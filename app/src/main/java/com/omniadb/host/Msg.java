package com.omniadb.host;

import android.content.Context;

/**
 * 狀態碼 → 目前語系的文字。
 *
 * <h3>為什麼不直接把中文句子存進 SharedPreferences</h3>
 * 狀態是**寫一次、讀很多次**的：服務在背景寫入，畫面每 2 秒輪詢一次，
 * 通知也是同一個來源。如果直接把「運作中」「需要處理」這種字串存起來，
 * 使用者把系統語言從中文改成英文之後，那些舊字串還是中文 ——
 * 而且顏色判斷（{@code "運作中".equals(status)}）也會跟著一起壞掉。
 *
 * <p>所以這裡把「狀態」與「顯示文字」拆開：
 * <ul>
 *   <li>{@link Store} 只存<b>語系無關的代碼</b>（{@link #ST_OK}、{@link #D_SWITCH_PORT} …）</li>
 *   <li>畫面與通知在**要顯示的那一刻**才用 {@link #label} / {@link #fromStored} 翻成字串</li>
 * </ul>
 * 這樣切換語系不用重啟服務，狀態文字立刻跟著變；顏色判斷也只認代碼，不會因為翻譯而錯。
 *
 * <p>未知代碼（例如舊版留下來的中文字串）原樣顯示，不做任何猜測。
 */
public final class Msg {

    // ---------------------------------------------------------------- 狀態碼
    public static final String ST_NOT_STARTED = "not_started";
    public static final String ST_OK = "ok";
    public static final String ST_ATTENTION = "attention";
    public static final String ST_DISABLED = "disabled";
    public static final String ST_WAIT_PAIR = "wait_pair";
    public static final String ST_REPAIRING = "repairing";
    public static final String ST_PAIRING = "pairing";
    public static final String ST_PAIRED = "paired";
    public static final String ST_GRANTING = "granting";

    // ---------------------------------------------------------------- 說明碼
    public static final String D_NONE = "";
    public static final String D_DISABLED = "d_disabled";
    public static final String D_NEED_WIFI_PAIR = "d_need_wifi_pair";
    public static final String D_NEED_PAIR = "d_need_pair";
    public static final String D_PERM_MISSING = "d_perm_missing";
    public static final String D_WAIT_WIFI_FAILED = "d_wait_wifi_failed";
    public static final String D_REOPEN_DEBUG = "d_reopen_debug";
    public static final String D_DEBUG_OPENED = "d_debug_opened";
    public static final String D_FIND_PORT = "d_find_port";
    public static final String D_PORT_NOT_FOUND = "d_port_not_found";
    public static final String D_SWITCH_PORT = "d_switch_port";
    public static final String D_SWITCH_FAILED = "d_switch_failed";
    public static final String D_REPAIR_EXCEPTION = "d_repair_exception";
    public static final String D_OK = "d_ok";
    public static final String D_OK_NO_PERM = "d_ok_no_perm";
    public static final String D_GRANT_SELF = "d_grant_self";
    public static final String D_PAIR_TRUST = "d_pair_trust";
    public static final String D_PAIR_TRUST_PORT = "d_pair_trust_port";
    public static final String D_PAIR_FAILED = "d_pair_failed";
    public static final String D_PAIR_OK = "d_pair_ok";
    public static final String D_PAIR_NO_PORT = "d_pair_no_port";
    public static final String D_PAIR_WRONG_CODE = "d_pair_wrong_code";
    public static final String D_PAIR_SERVER_CLOSED = "d_pair_server_closed";

    /** 多個參數在 SharedPreferences 裡的間隔字元（單元分隔符，正常文字不會出現）。 */
    private static final char ARG_SEP = '\u001F';

    private Msg() {
    }

    // ---------------------------------------------------------------- 對外

    /** 狀態代碼 → 目前語系的短標籤（運作中／Running…）。 */
    public static String label(Context c, String statusCode) {
        if (statusCode == null || statusCode.isEmpty()) {
            return c.getString(R.string.status_not_started);
        }
        switch (statusCode) {
            case ST_OK:
                return c.getString(R.string.status_ok);
            case ST_ATTENTION:
                return c.getString(R.string.status_attention);
            case ST_DISABLED:
                return c.getString(R.string.status_disabled);
            case ST_WAIT_PAIR:
                return c.getString(R.string.status_wait_pair);
            case ST_REPAIRING:
                return c.getString(R.string.status_repairing);
            case ST_PAIRING:
                return c.getString(R.string.status_pairing);
            case ST_PAIRED:
                return c.getString(R.string.status_paired);
            case ST_GRANTING:
                return c.getString(R.string.status_granting);
            case ST_NOT_STARTED:
                return c.getString(R.string.status_not_started);
            default:
                return statusCode;   // 舊版留下的中文字串：原樣顯示
        }
    }

    /** 說明代碼＋參數 → 目前語系的句子。 */
    public static String text(Context c, String detailCode, String... args) {
        if (detailCode == null) return "";
        switch (detailCode) {
            case D_NONE:
                return "";
            case D_DISABLED:
                return c.getString(R.string.detail_disabled);
            case D_NEED_WIFI_PAIR:
                return c.getString(R.string.detail_need_wifi_pair);
            case D_NEED_PAIR:
                return c.getString(R.string.detail_need_pair);
            case D_PERM_MISSING:
                return c.getString(R.string.detail_perm_missing, arg(args, 0));
            case D_WAIT_WIFI_FAILED:
                return c.getString(R.string.detail_wait_wifi_failed);
            case D_REOPEN_DEBUG:
                return c.getString(R.string.detail_reopen_debug);
            case D_DEBUG_OPENED:
                return c.getString(R.string.detail_debug_opened, arg(args, 0));
            case D_FIND_PORT:
                return c.getString(R.string.detail_find_port);
            case D_PORT_NOT_FOUND:
                return c.getString(R.string.detail_port_not_found);
            case D_SWITCH_PORT:
                return c.getString(R.string.detail_switch_port, num(args, 0));
            case D_SWITCH_FAILED:
                return c.getString(R.string.detail_switch_failed, num(args, 0));
            case D_REPAIR_EXCEPTION:
                return c.getString(R.string.detail_repair_exception, arg(args, 0));
            case D_OK:
                return c.getString(R.string.detail_ok, arg(args, 0));
            case D_OK_NO_PERM:
                return c.getString(R.string.detail_ok_no_perm, arg(args, 0), arg(args, 1));
            case D_GRANT_SELF:
                return c.getString(R.string.detail_grant_self);
            case D_PAIR_TRUST:
                return c.getString(R.string.detail_pair_trust);
            case D_PAIR_TRUST_PORT:
                return c.getString(R.string.detail_pair_trust_port, num(args, 0));
            case D_PAIR_FAILED:
                return c.getString(R.string.detail_pair_failed);
            case D_PAIR_OK:
                return c.getString(R.string.detail_pair_ok);
            case D_PAIR_NO_PORT:
                return c.getString(R.string.pair_err_no_port);
            case D_PAIR_WRONG_CODE:
                return c.getString(R.string.pair_err_wrong_code);
            case D_PAIR_SERVER_CLOSED:
                return c.getString(R.string.pair_err_server_closed);
            default:
                return detailCode;   // 未知碼：原樣顯示，不要吞掉
        }
    }

    /** 從 SharedPreferences 讀出來的說明（參數是序列化過的字串）。 */
    public static String fromStored(Context c, String detailCode, String rawArgs) {
        return text(c, detailCode, splitArgs(rawArgs));
    }

    /** 把參數序列化成可以存進 SharedPreferences 的字串。 */
    public static String join(String... args) {
        if (args == null || args.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(ARG_SEP);
            if (args[i] != null) sb.append(args[i]);
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 內部

    private static String[] splitArgs(String raw) {
        if (raw == null || raw.isEmpty()) return new String[0];
        return raw.split(String.valueOf(ARG_SEP), -1);
    }

    private static String arg(String[] args, int i) {
        return (args != null && args.length > i && args[i] != null) ? args[i] : "";
    }

    private static int num(String[] args, int i) {
        try {
            return Integer.parseInt(arg(args, i).trim());
        } catch (Exception e) {
            return 0;
        }
    }
}
