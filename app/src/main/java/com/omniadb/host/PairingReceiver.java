package com.omniadb.host;

import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * 接收通知欄輸入框（{@link RemoteInput}）送出的配對碼，轉交給 {@link PairingService}。
 *
 * <p>刻意放在 manifest 註冊（非 exported），確保 App 程序即使被系統回收、
 * 通知還在時，使用者按下送出仍然收得到。
 */
public class PairingReceiver extends BroadcastReceiver {

    private static final String TAG = "OmniADB";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !PairingService.ACTION_REPLY.equals(intent.getAction())) {
            return;
        }
        Bundle results = RemoteInput.getResultsFromIntent(intent);
        CharSequence code = results == null
                ? null : results.getCharSequence(PairingService.KEY_CODE);
        if (code == null || code.toString().trim().isEmpty()) {
            Log.w(TAG, "通知回覆裡沒有配對碼");
            return;
        }
        Log.i(TAG, "收到通知欄輸入的配對碼");
        PairingService.reply(context, code.toString().trim());
    }
}
