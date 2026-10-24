package com.omniadb.host;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 統一存放裝置保護儲存空間（device-protected storage）的偏好設定。
 *
 * 為什麼要用 device-protected storage：本 App 必須在「開機但尚未解鎖」的階段
 * （LOCKED_BOOT_COMPLETED）就能運作，那個階段 credential-protected 的檔案與
 * 偏好設定都還無法讀取。把 ADB 金鑰與狀態放在 device-protected 空間，
 * 才能確保重開機後沒人碰手機也照樣自動把 ADB 拉起來。
 */
public final class Store {

    public static final String PREFS = "omniadb_host";

    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_PAIRED = "paired";
    /**
     * 狀態「代碼」（{@link Msg#ST_OK} 等），不是給人看的文字。
     * 顯示時才由 {@link Msg#label} 依目前語系翻譯 —— 見 {@link Msg} 的說明。
     */
    public static final String KEY_STATUS = "status";
    /** 狀態說明「代碼」（{@link Msg#D_SWITCH_PORT} 等）。 */
    public static final String KEY_DETAIL_CODE = "detail_code";
    /** 說明代碼的參數（埠號、IP、套件名…），以 {@link Msg#join} 序列化。 */
    public static final String KEY_DETAIL_ARGS = "detail_args";
    /** 舊版直接把中文句子存在這裡，現在只為了升級時清掉它。 */
    private static final String KEY_DETAIL_LEGACY = "detail";
    public static final String KEY_LAST_OK_TS = "last_ok_ts";
    public static final String KEY_LAST_REPAIR_TS = "last_repair_ts";
    public static final String KEY_REPAIR_COUNT = "repair_count";
    public static final String KEY_TARGET_PORT = "target_port";
    public static final String KEY_LAST_WIFI_PORT = "last_wifi_port";
    /** 「使用者正在配對」的旗標：只有它為真時，無障礙服務才會去讀配對碼。 */
    public static final String KEY_WANT_PAIR = "want_pair";
    public static final String KEY_WANT_PAIR_TS = "want_pair_ts";

    /** 配對請求的有效期。過了就當作使用者已經放棄，不再自動讀碼。 */
    private static final long WANT_PAIR_TTL_MS = 10 * 60 * 1000L;

    private Store() {
    }

    public static Context dp(Context c) {
        return c.isDeviceProtectedStorage() ? c : c.createDeviceProtectedStorageContext();
    }

    public static SharedPreferences prefs(Context c) {
        return dp(c).getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * 寫入狀態。傳進來的是**代碼**，不是文字。
     *
     * @param statusCode {@link Msg#ST_OK} 之類的狀態代碼
     * @param detailCode {@link Msg#D_SWITCH_PORT} 之類的說明代碼
     * @param args       說明文字要用的參數（埠號、IP、套件名…）
     */
    public static void setStatus(Context c, String statusCode, String detailCode, String... args) {
        prefs(c).edit()
                .putString(KEY_STATUS, statusCode)
                .putString(KEY_DETAIL_CODE, detailCode == null ? "" : detailCode)
                .putString(KEY_DETAIL_ARGS, Msg.join(args))
                .remove(KEY_DETAIL_LEGACY)
                .apply();
    }

    /** 寫入「運作中」狀態並更新「上次正常」時間。 */
    public static void setOk(Context c, String detailCode, String... args) {
        prefs(c).edit()
                .putString(KEY_STATUS, Msg.ST_OK)
                .putString(KEY_DETAIL_CODE, detailCode == null ? "" : detailCode)
                .putString(KEY_DETAIL_ARGS, Msg.join(args))
                .remove(KEY_DETAIL_LEGACY)
                .putLong(KEY_LAST_OK_TS, System.currentTimeMillis())
                .apply();
    }

    public static void noteRepair(Context c) {
        SharedPreferences p = prefs(c);
        p.edit()
                .putLong(KEY_LAST_REPAIR_TS, System.currentTimeMillis())
                .putInt(KEY_REPAIR_COUNT, p.getInt(KEY_REPAIR_COUNT, 0) + 1)
                .apply();
    }

    public static int targetPort(Context c) {
        return prefs(c).getInt(KEY_TARGET_PORT, 5555);
    }

    // ---------------------------------------------------------------- 配對請求

    /**
     * 標記「使用者按下開始配對」。
     *
     * <p>{@link PairingCodeReader} 只在這個旗標為真、且還沒過期時才會讀畫面 ——
     * 這是隱私邊界：無障礙服務一直開著也不會平常就在看東西。
     */
    public static void wantPairing(Context c) {
        prefs(c).edit()
                .putBoolean(KEY_WANT_PAIR, true)
                .putLong(KEY_WANT_PAIR_TS, System.currentTimeMillis())
                .apply();
    }

    /** 配對成功後呼叫，讓無障礙服務立刻閉眼。 */
    public static void clearWantPairing(Context c) {
        prefs(c).edit().putBoolean(KEY_WANT_PAIR, false).apply();
    }

    public static boolean wantsPairing(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.getBoolean(KEY_WANT_PAIR, false)) return false;
        long ts = p.getLong(KEY_WANT_PAIR_TS, 0L);
        return ts > 0 && System.currentTimeMillis() - ts < WANT_PAIR_TTL_MS;
    }
}
