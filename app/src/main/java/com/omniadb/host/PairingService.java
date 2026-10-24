package com.omniadb.host;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一次性配對服務（免分割畫面、免切換 App）。
 *
 * <h3>為什麼要這樣設計</h3>
 * 無線偵錯的「配對伺服器」只在「使用配對碼配對裝置」那個系統對話框開著時才存活，
 * 對話框一關（例如切到別的 App）伺服器就消失，配對一定失敗。而使用者又必須同時
 * 看到配對碼與輸入框，於是常見做法是「分割畫面」——體驗很差。
 *
 * <p>本服務改用 <b>通知欄內嵌輸入框</b>（{@link RemoteInput}）解決：
 * <ol>
 *   <li>服務在背景以 mDNS 探索 {@code _adb-tls-pairing._tcp}，自動取得配對埠
 *       （因此使用者<b>只要輸入 6 位數配對碼，不必自己抄埠號</b>）。</li>
 *   <li>同時在通知欄放一個帶輸入框的通知。</li>
 *   <li>使用者打開系統的「使用配對碼配對裝置」看到配對碼後，
 *       <b>直接下拉通知欄</b>輸入配對碼——通知欄是系統覆蓋層，
 *       底下的配對對話框不會被關掉，伺服器自然還活著。</li>
 *   <li>送出後本服務在背景完成 SPAKE2 配對、自助取得 WRITE_SECURE_SETTINGS，
 *       再交給 {@link AdbKeepAliveService} 把 ADB 釘到固定埠。</li>
 * </ol>
 */
public class PairingService extends Service {

    private static final String TAG = "OmniADB";
    private static final String CHANNEL_ID = "omniadb_pairing";
    private static final int NOTIFICATION_ID = 1002;

    public static final String ACTION_START = "com.omniadb.host.action.PAIRING_START";
    public static final String ACTION_REPLY = "com.omniadb.host.action.PAIRING_REPLY";
    public static final String KEY_CODE = "pairing_code";

    /** Android 無線偵錯的配對服務 mDNS 型別。 */
    private static final String MDNS_PAIRING = "_adb-tls-pairing._tcp";

    /** 配對重試次數：讀碼常常比 mDNS 找埠還快，第一次很容易打到死埠。 */
    private static final int PAIR_ATTEMPTS = 3;
    private static final long PAIR_PORT_WAIT_MS = 12_000L;

    /** mDNS 找到的配對埠；-1 表示尚未找到。 */
    private static final AtomicInteger pairingPort = new AtomicInteger(-1);

    private NsdManager nsd;
    private NsdManager.DiscoveryListener discoveryListener;

    // ------------------------------------------------------------------ 對外入口

    public static void start(Context ctx) {
        // ★ Android 13+ 沒給 POST_NOTIFICATIONS 時，這個服務的通知（也就是配對碼
        //   輸入框）會被系統整個丟掉 —— 服務照樣在前景跑、mDNS 照樣找到埠，
        //   但使用者看到的是「按了開始配對，什麼都沒發生」。在日誌留下證據。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "沒有通知權限：配對碼輸入框不會顯示，配對必定失敗");
        }
        // 讓 PairingCodeReader 可以開始讀配對碼（10 分鐘內有效）。
        // 沒有開無障礙服務時這行無害，只是沒有人會去讀。
        Store.wantPairing(ctx);
        dispatch(ctx, new Intent(ctx, PairingService.class).setAction(ACTION_START));
    }

    /** 由通知的 RemoteInput 回覆呼叫，把配對碼送進服務。 */
    public static void reply(Context ctx, String code) {
        dispatch(ctx, new Intent(ctx, PairingService.class)
                .setAction(ACTION_REPLY)
                .putExtra(KEY_CODE, code));
    }

    private static void dispatch(Context ctx, Intent i) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Exception e) {
            Log.e(TAG, "無法啟動配對服務", e);
        }
    }

    // ------------------------------------------------------------------ 生命週期

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForegroundCompat(buildNotification(getString(R.string.pair_hint_searching)));
        startDiscovery();
        Log.i(TAG, "配對服務已啟動，開始 mDNS 探索 " + MDNS_PAIRING);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 任何一次啟動都要先站上前景，否則 Android 12+ 會直接砍掉服務。
        startForegroundCompat(buildNotification(currentHint()));

        if (intent != null && ACTION_REPLY.equals(intent.getAction())) {
            String code = intent.getStringExtra(KEY_CODE);
            if (code == null) {
                // 保險：有些機型會把 RemoteInput 結果留在 intent 裡
                android.os.Bundle r = RemoteInput.getResultsFromIntent(intent);
                if (r != null) {
                    CharSequence cs = r.getCharSequence(KEY_CODE);
                    if (cs != null) code = cs.toString();
                }
            }
            if (code != null && !code.trim().isEmpty()) {
                handleReply(code.trim());
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopDiscovery();
        Log.i(TAG, "配對服務已結束");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------ 配對流程

    /**
     * 用配對碼跟本機 adbd 配對（含「埠還沒找到 / 埠已失效」的重試）。
     *
     * ★ 為什麼需要重試：無障礙讀碼是「對話框一出現就讀到」，
     *   常常比 mDNS 找到**這一個**對話框的埠還快。那時 {@link #pairingPort}
     *   可能還是上一次對話框留下的死埠 → 直接 ECONNREFUSED。
     *   所以這裡要做三件事：等埠、失敗就丟掉舊埠重新探索、再試一次。
     */
    private void handleReply(final String code) {
        updateNotification(getString(R.string.pair_notif_pairing));
        new Thread(() -> {
            AdbClient adb = new AdbClient(this);
            boolean ok = false;
            String lastError = Msg.D_PAIR_FAILED;

            for (int attempt = 1; attempt <= PAIR_ATTEMPTS && !ok; attempt++) {
                int port = awaitPairingPort(PAIR_PORT_WAIT_MS);
                if (port <= 0) {
                    lastError = Msg.D_PAIR_NO_PORT;
                    break;
                }
                Store.setStatus(this, Msg.ST_PAIRING, Msg.D_PAIR_TRUST_PORT,
                        String.valueOf(port));

                ok = adb.pair("127.0.0.1", port, code);
                if (!ok) {
                    String ip = Net.deviceIp(this);
                    if (!"127.0.0.1".equals(ip)) {
                        ok = adb.pair(ip, port, code);
                    }
                }
                if (ok) break;

                // 失敗了，先判斷是「埠死了」還是「碼錯了」。
                if (isPortOpen("127.0.0.1", port)) {
                    // 埠還活著 → 是配對碼不對，重試沒用
                    lastError = Msg.D_PAIR_WRONG_CODE;
                    break;
                }

                // 埠死了（對話框被關掉又重開，埠號換了）→ 丟掉它、重新探索再試
                Log.i(TAG, "配對埠 " + port + " 已失效，重新探索（第 " + attempt + " 次）");
                pairingPort.compareAndSet(port, -1);
                restartDiscovery();
                lastError = Msg.D_PAIR_SERVER_CLOSED;
            }

            if (!ok) {
                fail(lastError);
                return;
            }

            Store.prefs(this).edit()
                    .putBoolean(Store.KEY_PAIRED, true)
                    .putBoolean(Store.KEY_ENABLED, true)
                    .apply();
            // 配對完成 → 立刻讓無障礙服務閉眼，不再讀任何畫面
            Store.clearWantPairing(this);
            Store.setStatus(this, Msg.ST_PAIRED, Msg.D_PAIR_OK);
            Log.i(TAG, "配對成功，交給常駐服務釘埠");
            AdbKeepAliveService.start(this, false);
            stopSelf();
        }, "adb-pair").start();
    }

    /**
     * 等 mDNS 找到配對埠。已經有埠就立刻回傳。
     *
     * 這裡**不判斷埠是否還活著** —— 呼叫端會在失敗後自己丟掉死埠，
     * 因為「先拿到舊埠、失敗、再拿新埠」比「等到天荒地老」可靠。
     */
    private int awaitPairingPort(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            int p = pairingPort.get();
            if (p > 0) return p;
            try {
                Thread.sleep(200L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }
        return pairingPort.get();
    }

    private void fail(String detailCode) {
        String message = Msg.text(this, detailCode);
        Log.w(TAG, message);
        Store.setStatus(this, Msg.ST_ATTENTION, detailCode);
        updateNotification(message);
    }

    private String currentHint() {
        int p = pairingPort.get();
        if (p > 0) return getString(R.string.pair_hint_found, p);
        return getString(R.string.pair_hint_go);
    }

    // ------------------------------------------------------------------ mDNS 探索

    private void startDiscovery() {
        nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
        if (nsd == null) {
            Log.e(TAG, "此裝置沒有 NsdManager，無法自動找配對埠");
            return;
        }
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String type) {
                Log.i(TAG, "mDNS 探索開始");
            }

            @Override
            public void onServiceFound(NsdServiceInfo info) {
                Log.i(TAG, "發現服務：" + info.getServiceName());
                try {
                    nsd.resolveService(info, new NsdManager.ResolveListener() {
                        @Override
                        public void onResolveFailed(NsdServiceInfo i, int errorCode) {
                            Log.w(TAG, "解析服務失敗：" + errorCode);
                        }

                        @Override
                        public void onServiceResolved(NsdServiceInfo i) {
                            acceptCandidate(i);
                        }
                    });
                } catch (Exception e) {
                    Log.w(TAG, "resolveService 丟出例外", e);
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
                Log.e(TAG, "mDNS 探索啟動失敗：" + errorCode);
            }

            @Override
            public void onStopDiscoveryFailed(String type, int errorCode) {
            }
        };
        try {
            nsd.discoverServices(MDNS_PAIRING, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (Exception e) {
            Log.e(TAG, "discoverServices 丟出例外", e);
        }
    }

    /**
     * 判斷這個 mDNS 結果是不是「本機自己的」配對服務。
     *
     * <p>同一個網段可能有好幾台裝置都在廣播 {@code _adb-tls-pairing._tcp}，
     * 我們只認自己這台：主機位址等於本機 LAN IP，或該埠在 127.0.0.1 上真的開著
     * （配對伺服器綁 0.0.0.0，所以本機 loopback 一定連得到）。
     */
    private void acceptCandidate(NsdServiceInfo info) {
        int port = info.getPort();
        if (port <= 0) return;

        String host = "";
        java.net.InetAddress addr = info.getHost();
        if (addr != null && addr.getHostAddress() != null) host = addr.getHostAddress();

        String myIp = Net.deviceIp(this);
        boolean mine = host.equals(myIp)
                || host.startsWith("127.")
                || isPortOpen("127.0.0.1", port);
        if (!mine) {
            Log.i(TAG, "略過非本機的配對服務 " + host + ":" + port);
            return;
        }
        if (pairingPort.get() == port) return;

        pairingPort.set(port);
        Log.i(TAG, "接受配對埠 " + port + "（host=" + host + "）");
        updateNotification(currentHint());
    }

    private boolean isPortOpen(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 400);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void stopDiscovery() {
        if (nsd != null && discoveryListener != null) {
            try {
                nsd.stopServiceDiscovery(discoveryListener);
            } catch (Exception ignored) {
            }
        }
        discoveryListener = null;
    }

    /** 重新啟動 mDNS 探索（配對埠可能因為畫面重開而改變）。 */
    private void restartDiscovery() {
        stopDiscovery();
        // NsdManager 停止後立刻重啟常常失敗，隔一下再起。
        new android.os.Handler(getMainLooper()).postDelayed(this::startDiscovery, 800L);
    }

    // ------------------------------------------------------------------ 通知

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, getString(R.string.pair_channel_name),
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(getString(R.string.pair_channel_desc));
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        Intent reply = new Intent(this, PairingReceiver.class).setAction(ACTION_REPLY);
        PendingIntent pi = PendingIntent.getBroadcast(this, 0, reply,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);

        RemoteInput input = new RemoteInput.Builder(KEY_CODE)
                .setLabel(getString(R.string.pair_input_label))
                .build();

        Notification.Action action = new Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_send),
                getString(R.string.pair_action_label), pi)
                .addRemoteInput(input)
                .build();

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.pair_notif_title))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true)
                .addAction(action)
                .build();
    }

    private void startForegroundCompat(Notification n) {
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
            Log.e(TAG, "更新配對通知失敗", e);
        }
    }
}
