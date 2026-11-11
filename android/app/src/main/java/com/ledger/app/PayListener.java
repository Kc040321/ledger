package com.ledger.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import org.json.JSONObject;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 读取系统通知，识别付款/收款。
 * 只看像交易的通知（有交易关键词）；其他通知读完就丢，不保存、不上传。
 */
public class PayListener extends NotificationListenerService {
    static final String CHANNEL = "auto_record";
    static final String ACTION_OPEN = "com.ledger.app.OPEN_PENDING";
    private static final Pattern TX_HINT = Pattern.compile("(消费|支出|支付|付款|扣款|转出|收入|入账|到账|转入|存入|收款|退款|交易|转账|¥|￥|元)");

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            if (sbn == null || getPackageName().equals(sbn.getPackageName())) return;
            Notification n = sbn.getNotification();
            if (n == null || n.extras == null) return;
            if ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0 || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
            Bundle e = n.extras;
            String title = str(e.getCharSequence(Notification.EXTRA_TITLE));
            CharSequence big = e.getCharSequence(Notification.EXTRA_BIG_TEXT);
            String text = str(big != null ? big : e.getCharSequence(Notification.EXTRA_TEXT));
            if (title.isEmpty() && text.isEmpty()) return;
            String all = title + " " + text;
            if (!TX_HINT.matcher(all).find()) return; // 和钱无关，直接忽略

            String pkg = sbn.getPackageName();
            PayParser.Result r = PayParser.parse(pkg, title, text);
            long time = sbn.getPostTime();
            PendingStore.log(this, pkg, title, text, r, time);
            if (r == null) return;
            JSONObject added = PendingStore.add(this, r, pkg, all.trim(), time);
            if (added != null) notifyUser(added, r);
            MainActivity.refreshPending();
        } catch (Throwable ignored) {
            // 识别失败绝不能影响系统通知
        }
    }

    private void notifyUser(JSONObject item, PayParser.Result r) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("付款后提醒你确认记账");
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class).setAction(ACTION_OPEN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String yuan = String.format(Locale.ROOT, "%d.%02d", r.amount / 100, r.amount % 100);
        String kind = "inc".equals(r.type) ? "收入" : "refund".equals(r.type) ? "退款" : "支出";
        String title = "记一笔？" + kind + " ¥" + yuan + " · " + r.source;
        String body = (r.merchant.isEmpty() ? "" : r.merchant + " · ") + "点一下打开记账本确认";
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pi);
        nm.notify(item.optString("id").hashCode(), b.build());
    }

    private static String str(CharSequence s) { return s == null ? "" : s.toString().trim(); }
}
