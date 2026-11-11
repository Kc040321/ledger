package com.ledger.app;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 读取支付宝、微信、云闪付和银行 App 的付款结果页 / 账单详情页，自动记账。
 * 只处理下面这些 App；页面文字读完就丢，只留下金额、商户、时间。
 */
public class ScreenReader extends AccessibilityService {
    static final Set<String> PKGS = new HashSet<>(Arrays.asList(
        PayParser.PKG_ALIPAY, PayParser.PKG_WECHAT, PayParser.PKG_UNIONPAY,
        "cmb.pb", "com.cmbchina.ccd.pluto.cmbActivity", "com.icbc", "com.chinamworld.main", "com.android.bankabc",
        "com.chinamworld.bocmbci", "com.bankcomm.Bankcomm", "com.ecitic.bank.mobile", "cn.com.spdb.mobilebank.per",
        "com.cib.cibmb", "com.pingan.paces.ccms", "com.mybank.android.phone", "com.webank.wemoney", "com.yitong.mbank.psbc",
        "com.cebbank.mobile.cemb", "com.cmbc.cc.mbank", "com.hxb.mobile.client"));

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable scan = this::scan;
    private String lastSig = "";
    private long lastAt = 0;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getPackageName() == null || !PKGS.contains(e.getPackageName().toString())) return;
        h.removeCallbacks(scan);
        h.postDelayed(scan, 450);
    }

    private void scan() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null || root.getPackageName() == null) return;
            String pkg = root.getPackageName().toString();
            if (!PKGS.contains(pkg)) return;
            List<String> lines = new ArrayList<>();
            collect(root, lines, 0);
            if (lines.isEmpty()) return;
            String sig = pkg + String.join("|", lines);
            long now = System.currentTimeMillis();
            if (sig.equals(lastSig) && now - lastAt < 60_000L) return;
            lastSig = sig; lastAt = now;
            if (!ScreenParser.looksRelevant(lines)) return;
            PayParser.Result r = ScreenParser.parse(pkg, lines, now);
            String text = String.join(" / ", lines);
            if (text.length() > 800) text = text.substring(0, 800);
            PendingStore.log(this, pkg, "[屏幕]", text, r, now);
            if (r == null) return;
            JSONObject added = PendingStore.add(this, r, pkg, text, r.time);
            if (added != null) Notifier.show(this, added, r);
            MainActivity.refreshPending();
        } catch (Throwable ignored) {
            // 读屏失败不能影响别的 App
        }
    }

    private static void collect(AccessibilityNodeInfo n, List<String> out, int depth) {
        if (n == null || depth > 40 || out.size() > 300) return;
        CharSequence t = n.getText();
        if (t == null || t.length() == 0) t = n.getContentDescription();
        if (t != null) {
            String s = t.toString().trim();
            if (!s.isEmpty() && s.length() <= 120 && (out.isEmpty() || !out.get(out.size() - 1).equals(s))) out.add(s);
        }
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), out, depth + 1);
    }

    @Override public void onInterrupt() { }
}
