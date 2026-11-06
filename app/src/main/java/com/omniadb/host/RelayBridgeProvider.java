package com.omniadb.host;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.util.Log;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 給 5G-Proxy Tailnet Relay 的「窄介面」：只提供四個寫死的動作。
 *
 * <p>設計底線（改動前請先讀）：
 * <ul>
 *   <li><b>這不是一般用途的 adb shell。</b> 呼叫端傳進來的 {@code method} 只能是
 *       ping / airplane_on / airplane_off / grant_secure；{@code arg} 與 {@code extras}
 *       <b>完全不參與指令組成</b>。真正的 shell 指令全部寫死在本檔，呼叫端沒有任何
 *       辦法塞進自己的字串。開成通用 shell 等於把整支手機的 shell 交給一個
 *       「對 tailnet 開 SOCKS、又有權杖控制 API」的 App。</li>
 *   <li><b>ADB 私鑰留在 OmniADB-Host。</b> relay 不持有私鑰、不自行配對，
 *       也不必複製 libadb / 配對流程；這裡直接沿用 {@link AdbClient#shell}。</li>
 *   <li><b>只認套件名 {@value #CALLER}。</b> 同一台裝置不可能存在第二個同套件名的 App，
 *       所以不需要再讓使用者輸入一組配對碼 —— 配對碼放在 relay 自己的 prefs 裡，
 *       惡意的 relay 本來就讀得到，對這個威脅模型沒有額外防禦力，只會多一步操作。</li>
 *   <li>兩邊 APK 使用不同簽章金鑰，因此<b>不能</b>用 signature permission 保護；
 *       改用 {@link Binder#getCallingUid()} 反查套件名。ContentProvider 而非廣播，
 *       是因為廣播拿不到可靠的呼叫端身分。</li>
 * </ul>
 *
 * <p>成敗判準不在这里：本介面只回「指令有沒有跑完」，射頻是否真的切了由 relay 的
 * {@code AirplaneToggle.awaitRadio()} 自己驗證（設定值會被自己寫進去，不能拿來驗證）。
 */
public class RelayBridgeProvider extends ContentProvider {

    private static final String TAG = "OmniADB";

    public static final String AUTHORITY = "com.omniadb.host.relay";
    private static final String CALLER = "com.tokyoxpa3.tailnetrelay";

    private static final String M_PING = "ping";
    private static final String M_AIRPLANE_ON = "airplane_on";
    private static final String M_AIRPLANE_OFF = "airplane_off";
    private static final String M_GRANT_SECURE = "grant_secure";

    /** shell 一律要有硬逾時：AdbStream.read() 在 A_CLSE 後可能永久阻塞。 */
    private static final long SHELL_TIMEOUT_MS = 12_000L;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle reply = new Bundle();
        if (!isAllowedCaller()) {
            Log.w(TAG, "RelayBridge: rejected caller uid=" + Binder.getCallingUid());
            reply.putBoolean("ok", false);
            reply.putString("error", "caller_not_allowed");
            return reply;
        }

        // 探測用：不碰 ADB，只證明「Host 裝了、版本夠新、呼叫端合法」。
        if (M_PING.equals(method)) {
            reply.putBoolean("ok", true);
            return reply;
        }

        final String command;
        if (M_AIRPLANE_ON.equals(method)) {
            command = "cmd connectivity airplane-mode enable";
        } else if (M_AIRPLANE_OFF.equals(method)) {
            command = "cmd connectivity airplane-mode disable";
        } else if (M_GRANT_SECURE.equals(method)) {
            // 讓 relay 能設定 AIRPLANE_MODE_RADIOS（換 IP 時保留 Wi-Fi）。
            // 寫死套件名與權限，呼叫端無法改成別的目標。
            command = "pm grant " + CALLER + " android.permission.WRITE_SECURE_SETTINGS";
        } else {
            reply.putBoolean("ok", false);
            reply.putString("error", "unknown_method");
            return reply;
        }

        try {
            String out = runShell(command);
            boolean ok = !looksLikeError(out);
            reply.putBoolean("ok", ok);
            if (!ok) reply.putString("error", out == null ? "empty_output" : out.trim());
            Log.i(TAG, "RelayBridge: " + method + " -> " + (ok ? "ok" : "failed"));
        } catch (Exception e) {
            reply.putBoolean("ok", false);
            reply.putString("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            Log.w(TAG, "RelayBridge: " + method + " failed", e);
        }
        return reply;
    }

    /** 呼叫端身分以 UID 反查為準；getCallingPackage() 在 provider.call() 可能為 null。 */
    private boolean isAllowedCaller() {
        Context ctx = getContext();
        if (ctx == null) return false;
        String[] pkgs = ctx.getPackageManager().getPackagesForUid(Binder.getCallingUid());
        if (pkgs != null) {
            for (String p : pkgs) {
                if (CALLER.equals(p)) return true;
            }
        }
        return false;
    }

    private String runShell(final String command) throws Exception {
        final Context ctx = getContext();
        // 每次用獨立的 daemon 執行緒，逾時就放棄；卡住的 shell 不會累積在
        // binder 執行緒池裡，也不會拖住 ContentResolver.call() 的呼叫端。
        ExecutorService ex = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "relay-bridge-shell");
            t.setDaemon(true);
            return t;
        });
        try {
            Future<String> f = ex.submit(() -> {
                int port = Store.targetPort(ctx);
                try {
                    return new AdbClient(ctx).shell("127.0.0.1", port, command);
                } catch (Exception first) {
                    // 少數情況 adbd 還沒切到固定埠；試一次上次記住的無線偵錯埠。
                    int last = Store.prefs(ctx).getInt(Store.KEY_LAST_WIFI_PORT, -1);
                    if (last > 0 && last != port) {
                        return new AdbClient(ctx).shell("127.0.0.1", last, command);
                    }
                    throw first;
                }
            });
            return f.get(SHELL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } finally {
            ex.shutdownNow();
        }
    }

    private static boolean looksLikeError(String out) {
        if (out == null) return false;
        String s = out.toLowerCase(Locale.ROOT);
        return s.contains("error") || s.contains("exception")
                || s.contains("permission denial") || s.contains("unknown command")
                || s.contains("can't find service") || s.contains("not found");
    }

    // ------------------------------------------------------------------
    // 這個 provider 不是資料來源：所有標準 CRUD 一律拒絕，避免被拿來當一般
    // ContentProvider 探測或寫入。
    // ------------------------------------------------------------------

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        Log.w(TAG, "RelayBridge: query is not supported");
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        Log.w(TAG, "RelayBridge: insert is not supported");
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        Log.w(TAG, "RelayBridge: delete is not supported");
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        Log.w(TAG, "RelayBridge: update is not supported");
        return 0;
    }
}
