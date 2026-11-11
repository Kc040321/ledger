package com.ledger.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import org.json.JSONObject;

import java.util.Locale;

/** 识别到交易后的系统通知：自动记入的显示「已记」，需要补金额的显示「记一笔？」 */
final class Notifier {
    static final String CHANNEL = "auto_record";
    private Notifier() {}

    static void show(Context c, JSONObject item, PayParser.Result r) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, c.getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(c, MainActivity.class).setAction(PayListener.ACTION_OPEN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        boolean auto = item.optBoolean("auto");
        String yuan = r.amount > 0 ? "¥" + String.format(Locale.ROOT, "%d.%02d", r.amount / 100, r.amount % 100) : "";
        String kind = r.amount == 0 ? "转账" : "inc".equals(r.type) ? "收入" : "refund".equals(r.type) ? "退款" : "支出";
        String title = (auto ? "已记 " : "记一笔？") + kind + " " + yuan;
        String body = (r.merchant.isEmpty() ? r.source : r.merchant + " · " + r.source);
        Notification.Builder b = new Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title.trim())
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pi);
        nm.notify(item.optString("id").hashCode(), b.build());
    }
}
