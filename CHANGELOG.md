# Changelog

## 1.1.0 — 2026-10-24

**Localization / 多語系**

- Chinese (Traditional + Simplified) and English UI, selected automatically from the
  system language. No in-app switch.
  / 中英雙語介面（含繁簡），依系統語系自動切換，App 內沒有切換開關。
- Status text is now stored as locale-independent codes (`ok`, `d_switch_port`, …) and
  translated at render time, so changing the system language re-labels the live state
  without restarting the service. Status colours key off the code, not the string.
  / 狀態改存語系無關的代碼，顯示時才翻譯；切換語言不必重啟服務，顏色判斷也不會因翻譯而錯。
- Notification channel names, notification bodies, the pairing input label and the
  accessibility description are localized too.
  / 通知頻道名稱、通知內容、配對碼輸入框標籤、無障礙說明文字一併在地化。
- Resource folders use explicit script qualifiers (`values-b+zh+Hant`,
  `values-b+zh+Hans`). A bare `values-zh` is treated as Simplified by the platform and
  sends Traditional Chinese devices back to English.
  / 資源目錄改用明確的 script 限定符；裸 `values-zh` 會被平台當成簡體，繁中機會回退成英文。
- Date/time in the status block follows the system locale instead of being pinned to
  `Locale.TAIWAN`.
  / 狀態區的時間格式改跟系統語系，不再寫死台灣。

## 1.0.1 — 2026-10-23

- Fix: the launcher icon kept showing a "1" badge. The ongoing notification moved to a
  new channel (`adb_always_on_v2`) created with `setShowBadge(false)`; the old channel is
  deleted on start.
  / 修掉桌面圖示一直掛著「1」角標：常駐通知改用新頻道並關閉角標，舊頻道啟動時刪除。

## 1.0.0 — 2026-10-13

- First working build: boot / Wi-Fi / sleep recovery, 45 s watchdog, port pinning,
  self-granting of `WRITE_SECURE_SETTINGS`, notification-shade pairing with mDNS port
  discovery, and the accessibility-based pairing-code reader.
  / 首個可用版本：開機／網路／休眠自恢復、45 秒看門狗、釘埠、自助授權、通知欄配對 + mDNS 找埠、
  無障礙讀取配對碼。
