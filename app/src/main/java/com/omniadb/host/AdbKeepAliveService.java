package com.omniadb.host;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ADB 常駐服務。
 *
 * 三重觸發，確保「無人職守」下 ADB 一定會被拉起來：
 *   1. 開機（BOOT_COMPLETED / LOCKED_BOOT_COMPLETED）
 *   2. 網路狀態改變（WiFi 重連、換 IP）
 *   3. 週期性看門狗（每 45 秒確認 5555 還在，不在就修）
 *
 * 修復流程（免 root）：
 *   寫 adb_wifi_enabled=1 → 等 adbd → 用 mDNS/掃描找出無線偵錯埠號
 *   → 用內建 ADB 客戶端下 tcpip:5555 → 之後電腦永遠 adb connect IP:5555 就好
 *
 * <p><b>自助授權（sideload 安裝的關鍵）：</b>如果 App 還沒有
 * WRITE_SECURE_SETTINGS，會在「找到無線偵錯埠、但還沒切到 legacy 埠」的那一瞬間
 * 用 {@code pm grant} 補上（{@link #ensurePermission(int)}）。那是唯一的機會窗口：
 * 切埠之後 TLS 埠消失就再也連不上，而重開機後無線偵錯是關的、根本沒有埠。
 * 這也是為什麼「直接裝 APK」的機器以前重開機就回不來。
 */
public class AdbKeepAliveService extends Service {

    private static final String TAG = "OmniADB";
    /**
     * 常駐通知的頻道 ID。
     *
     * <p>★ 為什麼是 {@code _v2}：通知頻道一旦建立，**行為欄位（含 showBadge）
     * 就鎖死了**，之後 App 再怎麼 {@code createNotificationChannel} 都不會生效，
     * 只有名稱／描述能改。舊頻道 {@code adb_always_on} 建立時沒關閉角標，
     * 桌面圖示右上角就一直掛著「1」。
     * 換一個新 ID 是唯一能讓既有安裝立刻生效的做法，舊頻道由
     * {@link #createChannel()} 順手刪掉。
     */
    private static final String CHANNEL_ID = "adb_always_on_v2";

    /** 舊版建立過、需要清掉的頻道（見 {@link #CHANNEL_ID} 的說明）。 */
    private static final String[] LEGACY_CHANNEL_IDS = {"adb_always_on"};

    private static final int NOTIFICATION_ID = 1001;
    private static final String MDNS_SERVICE_TYPE = "_adb-tls-connect._tcp";

    /** 自助授權用的權限與重試參數（見 {@link #ensurePermission(int)}）。 */
    private static final String SECURE_PERM = "android.permission.WRITE_SECURE_SETTINGS";
    private static final int GRANT_ATTEMPTS = 3;
    private static final long GRANT_RETRY_MS = 1_500L;

    /*
     * ★ 沒有權限時給使用者的指引，文字本體在 strings.xml（detail_perm_missing），
     *   這裡只留參數（套件名）。
     *
     *   舊版寫「請重新配對一次」是錯的 —— 兩個配對入口
     *   （PairingService.handleReply / MainActivity.doPair）都只寫 KEY_PAIRED，
     *   從來沒有下過 pm grant。照那句話重配一百次也拿不到權限。
     */

    private static final long WATCHDOG_INTERVAL_MS = 45_000L;
    private static final long BOOT_SETTLE_MS = 25_000L;
    private static final long DEFAULT_DELAY_MS = 2_000L;

    public static void start(Context ctx, boolean boot) {
        Intent i = new Intent(ctx, AdbKeepAliveService.class);
        i.putExtra("boot", boot);
        try {
            ctx.startForegroundService(i);
        } catch (Exception e) {
            Log.e(TAG, "無法啟動服務", e);
        }
    }

    private ScheduledExecutorService scheduler;
    private Handler handler;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private BroadcastReceiver screenReceiver;

    private final AtomicBoolean repairInProgress = new AtomicBoolean(false);
    private volatile long pendingRepairAt = Long.MAX_VALUE;

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "adb-keepalive");
            t.setDaemon(true);
            return t;
        });

        createChannel();
        ensureBootReceiverEnabled();
        registerNetworkCallback();
        registerScreenReceiver();

        scheduler.scheduleWithFixedDelay(this::watchdog, WATCHDOG_INTERVAL_MS,
                WATCHDOG_INTERVAL_MS, TimeUnit.MILLISECONDS);
        Log.i(TAG, "服務已建立，看門狗每 " + (WATCHDOG_INTERVAL_MS / 1000) + " 秒檢查一次");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean boot = intent != null && intent.getBooleanExtra("boot", false);
        startForegroundCompat(getString(R.string.notif_starting));

        if (!Store.prefs(this).getBoolean(Store.KEY_ENABLED, true)) {
            Store.setStatus(this, Msg.ST_DISABLED, Msg.D_DISABLED);
            updateNotification(getString(R.string.notif_disabled));
            return START_STICKY;
        }

        requestRepair(boot ? BOOT_SETTLE_MS : DEFAULT_DELAY_MS);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            if (networkCallback != null && connectivityManager != null) {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            }
        } catch (Exception ignored) {
        }
        try {
            if (screenReceiver != null) unregisterReceiver(screenReceiver);
        } catch (Exception ignored) {
        }
        if (scheduler != null) scheduler.shutdownNow();
        Log.i(TAG, "服務已結束");
    }

    // ---------------------------------------------------------------- 觸發來源

    /** 排程一次修復；若已有更早的排程則不覆蓋。 */
    private synchronized void requestRepair(long delayMs) {
        long when = System.currentTimeMillis() + delayMs;
        if (when >= pendingRepairAt) return;
        pendingRepairAt = when;
        handler.postDelayed(this::fireRepair, delayMs);
    }

    private void fireRepair() {
        synchronized (this) {
            pendingRepairAt = Long.MAX_VALUE;
        }
        scheduler.execute(this::repair);
    }

    private void watchdog() {
        try {
            if (!Store.prefs(this).getBoolean(Store.KEY_ENABLED, true)) return;
            repair();
        } catch (Exception e) {
            Log.e(TAG, "看門狗異常", e);
        }
    }

    private void registerNetworkCallback() {
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (connectivityManager == null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                Log.i(TAG, "偵測到網路可用，排程修復");
                requestRepair(3_000L);
            }

            @Override
            public void onLost(Network network) {
                Log.i(TAG, "偵測到網路中斷");
            }
        };
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback);
        } catch (Exception e) {
            Log.e(TAG, "註冊網路回呼失敗", e);
        }
    }

    private void registerScreenReceiver() {
        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.i(TAG, "螢幕/解鎖事件，排程修復：" + intent.getAction());
                requestRepair(2_000L);
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(screenReceiver, filter);
            }
        } catch (Exception e) {
            Log.e(TAG, "註冊螢幕事件失敗", e);
        }
    }

    // ---------------------------------------------------------------- 修復流程

    private void repair() {
        if (!repairInProgress.compareAndSet(false, true)) {
            Log.i(TAG, "已有修復進行中，略過");
            return;
        }
        final int target = Store.targetPort(this);
        final boolean paired = Store.prefs(this).getBoolean(Store.KEY_PAIRED, false);
        try {
            // ★ 先搶授權，必須在任何提前 return 之前。
            //
            //   這是修掉「直接裝 APK、配對成功卻一直顯示權限遺失」的關鍵：
            //   原本 ensurePermission() 寫在下面第 2 步，可是那條路上有三個
            //   提前 return ——「無線偵錯是關的」「還沒配對」「5555 已經開著」，
            //   任何一個成立都會讓這個唯一的窗口白白錯過。
            //   配對剛成功時無線偵錯一定開著、TLS 埠一定活著，就是最好的時機。
            if (paired && !hasWriteSecureSettings()
                    && readIntSetting("adb_wifi_enabled", 0) == 1) {
                int p = findWirelessDebugPort();
                if (p > 0) {
                    Store.prefs(this).edit().putInt(Store.KEY_LAST_WIFI_PORT, p).apply();
                    ensurePermission(p);
                } else {
                    Log.w(TAG, "已配對但找不到無線偵錯埠，這次無法自助授權");
                }
            }

            // 第 1 步：無線偵錯開關。
            // 這裡刻意「只讀不寫」——值本來就對的話完全沒有副作用，
            // 不會去重啟 adbd、也就不會把既有的遠端連線踢掉。
            boolean wifiOn = readIntSetting("adb_wifi_enabled", 0) == 1;
            if (!wifiOn) {
                if (!hasWriteSecureSettings()) {
                    trySelfGrant();
                }
                if (!hasWriteSecureSettings()) {
                    if (!paired) {
                        // 還沒配對過就還沒走到「需要權限」這一步，
                        // 這時候報「權限錯誤」只會嚇人（而且照做也沒用）。
                        Store.setStatus(this, Msg.ST_WAIT_PAIR, Msg.D_NEED_WIFI_PAIR);
                        updateNotification(getString(R.string.notif_wait_pair));
                    } else {
                        fail(Msg.D_PERM_MISSING, getPackageName());
                    }
                    return;
                }
                if (!waitForLan(60_000L)) {
                    fail(Msg.D_WAIT_WIFI_FAILED);
                    return;
                }
                Log.i(TAG, "無線偵錯是關的，重新開啟");
                Store.noteRepair(this);
                Store.setStatus(this, Msg.ST_REPAIRING, Msg.D_REOPEN_DEBUG);
                updateNotification(getString(R.string.notif_repairing));
                writeIntSetting("adb_wifi_enabled", 1);
                writeLongSetting("adb_allowed_connection_time", 0L);
                sleep(12_000L);
            } else if (hasWriteSecureSettings()
                    && readLongSetting("adb_allowed_connection_time", -1L) != 0L) {
                // 避免金鑰一週後過期，電腦又要重新授權
                writeLongSetting("adb_allowed_connection_time", 0L);
            }

            // 第 2 步：把 ADB 釘在固定埠。
            // 這步需要 App 自己的 ADB 金鑰（也就是要先在 App 內配對過）。
            // 沒配對也沒關係——電腦端的 AdbWatchdog 會用 adb tcpip 5555 做同一件事。
            if (!paired) {
                if (hasWriteSecureSettings()) {
                    Store.setOk(this, Msg.D_DEBUG_OPENED, Net.deviceIp(this));
                } else {
                    Store.setStatus(this, Msg.ST_WAIT_PAIR, Msg.D_NEED_PAIR);
                }
                return;
            }

            if (isPortOpen("127.0.0.1", target)) {
                markOk(target);
                return;
            }

            Store.setStatus(this, Msg.ST_REPAIRING, Msg.D_FIND_PORT);
            updateNotification(getString(R.string.notif_repairing));
            int wifiPort = findWirelessDebugPort();
            if (wifiPort <= 0) {
                fail(Msg.D_PORT_NOT_FOUND);
                return;
            }
            Store.prefs(this).edit().putInt(Store.KEY_LAST_WIFI_PORT, wifiPort).apply();
            Log.i(TAG, "無線偵錯埠號：" + wifiPort);

            // ★ 這裡是整個 App 唯一能自助取得 WRITE_SECURE_SETTINGS 的窗口。
            //
            //   條件全部同時成立才有機會：無線偵錯開著（所以有 TLS 埠）、
            //   金鑰已被授權（配對過）、而且**還沒切到 legacy 埠**。
            //   一旦送出 tcpip，TLS 埠就消失，libadb 的 TLS 握手連不上，
            //   之後就只能靠電腦補授權了 —— 所以這一段必須在 switchToPort 之前。
            if (!hasWriteSecureSettings()) {
                ensurePermission(wifiPort);
            }

            Store.setStatus(this, Msg.ST_REPAIRING, Msg.D_SWITCH_PORT,
                    String.valueOf(target));
            AdbClient adb = new AdbClient(this);
            boolean switched = adb.switchToPort("127.0.0.1", wifiPort, target);
            if (!switched) {
                String ip = Net.deviceIp(this);
                if (!"127.0.0.1".equals(ip)) {
                    switched = adb.switchToPort(ip, wifiPort, target);
                }
            }
            sleep(2_000L);

            if (isPortOpen("127.0.0.1", target)) {
                markOk(target);
            } else {
                fail(Msg.D_SWITCH_FAILED, String.valueOf(target));
            }
        } catch (Exception e) {
            Log.e(TAG, "修復流程發生例外", e);
            fail(Msg.D_REPAIR_EXCEPTION, String.valueOf(e.getMessage()));
        } finally {
            repairInProgress.set(false);
        }
    }

    private void markOk(int port) {
        String endpoint = Net.deviceIp(this) + ":" + port;
        if (!hasWriteSecureSettings()) {
            // 誠實講清楚：現在能用，但重開機後不會自動恢復。
            Store.setOk(this, Msg.D_OK_NO_PERM, endpoint, getPackageName());
            updateNotification(getString(R.string.notif_ok_no_perm, endpoint));
        } else {
            Store.setOk(this, Msg.D_OK, endpoint);
            updateNotification(getString(R.string.notif_ok, endpoint));
        }
    }

    /** 統一的失敗出口：狀態、日誌、通知三邊都寫同一句話（依目前語系）。 */
    private void fail(String detailCode, String... args) {
        String message = Msg.text(this, detailCode, args);
        Log.w(TAG, message);
        Store.setStatus(this, Msg.ST_ATTENTION, detailCode, args);
        updateNotification(getString(R.string.notif_attention, message));
    }

    private boolean hasWriteSecureSettings() {
        return checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 開機時的最後一搏：無線偵錯是關的，所以通常沒有埠可連。
     *
     * ★ 這裡刻意**不做全埠掃描**（原本會掃 30000-65535、96 條執行緒跑 30 秒，
     *   而那個狀態下 adbd 根本沒監聽任何 TCP，掃完必然空手而回）。
     *   只試上次記住的埠，成本趨近於零。
     *
     * 真正的機會在 {@link #repair()} 找到無線偵錯埠之後的那一段 —— 見
     * {@link #ensurePermission(int)}。
     */
    private void trySelfGrant() {
        if (hasWriteSecureSettings()) return;
        int last = Store.prefs(this).getInt(Store.KEY_LAST_WIFI_PORT, -1);
        if (last <= 0) {
            Log.i(TAG, "無線偵錯是關的、也沒有上次的埠號，無法自助授權");
            return;
        }
        ensurePermission(last);
    }

    /**
     * 在「有活埠」的窗口內自助取得 WRITE_SECURE_SETTINGS。
     *
     * <p>原理：{@code pm grant} 是由 adbd fork 出來的 shell 進程（uid 2000）執行的，
     * shell 本身就握有這個權限；App 只是拿自己的 ADB 金鑰請 adbd 代跑。
     * 所以前提永遠是同一個：<b>當下必須有一個活著的 adbd 埠可以連</b>。
     * 這也解釋了為什麼「重開機後無線偵錯關著」時誰都救不了 —— 那時一個埠都沒有。
     *
     * <p>因此呼叫點只有兩個，而且都在埠還活著的時候：
     * <ul>
     *   <li>{@link #repair()}：找到無線偵錯埠、<b>切埠之前</b>
     *       （切到 legacy 埠後 TLS 埠就消失，libadb 的 TLS 握手連不上）</li>
     *   <li>{@link #trySelfGrant()}：開機時試上次記住的埠（成功算賺到）</li>
     * </ul>
     *
     * @return 是否已取得權限
     */
    private boolean ensurePermission(int port) {
        if (hasWriteSecureSettings()) return true;
        if (port <= 0) return false;

        final String command = "pm grant " + getPackageName() + " " + SECURE_PERM;
        Log.i(TAG, "嘗試自助授權 WRITE_SECURE_SETTINGS（經 " + port + "）");
        Store.setStatus(this, Msg.ST_GRANTING, Msg.D_GRANT_SELF);

        for (int attempt = 1; attempt <= GRANT_ATTEMPTS; attempt++) {
            boolean ran = false;
            String out = "";
            try {
                out = new AdbClient(this).shell("127.0.0.1", port, command);
                ran = true;
            } catch (Exception e) {
                Log.w(TAG, "自助授權第 " + attempt + " 次連線失敗", e);
            }
            // 成功判準：權限真的生效，或指令跑完且沒有任何錯誤輸出。
            // （pm grant 成功時是空輸出；失敗才印 Error / Exception。）
            if (hasWriteSecureSettings() || (ran && !looksLikeError(out))) {
                Log.i(TAG, "自助授權成功（第 " + attempt + " 次嘗試）");
                // 順手關掉金鑰效期，否則一週後配對金鑰過期、又要重配一次
                writeLongSetting("adb_allowed_connection_time", 0L);
                // ★ 權限剛到手，順手把自動讀碼也開起來。
                //   這樣「配對一次」就把權限與無障礙一起備妥，使用者永遠不用碰設定頁；
                //   下次憑證失效要重配時，6 位數會自己讀。
                if (PairingCodeReader.enableSilently(this)) {
                    Log.i(TAG, "已順手開啟自動讀取配對碼");
                }
                return true;
            }
            Log.w(TAG, "自助授權第 " + attempt + " 次未生效：" + (out == null ? "" : out.trim()));
            if (attempt < GRANT_ATTEMPTS) sleep(GRANT_RETRY_MS);
        }
        Log.w(TAG, "自助授權失敗（連續 " + GRANT_ATTEMPTS + " 次），重開機後不會自動恢復");
        return false;
    }

    /** pm grant 成功時沒有輸出，失敗才會印 Error / Exception。 */
    private static boolean looksLikeError(String out) {
        if (out == null || out.trim().isEmpty()) return false;
        String o = out.toLowerCase();
        return o.contains("error") || o.contains("exception")
                || o.contains("not allowed") || o.contains("unknown permission");
    }

    private int readIntSetting(String key, int def) {
        try {
            return Settings.Global.getInt(getContentResolver(), key, def);
        } catch (Exception e) {
            return def;
        }
    }

    private long readLongSetting(String key, long def) {
        try {
            return Settings.Global.getLong(getContentResolver(), key, def);
        } catch (Exception e) {
            return def;
        }
    }

    private void writeIntSetting(String key, int value) {
        try {
            Settings.Global.putInt(getContentResolver(), key, value);
            Log.i(TAG, "已寫入 " + key + " = " + value);
        } catch (SecurityException e) {
            Log.e(TAG, "寫入 " + key + " 被拒絕", e);
        }
    }

    private void writeLongSetting(String key, long value) {
        try {
            Settings.Global.putLong(getContentResolver(), key, value);
            Log.i(TAG, "已寫入 " + key + " = " + value);
        } catch (SecurityException e) {
            Log.e(TAG, "寫入 " + key + " 被拒絕", e);
        }
    }

    private boolean waitForLan(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Net.lanConnected(this)) {
                String ip = Net.deviceIp(this);
                if (!"127.0.0.1".equals(ip) && !"0.0.0.0".equals(ip)) return true;
            }
            sleep(1_000L);
        }
        return false;
    }

    // ---------------------------------------------------------------- 找埠號

    private int findWirelessDebugPort() {
        AdbClient adb = new AdbClient(this);

        int last = Store.prefs(this).getInt(Store.KEY_LAST_WIFI_PORT, -1);
        if (last > 0 && isPortOpen("127.0.0.1", last) && adb.connect("127.0.0.1", last)) {
            Log.i(TAG, "沿用上次的埠號：" + last);
            return last;
        }

        int mdns = discoverViaMdns();
        if (mdns > 0 && adb.connect("127.0.0.1", mdns)) {
            Log.i(TAG, "mDNS 找到埠號：" + mdns);
            return mdns;
        }

        return scanForAdbPort(adb);
    }

    private int discoverViaMdns() {
        NsdManager nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
        if (nsd == null) return -1;

        final int[] result = {-1};
        final CountDownLatch latch = new CountDownLatch(1);
        final String myIp = Net.deviceIp(this);

        NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String type) {
            }

            @Override
            public void onServiceFound(NsdServiceInfo info) {
                try {
                    nsd.resolveService(info, new NsdManager.ResolveListener() {
                        @Override
                        public void onResolveFailed(NsdServiceInfo i, int errorCode) {
                        }

                        @Override
                        public void onServiceResolved(NsdServiceInfo i) {
                            InetAddress host = i.getHost();
                            if (host == null) return;
                            String h = host.getHostAddress();
                            if (h == null) return;
                            if (h.equals(myIp) || h.startsWith("127.")) {
                                result[0] = i.getPort();
                                latch.countDown();
                            }
                        }
                    });
                } catch (Exception ignored) {
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo info) {
            }

            @Override
            public void onDiscoveryStopped(String type) {
            }

            @Override
            public void onStartDiscoveryFailed(String type, int errorCode) {
                latch.countDown();
            }

            @Override
            public void onStopDiscoveryFailed(String type, int errorCode) {
            }
        };

        try {
            nsd.discoverServices(MDNS_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener);
            latch.await(8, TimeUnit.SECONDS);
        } catch (Exception e) {
            Log.e(TAG, "mDNS 探索失敗", e);
        } finally {
            try {
                nsd.stopServiceDiscovery(listener);
            } catch (Exception ignored) {
            }
        }
        return result[0];
    }

    private int scanForAdbPort(AdbClient adb) {
        final int min = 30000;
        final int max = 65535;
        final int threads = 96;
        Log.i(TAG, "mDNS 失敗，改用埠號掃描 " + min + "-" + max);
        final AtomicInteger found = new AtomicInteger(-1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int port = min; port <= max; port++) {
            final int p = port;
            pool.execute(() -> {
                if (found.get() >= 0) return;
                if (!isPortOpen("127.0.0.1", p)) return;
                if (adb.connect("127.0.0.1", p)) {
                    found.compareAndSet(-1, p);
                }
            });
        }
        pool.shutdown();
        try {
            pool.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        Log.i(TAG, "掃描結果：" + found.get());
        return found.get();
    }

    private boolean isPortOpen(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 300);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- 通知 / 自我修復

    /**
     * 部分裝置（尤其國產 ROM）會把 App 的開機廣播接收器停用，一停用就再也不會
     * 被 BOOT_COMPLETED 喚醒。這裡每次服務啟動都檢查並修回來。
     */
    private void ensureBootReceiverEnabled() {
        try {
            ComponentName receiver = new ComponentName(this, BootReceiver.class);
            int state = getPackageManager().getComponentEnabledSetting(receiver);
            if (state != PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    && state != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
                getPackageManager().setComponentEnabledSetting(receiver,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP);
                Log.i(TAG, "開機接收器曾被停用，已重新啟用");
            }
        } catch (Exception e) {
            Log.e(TAG, "檢查開機接收器失敗", e);
        }
    }

    /**
     * 建立（或更新）常駐通知頻道，並清掉舊頻道。
     *
     * <p><b>角標（桌面圖示右上角的「1」）就是在這裡關掉的。</b>
     * 常駐通知只是告訴使用者「服務活著」，不該在桌面累積未讀角標；
     * 而 Android 的角標是**頻道層級**的開關（{@code setShowBadge(false)}），
     * 不是通知層級 —— 所以只改 {@code buildNotification()} 沒用。
     */
    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notif_channel_desc));
        channel.setShowBadge(false);      // ★ 不顯示桌面角標
        channel.setSound(null, null);     // 靜音（IMPORTANCE_LOW 本來就不響，雙保險）
        channel.enableVibration(false);
        channel.enableLights(false);
        nm.createNotificationChannel(channel);

        // 舊頻道（showBadge=true，關不掉）直接刪除，順帶清掉它殘留的通知。
        for (String legacy : LEGACY_CHANNEL_IDS) {
            try {
                NotificationChannel old = nm.getNotificationChannel(legacy);
                if (old != null) {
                    nm.deleteNotificationChannel(legacy);
                    Log.i(TAG, "已刪除舊通知頻道：" + legacy + "（它的角標關不掉）");
                }
            } catch (Exception e) {
                Log.w(TAG, "刪除舊通知頻道失敗：" + legacy, e);
            }
        }
    }

    private Notification buildNotification(String text) {
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true)
                // 通知層級再保險一次：就算某個 ROM 忽略頻道的 showBadge，
                // BADGE_ICON_NONE + number=0 也不會畫出數字角標。
                .setBadgeIconType(Notification.BADGE_ICON_NONE)
                .setNumber(0)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void startForegroundCompat(String text) {
        Notification n = buildNotification(text);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private void updateNotification(String text) {
        try {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text));
        } catch (Exception e) {
            Log.e(TAG, "更新通知失敗", e);
        }
    }
}
