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
    private static final Pattern TX_HINT = Pattern.compile("(消费|支出|支付|付款|扣款|转出|收入|入账|到账|转入|存入|收款|退款|交易|转账|红包|¥|￥|元)");

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
            String pkg = sbn.getPackageName();
            // 支付宝可能隐藏通知内容，它的通知都先记进排查日志；其他 App 和钱无关的直接忽略
            if (!TX_HINT.matcher(all).find() && !PayParser.PKG_ALIPAY.equals(pkg)) return;

            PayParser.Result r = PayParser.parse(pkg, title, text);
            long time = sbn.getPostTime();
            PendingStore.log(this, pkg, title, text, r, time);
            if (r == null) return;
            JSONObject added = PendingStore.add(this, r, pkg, all.trim(), time);
            if (added != null) Notifier.show(this, added, r);
            MainActivity.refreshPending();
        } catch (Throwable ignored) {
            // 识别失败绝不能影响系统通知
        }
    }

    private static String str(CharSequence s) { return s == null ? "" : s.toString().trim(); }
}
