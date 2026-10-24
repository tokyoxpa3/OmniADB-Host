package com.omniadb.host;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityManager;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 從系統的「使用配對碼配對裝置」對話框自動讀出 6 位數配對碼。
 *
 * <h3>為什麼不是 OCR</h3>
 * 實機 dump 過那個對話框（SM-G9810 / Android 13，{@code package=com.android.settings}）：
 *
 * <pre>
 *   text="與裝置配對" / text="Wi‑Fi 配對碼" / text="477307" / text="10.8.0.2:40524"
 * </pre>
 *
 * 配對碼就是一個普通 {@code TextView} —— 無障礙服務直接讀得到**精確字串**，
 * 不必截圖（MediaProjection 每次要使用者按同意）、不必辨識（0/O、1/l、6/8 是
 * OCR 的典型錯誤）、也不必為了辨識塞一個幾十 MB 的模型進來。
 *
 * <h3>只在使用者要求時才讀</h3>
 * 這個服務平常**完全不看畫面**：只有 {@link PairingService#start(Context)} 被呼叫
 * （也就是使用者按了「開始配對」）之後的 10 分鐘內才會動作，見
 * {@link Store#wantsPairing(Context)}。這樣即使無障礙服務一直開著，
 * 也不會在使用者只是逛設定頁時亂配對。
 *
 * <p>還有一個副作用是好的：配對伺服器只在對話框開著時存活，
 * 程式在碼出現的**那一瞬間**就送出，比人手快好幾秒，成功率反而更高。
 */
public class PairingCodeReader extends AccessibilityService {

    private static final String TAG = "OmniADB";

    /** 配對對話框屬於系統設定。 */
    private static final String SETTINGS_PKG = "com.android.settings";

    private static final Pattern SIX_DIGITS = Pattern.compile("^\\d{6}$");

    /** 同一個碼會連續觸發很多次事件，避免重複送出。 */
    private String lastCode = "";
    private long lastCodeAt = 0L;

    private static final long DEDUPE_MS = 30_000L;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        CharSequence pkg = event.getPackageName();
        if (pkg == null || !SETTINGS_PKG.contentEquals(pkg)) return;

        // 沒有配對請求就完全不看畫面（這一行是隱私邊界，不要拿掉）
        if (!Store.wantsPairing(this)) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        try {
            List<String> texts = new ArrayList<>();
            collect(root, texts, 0);

            if (!looksLikePairingDialog(texts)) return;

            String code = firstSixDigits(texts);
            if (code == null) return;

            long now = System.currentTimeMillis();
            if (code.equals(lastCode) && now - lastCodeAt < DEDUPE_MS) return;
            lastCode = code;
            lastCodeAt = now;

            // ★ 配對碼本身不進日誌、不落盤、不上傳。
            Log.i(TAG, "無障礙讀到配對碼，交給配對服務");
            PairingService.reply(this, code);
        } catch (Exception e) {
            Log.e(TAG, "讀取配對碼失敗", e);
        }
    }

    @Override
    public void onInterrupt() {
    }

    // ------------------------------------------------------------------ 節點處理

    private static void collect(AccessibilityNodeInfo node, List<String> out, int depth) {
        if (node == null || depth > 12 || out.size() > 200) return;
        CharSequence t = node.getText();
        if (!TextUtils.isEmpty(t)) out.add(t.toString());
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            collect(node.getChild(i), out, depth + 1);
        }
    }

    /**
     * 確認這是「配對」對話框，而不是設定裡隨便一頁有 6 位數字的地方。
     * 標題在不同語系/版本可能是「Wi‑Fi 配對碼」或 "Pairing code"。
     */
    private static boolean looksLikePairingDialog(List<String> texts) {
        for (String t : texts) {
            if (t.contains("配對碼")) return true;
            String s = t.toLowerCase();
            if (s.contains("pairing code") || s.contains("pair device")) return true;
        }
        return false;
    }

    /**
     * 取第一個「剛好 6 位純數字」的文字。
     *
     * <p>對話框上還有 {@code "10.8.0.2:40524"} 這種 IP:埠，用 {@code ^\d{6}$}
     * 就不會誤抓 —— 這也是不要用「抓連續數字」這種寬鬆比對的原因。
     */
    private static String firstSixDigits(List<String> texts) {
        for (String t : texts) {
            String s = t.trim();
            if (SIX_DIGITS.matcher(s).matches()) return s;
        }
        return null;
    }

    // ------------------------------------------------------------------ 狀態查詢

    /** 給 UI 用：這個無障礙服務是否已在系統設定裡被開啟。 */
    public static boolean isEnabled(Context ctx) {
        String flat = Settings.Secure.getString(ctx.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (flat == null) return false;
        String me = ctx.getPackageName() + "/" + PairingCodeReader.class.getName();
        for (String s : flat.split(":")) {
            if (me.equalsIgnoreCase(s.trim())) return true;
        }
        return false;
    }

    /**
     * 服務是否**真的被系統綁定**（不是只看設定值）。
     *
     * <p>設定值寫進去但這裡回 false，就是被 Android 13 的 restricted settings 擋掉了。
     * 只看 {@link #isEnabled} 會誤判成「已開啟」。
     *
     * <p>用 {@link AccessibilityServiceInfo#getId()} 而不是
     * {@code getResolveInfo().serviceInfo}：後者在部分 ROM／情況下是 null，
     * 會讓「明明已綁定」被判成未綁定（實測三星 S20 踩過）。
     */
    public static boolean isBound(Context ctx) {
        // 1) 首選：AccessibilityManager 的即時綁定清單。
        try {
            AccessibilityManager am = (AccessibilityManager)
                    ctx.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am != null) {
                String pkg = ctx.getPackageName();
                String cls = PairingCodeReader.class.getName();
                for (AccessibilityServiceInfo info : am.getEnabledAccessibilityServiceList(
                        AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
                    // getId() 是 "package/class"，短名形式（.PairingCodeReader）也能正確展開
                    ComponentName c = ComponentName.unflattenFromString(info.getId());
                    if (c != null
                            && c.getPackageName().equals(pkg)
                            && c.getClassName().equals(cls)) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
            // 落到下面的退化判斷
        }

        // 2) 退路：設定層面已啟用且清單含自己。
        //    AccessibilityManager 在**進程剛啟動**時可能還沒拉到服務清單（實測三星會這樣），
        //    只靠它會把「已綁定」誤判成未綁定，於是引導卡片一直叫使用者去開。
        //    這裡退化成看設定值；restricted settings 的情況本來就寫不進去，
        //    所以不會掩蓋真正的問題。
        try {
            return isEnabled(ctx)
                    && Settings.Secure.getInt(ctx.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 直接寫 {@code Settings.Secure} 開啟本服務，完全不跳設定頁。
     *
     * <h3>為什麼不跳「無障礙 → 已安裝的應用程式」</h3>
     * 那條路沒有通用做法，實測三條全被簽名權限鎖死：
     * <ul>
     *   <li>{@code ACTION_ACCESSIBILITY_DETAILS_SETTINGS} 需要 signature 級的
     *       {@code OPEN_ACCESSIBILITY_DETAILS_SETTINGS}</li>
     *   <li>三星的 {@code InstalledServicesActivity} 需要私有的
     *       {@code OPEN_INSTALLED_SERVICES}</li>
     *   <li>三星的 {@code AccessibilityDetailsSettingsActivity} 不帶 extra 就退回
     *       首頁，帶了也被忽略</li>
     * </ul>
     * 而 {@code WRITE_SECURE_SETTINGS} 本來就允許寫 secure settings，等於繞過整個 UI，
     * 且不依賴任何 ROM。
     *
     * <p><b>必須合併寫入，不能覆蓋</b> —— 覆蓋會把使用者的其他無障礙服務一起關掉。
     *
     * @return true 表示設定已寫入（綁定是非同步的，要用 {@link #isBound} 再確認）
     */
    public static boolean enableSilently(Context ctx) {
        try {
            ComponentName me = new ComponentName(ctx, PairingCodeReader.class);
            String flat = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);

            boolean already = false;
            if (flat != null) {
                for (String s : flat.split(":")) {
                    ComponentName c = ComponentName.unflattenFromString(s.trim());
                    if (c != null
                            && c.getPackageName().equals(me.getPackageName())
                            && c.getClassName().equals(me.getClassName())) {
                        already = true;
                        break;
                    }
                }
            }
            if (!already) {
                String next = (flat == null || flat.trim().isEmpty())
                        ? me.flattenToString()
                        : flat + ":" + me.flattenToString();
                if (!Settings.Secure.putString(ctx.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next)) {
                    Log.w(TAG, "寫入 enabled_accessibility_services 被拒");
                    return false;
                }
                Log.i(TAG, "已自助寫入無障礙服務清單");
            }

            if (Settings.Secure.getInt(ctx.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                if (!Settings.Secure.putInt(ctx.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_ENABLED, 1)) {
                    Log.w(TAG, "寫入 accessibility_enabled 被拒");
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "自助開啟無障礙失敗", e);
            return false;
        }
    }
}
