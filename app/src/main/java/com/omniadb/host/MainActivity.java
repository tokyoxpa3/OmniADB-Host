package com.omniadb.host;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 設定與狀態畫面。
 *
 * <p>所有給使用者看的文字都走 {@code strings.xml}：預設（{@code values/}）是英文，
 * {@code values-zh} 是繁體、{@code values-b+zh+Hans} 是簡體。
 * 系統語系是中文就自動顯示中文，其他一律英文 —— 由 Android 自己挑資源，
 * App 內沒有任何語言切換開關，也不需要重啟。
 */
public class MainActivity extends Activity {

    private static final int REQ_NOTIFICATIONS = 41;
    /** 從「開始配對」按鈕發起的通知權限請求（核准後會自動接著開始配對）。 */
    private static final int REQ_NOTIFICATIONS_PAIRING = 42;
    private static final String TAG = "OmniADB";

    private TextView statusView;
    private TextView detailView;
    private TextView infoView;
    private TextView notifWarn;
    private TextView autoReadStatus;
    private Button enableButton;

    // ---- 引導卡片：首屏只講「現在該做什麼」，其餘收起來 ----
    private LinearLayout guideCard;
    private TextView guideTitle;
    private TextView guideDesc;
    private Button guideButton;

    // ---- 配對區（按狀態顯示/收起）----
    private TextView pairTitle;
    private TextView pairHelp;
    private Button startPairButton;
    private LinearLayout manualSection;
    private Button autoReadButton;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refresh();
            ui.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "語系：" + getResources().getConfiguration().getLocales().toLanguageTags()
                + "（中文顯示與否看這行，排查翻譯問題時很有用）");
        setContentView(buildUi());
        requestNotificationPermissionIfNeeded();
        ensureServiceRunning();
        // 服務綁定是非同步的，等一下子再判斷「是不是真的沒開」
        ui.postDelayed(this::autoEnableA11yIfPossible, 1_200L);
    }

    /**
     * 有 {@code WRITE_SECURE_SETTINGS} 就順手把「自動讀取配對碼」開好。
     *
     * <p>為什麼不問使用者：拿到這個權限的唯一途徑就是**使用者主動跑了
     * {@code omniadb setup}（或插 USB 手動 {@code pm grant}）**，那本身就是一次明確授權。
     * 這時再問「要不要開自動讀碼」只是多一次點擊 —— 而它本來就是這個 App 該有的能力。
     *
     * <p>涵蓋三種情況：
     * <ul>
     *   <li>走 {@code setup} 安裝（有權限、沒開無障礙）→ 開 App 就自動開好</li>
     *   <li>sideload 配對成功（{@code ensurePermission} 剛拿到權限）→ 由服務端開，
     *       這裡是補一刀保險</li>
     *   <li>已配對、權限一直在，但無障礙被系統解綁（覆蓋安裝、force-stop）→ 自動重綁</li>
     * </ul>
     *
     * <p>每次 {@code onCreate} 只試一次，不輪詢：失敗（例如被 restricted settings 擋）
     * 就留給引導卡片上的按鈕處理，不在背景反覆騷擾系統。
     */
    private void autoEnableA11yIfPossible() {
        if (isFinishing() || isDestroyed()) return;
        if (!hasWriteSecureSettings()) return;
        if (PairingCodeReader.isBound(this)) return;
        if (PairingCodeReader.enableSilently(this)) {
            Log.i(TAG, "已自動開啟自動讀取配對碼（有 WRITE_SECURE_SETTINGS）");
            ui.postDelayed(this::refresh, 1_200L);
        } else {
            Log.w(TAG, "自動開啟自動讀取配對碼失敗，留給引導卡片處理");
        }
    }

    /**
     * 開啟 App 就確保常駐服務在跑。
     *
     * ★ 為什麼需要這一段：全新安裝後**沒有任何東西會啟動服務**。
     *   · BootReceiver 只聽 BOOT_COMPLETED / LOCKED_BOOT_COMPLETED /
     *     MY_PACKAGE_REPLACED —— 全新安裝不會觸發任何一個（MY_PACKAGE_REPLACED
     *     只在「覆蓋安裝」時才送）。
     *   · 服務是 android:exported="false"，電腦端也沒辦法幫它啟動。
     *     實測 `adb shell am start-foreground-service -n com.omniadb.host/.AdbKeepAliveService`
     *     會回：`Error: Requires permission not exported from uid 10289`。
     *
     *   結果就是「裝好了、權限也給了，但服務根本沒在跑」，使用者得自己
     *   進 App 按一下。加上這一行之後，電腦端只要
     *   `am start -n com.omniadb.host/.MainActivity` 就能把整條鏈路拉起來，
     *   首次設定真正做到一鍵。重複呼叫是安全的（start() 本身冪等，
     *   服務內也有 repairInProgress 互斥）。
     */
    private void ensureServiceRunning() {
        if (Store.prefs(this).getBoolean(Store.KEY_ENABLED, true)) {
            AdbKeepAliveService.start(this, false);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(refresher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(refresher);
    }

    // ------------------------------------------------------------------ 畫面

    private View buildUi() {
        int pad = dp(20);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(R.string.title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        // ---- 引導卡片：依「權限／配對」狀態只顯示當前該做的那一步 ----
        guideCard = new LinearLayout(this);
        guideCard.setOrientation(LinearLayout.VERTICAL);
        guideCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.topMargin = dp(14);
        guideCard.setLayoutParams(glp);
        guideCard.setBackgroundColor(Color.parseColor("#EEF4FF"));

        guideTitle = new TextView(this);
        guideTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        guideTitle.setTypeface(Typeface.DEFAULT_BOLD);
        guideCard.addView(guideTitle);

        guideDesc = new TextView(this);
        guideDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        guideDesc.setPadding(0, dp(6), 0, 0);
        guideCard.addView(guideDesc);

        guideButton = new Button(this);
        guideButton.setAllCaps(false);
        LinearLayout.LayoutParams gblp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gblp.topMargin = dp(10);
        guideButton.setLayoutParams(gblp);
        guideCard.addView(guideButton);

        root.addView(guideCard);

        // 通知權限的警示條。只在沒權限時出現 —— 因為沒有通知就沒有配對碼輸入框，
        // 而「按了開始配對卻什麼都沒發生」是這個 App 最難自己查的症狀。
        notifWarn = new TextView(this);
        notifWarn.setText(R.string.notif_warn);
        notifWarn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        notifWarn.setTextColor(Color.parseColor("#B3261E"));
        notifWarn.setBackgroundColor(Color.parseColor("#FCEBEB"));
        notifWarn.setPadding(dp(10), dp(10), dp(10), dp(10));
        notifWarn.setOnClickListener(v -> openAppNotificationSettings());
        notifWarn.setVisibility(hasNotificationPermission() ? View.GONE : View.VISIBLE);
        root.addView(notifWarn);

        statusView = new TextView(this);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        statusView.setTypeface(Typeface.DEFAULT_BOLD);
        statusView.setPadding(0, dp(12), 0, 0);
        root.addView(statusView);

        detailView = new TextView(this);
        detailView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        detailView.setPadding(0, dp(4), 0, dp(8));
        root.addView(detailView);

        infoView = new TextView(this);
        infoView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        infoView.setPadding(0, 0, 0, dp(16));
        root.addView(infoView);

        enableButton = new Button(this);
        enableButton.setOnClickListener(v -> toggleEnabled());
        root.addView(enableButton);

        root.addView(button(getString(R.string.btn_check_repair), v -> {
            AdbKeepAliveService.start(MainActivity.this, false);
            Toast.makeText(this, R.string.toast_check_scheduled, Toast.LENGTH_SHORT).show();
        }));

        root.addView(button(getString(R.string.btn_copy_connect), v -> copyConnectCommand()));

        root.addView(button(getString(R.string.btn_battery_whitelist),
                v -> requestIgnoreBatteryOptimizations()));

        root.addView(button(getString(R.string.btn_wireless_settings),
                v -> openWirelessDebuggingSettings()));

        // ---- 一次性配對（按狀態決定顯示內容）----
        pairTitle = new TextView(this);
        pairTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        pairTitle.setTypeface(Typeface.DEFAULT_BOLD);
        pairTitle.setPadding(0, dp(20), 0, dp(4));
        root.addView(pairTitle);

        pairHelp = new TextView(this);
        pairHelp.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        pairHelp.setPadding(0, 0, 0, dp(8));
        root.addView(pairHelp);

        startPairButton = button(getString(R.string.btn_start_pair_notif),
                v -> startPairingViaNotification());
        root.addView(startPairButton);

        // ---- 自動讀取配對碼（有 WRITE_SECURE_SETTINGS 才有意義，否則整段收起）----
        autoReadStatus = new TextView(this);
        autoReadStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        autoReadStatus.setPadding(0, dp(16), 0, dp(2));
        root.addView(autoReadStatus);

        autoReadButton = button(getString(R.string.btn_auto_read), v -> enableAutoRead());
        root.addView(autoReadButton);

        // ---- 備用：手動輸入（已配對時收起，正常流程用不到）----
        manualSection = new LinearLayout(this);
        manualSection.setOrientation(LinearLayout.VERTICAL);
        root.addView(manualSection);

        TextView manualTitle = new TextView(this);
        manualTitle.setText(R.string.manual_title);
        manualTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        manualTitle.setTypeface(Typeface.DEFAULT_BOLD);
        manualTitle.setPadding(0, dp(18), 0, dp(4));
        manualSection.addView(manualTitle);

        final EditText codeInput = new EditText(this);
        codeInput.setHint(R.string.hint_code);
        codeInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        manualSection.addView(codeInput);

        final EditText portInput = new EditText(this);
        portInput.setHint(R.string.hint_port);
        portInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        manualSection.addView(portInput);

        manualSection.addView(button(getString(R.string.btn_manual_pair), v -> {
            String code = codeInput.getText().toString().trim();
            String portText = portInput.getText().toString().trim();
            if (code.isEmpty() || portText.isEmpty()) {
                Toast.makeText(this, R.string.toast_need_code_port, Toast.LENGTH_SHORT).show();
                return;
            }
            doPair(code, Integer.parseInt(portText));
        }));

        TextView footer = new TextView(this);
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        footer.setPadding(0, dp(24), 0, dp(8));
        footer.setText(R.string.footer);
        root.addView(footer);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private Button button(String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------ 狀態

    private void refresh() {
        // 存的是代碼（見 Msg），顯示時才翻成目前語系 —— 切換語言不必重啟服務
        String statusCode = Store.prefs(this).getString(Store.KEY_STATUS, Msg.ST_NOT_STARTED);
        String detailCode = Store.prefs(this).getString(Store.KEY_DETAIL_CODE, Msg.D_NONE);
        String detailArgs = Store.prefs(this).getString(Store.KEY_DETAIL_ARGS, "");
        boolean enabled = Store.prefs(this).getBoolean(Store.KEY_ENABLED, true);
        boolean paired = Store.prefs(this).getBoolean(Store.KEY_PAIRED, false);

        statusView.setText(Msg.label(this, statusCode));
        if (Msg.ST_OK.equals(statusCode)) {
            statusView.setTextColor(Color.parseColor("#1B7F3B"));
        } else if (Msg.ST_ATTENTION.equals(statusCode)) {
            statusView.setTextColor(Color.parseColor("#B3261E"));
        } else {
            statusView.setTextColor(Color.parseColor("#8A6D00"));
        }
        detailView.setText(Msg.fromStored(this, detailCode, detailArgs));

        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.info_paired, getString(paired
                ? R.string.info_paired_yes : R.string.info_paired_no))).append('\n');
        sb.append(getString(R.string.info_ip, Net.deviceIp(this))).append('\n');
        sb.append(getString(R.string.info_port, Store.targetPort(this))).append('\n');
        sb.append(getString(R.string.info_repair_count,
                Store.prefs(this).getInt(Store.KEY_REPAIR_COUNT, 0))).append('\n');
        sb.append(getString(R.string.info_last_ok,
                fmt(Store.prefs(this).getLong(Store.KEY_LAST_OK_TS, 0)))).append('\n');
        sb.append(getString(R.string.info_last_repair,
                fmt(Store.prefs(this).getLong(Store.KEY_LAST_REPAIR_TS, 0))));
        infoView.setText(sb.toString());

        enableButton.setText(enabled ? R.string.btn_disable_keepalive : R.string.btn_enable_keepalive);

        if (notifWarn != null) {
            notifWarn.setVisibility(hasNotificationPermission() ? View.GONE : View.VISIBLE);
        }

        updateGuide(paired);
    }

    /**
     * 引導卡片：依「有沒有 WRITE_SECURE_SETTINGS」「配對了沒」只顯示當前該做的那一步。
     *
     * <p>這四種狀態就是使用者實際會遇到的全部情況：
     * <ol>
     *   <li>已配對 + 有權限 → 什麼都不用做</li>
     *   <li>已配對 + 沒權限 → 權限遺失（多半是解除安裝後重裝）</li>
     *   <li>未配對 + 有權限 → 只差配對</li>
     *   <li>未配對 + 沒權限 → 首次設定，配對一次就會自動取得權限</li>
     * </ol>
     *
     * <p>沒權限時「自動讀取配對碼設定」整段收起：那時開不了（無障礙要手動去設定頁），
     * 而且配對成功後 App 會自己補上權限與無障礙，等那時再出現才有意義。
     */
    private void updateGuide(boolean paired) {
        if (guideCard == null) return;
        boolean perm = hasWriteSecureSettings();
        boolean a11y = isServiceBound();
        String endpoint = Net.deviceIp(this) + ":" + Store.targetPort(this);

        if (paired && perm) {
            guideTitle.setText(R.string.guide_title_ok);
            guideDesc.setText(getString(R.string.guide_desc_ok, endpoint,
                    a11y ? getString(R.string.guide_desc_ok_a11y_extra) : ""));
            guideCard.setBackgroundColor(Color.parseColor("#E8F5E9"));
            if (a11y) {
                guideButton.setVisibility(View.GONE);
            } else {
                guideButton.setVisibility(View.VISIBLE);
                guideButton.setText(R.string.guide_btn_autoread);
                guideButton.setOnClickListener(v -> enableAutoRead());
            }
        } else if (paired) {
            guideTitle.setText(R.string.guide_title_no_perm);
            guideDesc.setText(R.string.guide_desc_no_perm);
            guideCard.setBackgroundColor(Color.parseColor("#FCEBEB"));
            guideButton.setVisibility(View.VISIBLE);
            guideButton.setText(R.string.guide_btn_repair_grant);
            guideButton.setOnClickListener(v -> {
                AdbKeepAliveService.start(MainActivity.this, false);
                Toast.makeText(this, R.string.toast_repair_grant, Toast.LENGTH_LONG).show();
            });
        } else if (perm) {
            guideTitle.setText(R.string.guide_title_ready);
            guideDesc.setText(getString(R.string.guide_desc_ready, getString(a11y
                    ? R.string.guide_desc_ready_a11y : R.string.guide_desc_ready_manual)));
            guideCard.setBackgroundColor(Color.parseColor("#E8F0FE"));
            guideButton.setVisibility(View.VISIBLE);
            guideButton.setText(a11y
                    ? R.string.btn_start_pair_a11y : R.string.btn_start_pair_notif);
            guideButton.setOnClickListener(v -> startPairingViaNotification());
        } else {
            guideTitle.setText(R.string.guide_title_first_run);
            guideDesc.setText(R.string.guide_desc_first_run);
            guideCard.setBackgroundColor(Color.parseColor("#FFF8E1"));
            guideButton.setVisibility(View.VISIBLE);
            guideButton.setText(R.string.btn_start_pair_notif);
            guideButton.setOnClickListener(v -> startPairingViaNotification());
        }

        // ---- 自動讀碼區：沒有權限就整段收起 ----
        int autoVis = perm ? View.VISIBLE : View.GONE;
        if (autoReadStatus != null) {
            autoReadStatus.setVisibility(autoVis);
            if (perm) {
                boolean written = PairingCodeReader.isEnabled(this);
                if (a11y) {
                    autoReadStatus.setText(R.string.a11y_status_on);
                    autoReadStatus.setTextColor(Color.parseColor("#1B7F3B"));
                } else if (written) {
                    autoReadStatus.setText(R.string.a11y_status_written);
                    autoReadStatus.setTextColor(Color.parseColor("#B33A00"));
                } else {
                    autoReadStatus.setText(R.string.a11y_status_off);
                    autoReadStatus.setTextColor(Color.parseColor("#8A6D00"));
                }
            }
        }
        if (autoReadButton != null) autoReadButton.setVisibility(autoVis);

        // ---- 配對區文案：已配對時降級成「重新配對」 ----
        if (pairTitle != null) {
            pairTitle.setText(paired ? R.string.pair_title_repair : R.string.pair_title_first);
        }
        if (pairHelp != null) {
            pairHelp.setText(a11y ? R.string.pair_help_a11y : R.string.pair_help_manual);
        }
        if (startPairButton != null) {
            startPairButton.setText(a11y
                    ? R.string.btn_start_pair_a11y : R.string.btn_start_pair_notif);
        }
        // 手動輸入區只在還沒配對時才有意義
        if (manualSection != null) {
            manualSection.setVisibility(paired ? View.GONE : View.VISIBLE);
        }
    }

    private boolean hasWriteSecureSettings() {
        return checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private String fmt(long ts) {
        if (ts <= 0) return "—";
        // 用系統語系的日期格式，不要寫死台灣
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(ts));
    }

    private void toggleEnabled() {
        boolean enabled = Store.prefs(this).getBoolean(Store.KEY_ENABLED, true);
        Store.prefs(this).edit().putBoolean(Store.KEY_ENABLED, !enabled).apply();
        if (!enabled) {
            AdbKeepAliveService.start(this, false);
        } else {
            Store.setStatus(this, Msg.ST_DISABLED, Msg.D_DISABLED);
        }
        refresh();
    }

    private void copyConnectCommand() {
        String cmd = "adb connect " + Net.deviceIp(this) + ":" + Store.targetPort(this);
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("adb", cmd));
            Toast.makeText(this, getString(R.string.toast_copied, cmd), Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ 配對

    /**
     * 推薦流程：起一個前景服務在通知欄放輸入框，並自動用 mDNS 找配對埠。
     * 使用者只要去系統的「使用配對碼配對裝置」看到 6 位數，下拉通知欄輸入即可。
     *
     * ★ 進場先檢查通知權限。Android 13+ 沒給 POST_NOTIFICATIONS 時，整個 App 的
     *   通知會被系統丟掉（AppOps `POST_NOTIFICATION: ignore`、importance=NONE），
     *   連前景服務的通知都不會出現。那時起 PairingService 等於**靜默失敗** ——
     *   使用者只會覺得「按了開始配對，什麼都沒發生」。
     */
    private void startPairingViaNotification() {
        if (!hasNotificationPermission()) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIFICATIONS_PAIRING);
            return;
        }
        launchPairing();
    }

    private void launchPairing() {
        PairingService.start(this);
        Toast.makeText(this, R.string.toast_pairing_started, Toast.LENGTH_LONG).show();
        // 直接帶使用者到開發者選項，少走幾步
        ui.postDelayed(this::openWirelessDebuggingSettings, 800);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_NOTIFICATIONS && requestCode != REQ_NOTIFICATIONS_PAIRING) {
            return;
        }

        if (hasNotificationPermission()) {
            refresh();
            if (requestCode == REQ_NOTIFICATIONS_PAIRING) launchPairing();
            return;
        }

        // 被拒絕，或之前已永久拒絕（系統不會再彈，grantResults 會是空的）。
        // 這時只有「設定頁」能救，直接帶過去，不要讓使用者自己找。
        Toast.makeText(this, R.string.toast_no_notif_perm, Toast.LENGTH_LONG).show();
        openAppNotificationSettings();
    }

    /** 備用流程：使用者自己抄下配對碼與配對埠。 */
    private void doPair(String code, int port) {
        Toast.makeText(this, R.string.toast_pairing_now, Toast.LENGTH_SHORT).show();
        Store.setStatus(this, Msg.ST_PAIRING, Msg.D_PAIR_TRUST);

        new Thread(() -> {
            AdbClient adb = new AdbClient(MainActivity.this);
            String ip = Net.deviceIp(MainActivity.this);
            boolean ok = adb.pair(ip, port, code);
            if (!ok) {
                ok = adb.pair("127.0.0.1", port, code);
            }

            if (!ok) {
                Store.setStatus(MainActivity.this, Msg.ST_ATTENTION, Msg.D_PAIR_FAILED);
                return;
            }

            Store.prefs(MainActivity.this).edit()
                    .putBoolean(Store.KEY_PAIRED, true)
                    .putBoolean(Store.KEY_ENABLED, true)
                    .apply();
            Store.setStatus(MainActivity.this, Msg.ST_PAIRED, Msg.D_PAIR_OK);
            AdbKeepAliveService.start(MainActivity.this, false);
        }, "adb-pair").start();
    }

    // ------------------------------------------------------------------ 權限 / 系統設定

    private void requestNotificationPermissionIfNeeded() {
        if (!hasNotificationPermission()) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIFICATIONS);
        }
    }

    /** Android 13 以下沒有這個權限，一律視為已授權。 */
    private boolean hasNotificationPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 跳到本 App 的通知設定頁（被永久拒絕時唯一的自救路徑）。 */
    private void openAppNotificationSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e2) {
                Toast.makeText(this, R.string.toast_notif_settings_manual, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void requestIgnoreBatteryOptimizations() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e2) {
                Toast.makeText(this, R.string.toast_battery_manual, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void openWirelessDebuggingSettings() {
        try {
            startActivity(new Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS"));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
            } catch (Exception e2) {
                Toast.makeText(this, R.string.toast_wireless_manual, Toast.LENGTH_LONG).show();
            }
        }
    }

    /**
     * 「自動讀取配對碼設定」按鈕。
     *
     * 首選：直接寫 Settings.Secure 開啟本服務（需要 WRITE_SECURE_SETTINGS）。
     * 這條路不碰任何 ROM 的 Activity，所以 Pixel／三星／小米／OPPO 都一樣。
     *
     * 退路：沒有 WRITE_SECURE_SETTINGS，或寫進去卻沒被系統綁定
     *      （Android 13+ 對側載 App 的「受限制的設定」會擋），才帶使用者去設定頁。
     */
    private void enableAutoRead() {
        Log.i(TAG, "按下「自動讀取配對碼設定」：written=" + PairingCodeReader.isEnabled(this)
                + " bound=" + isServiceBound());
        if (PairingCodeReader.isEnabled(this) && isServiceBound()) {
            Toast.makeText(this, R.string.toast_a11y_on, Toast.LENGTH_SHORT).show();
            openAccessibilitySettings();
            return;
        }
        if (!enableAccessibilitySilently()) {
            Toast.makeText(this, R.string.toast_no_perm_manual, Toast.LENGTH_LONG).show();
            openAccessibilitySettings();
            return;
        }
        Toast.makeText(this, R.string.toast_a11y_sent, Toast.LENGTH_SHORT).show();
        // 系統綁定是非同步的，等一會兒再確認真的生效
        ui.postDelayed(() -> {
            boolean bound = isServiceBound();
            refresh();
            if (bound) {
                Toast.makeText(this, R.string.toast_a11y_on, Toast.LENGTH_LONG).show();
            } else {
                // 設定值寫進去了但系統沒綁 → 幾乎一定是 restricted settings
                Toast.makeText(this, R.string.toast_a11y_written, Toast.LENGTH_LONG).show();
                openAccessibilitySettings();
            }
        }, 1_500L);
    }

    /**
     * 委派給 {@link PairingCodeReader#enableSilently}：直接寫 Settings.Secure 開啟服務，
     * 不跳任何 ROM 的設定頁（深鏈入口全被簽名權限鎖死，沒有通用做法）。
     */
    private boolean enableAccessibilitySilently() {
        if (checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "沒有 WRITE_SECURE_SETTINGS，無法自助開啟無障礙");
            return false;
        }
        return PairingCodeReader.enableSilently(this);
    }

    /** 服務是否真的被系統綁定（設定值寫進去但這裡 false = 被 restricted settings 擋）。 */
    private boolean isServiceBound() {
        return PairingCodeReader.isBound(this);
    }

    /**
     * 帶使用者去無障礙設定開服務（退路）。
     *
     * ★ Android 13+ 對「非商店安裝」的 App 有 restricted settings 限制，
     *   若開關是灰的，要先去 應用程式資訊 → 右上角 ⋮ → 允許受限制的設定。
     *   這句提示直接寫進 Toast，省掉使用者卡在灰色開關前查半天。
     */
    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception e) {
            Toast.makeText(this, R.string.toast_a11y_manual, Toast.LENGTH_LONG).show();
        }
    }
}
