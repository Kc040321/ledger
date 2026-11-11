package com.ledger.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 待确认的交易队列 + 最近捕获记录（用于排查识别不准的通知）。
 * 只存在本机的 SharedPreferences 里，记账本确认或忽略后删除。
 */
final class PendingStore {
    private static final String PREFS = "auto_record";
    private static final int MAX_PENDING = 100, MAX_LOG = 40;

    private PendingStore() {}

    private static SharedPreferences sp(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    private static JSONArray read(Context c, String key) {
        try { return new JSONArray(sp(c).getString(key, "[]")); } catch (JSONException e) { return new JSONArray(); }
    }

    static boolean autoSave(Context c) { return sp(c).getBoolean("autoSave", true); }
    static void setAutoSave(Context c, boolean on) { sp(c).edit().putBoolean("autoSave", on).apply(); }

    private static final long WINDOW = 5 * 60 * 1000L, DONE_KEEP = 48 * 3600 * 1000L;

    /** 是不是同一笔：订单号相同；或者金额、方向相同且时间相近（订单号都有但不同则不是） */
    static boolean same(JSONObject o, PayParser.Result r, long time) {
        String a = o.optString("orderNo"), b = r.orderNo == null ? "" : r.orderNo;
        if (!a.isEmpty() && a.equals(b)) return true;
        if (!a.isEmpty() && !b.isEmpty()) return false;
        long oa = o.optLong("amount");
        boolean near = o.optString("type").equals(r.type) && Math.abs(o.optLong("time") - time) <= WINDOW;
        if (!near) return false;
        if (oa == r.amount) return oa > 0 || o.optString("merchant").equals(r.merchant);
        return oa == 0 || r.amount == 0; // 一条没金额、一条有金额
    }

    /**
     * 加入队列。和队列里或刚记过的同一笔合并（比如微信通知 + 银行短信 + 支付成功页面），返回 null。
     * 有金额的支出/收入标记 auto，记账本打开时直接记入。
     */
    static synchronized JSONObject add(Context c, PayParser.Result r, String pkg, String raw, long time) {
        JSONArray arr = read(c, "pending");
        try {
            JSONArray done = read(c, "done");
            for (int i = 0; i < done.length(); i++) {
                JSONObject o = done.getJSONObject(i);
                if (same(o, r, time) && (o.optLong("amount") == r.amount || r.amount == 0)) return null;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (!same(o, r, time)) continue;
                if (o.optLong("amount") == 0 && r.amount > 0) { o.put("amount", r.amount); o.put("auto", !"refund".equals(r.type) && autoSave(c)); }
                String src = o.optString("source");
                if (!src.contains(r.source)) o.put("source", src + " + " + r.source);
                if (o.optString("merchant").isEmpty() && !r.merchant.isEmpty()) o.put("merchant", r.merchant);
                if (o.optString("orderNo").isEmpty() && !r.orderNo.isEmpty()) o.put("orderNo", r.orderNo);
                if (r.account.length() > o.optString("account").length()) o.put("account", r.account);
                o.put("raw", o.optString("raw") + "\n" + raw);
                sp(c).edit().putString("pending", arr.toString()).apply();
                return null;
            }
            JSONObject o = new JSONObject();
            o.put("id", "p" + time + "_" + (int) (Math.random() * 1e6));
            o.put("amount", r.amount);
            o.put("type", r.type);
            o.put("merchant", r.merchant);
            o.put("account", r.account);
            o.put("source", r.source);
            o.put("bank", r.bank);
            o.put("orderNo", r.orderNo);
            o.put("auto", r.amount > 0 && !"refund".equals(r.type) && autoSave(c));
            o.put("pkg", pkg);
            o.put("raw", raw);
            o.put("time", time);
            JSONArray out = new JSONArray();
            out.put(o);
            for (int i = 0; i < arr.length() && out.length() < MAX_PENDING; i++) out.put(arr.get(i));
            sp(c).edit().putString("pending", out.toString()).apply();
            return o;
        } catch (JSONException e) {
            return null;
        }
    }

    static synchronized String list(Context c) { return read(c, "pending").toString(); }

    /** 记入或忽略后移出队列，并在「已处理」里留 48 小时，防止同一笔又被读到 */
    static synchronized void remove(Context c, String id) {
        JSONArray arr = read(c, "pending"), out = new JSONArray(), done = read(c, "done"), keep = new JSONArray();
        long now = System.currentTimeMillis();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            if (id.equals(o.optString("id"))) {
                try {
                    JSONObject d = new JSONObject();
                    d.put("amount", o.optLong("amount")); d.put("type", o.optString("type")); d.put("time", o.optLong("time"));
                    d.put("orderNo", o.optString("orderNo")); d.put("merchant", o.optString("merchant")); d.put("doneAt", now);
                    keep.put(d);
                } catch (JSONException ignored) { }
            } else out.put(o);
        }
        for (int i = 0; i < done.length() && keep.length() < 300; i++) {
            JSONObject d = done.optJSONObject(i);
            if (d != null && now - d.optLong("doneAt") < DONE_KEEP) keep.put(d);
        }
        sp(c).edit().putString("pending", out.toString()).putString("done", keep.toString()).apply();
    }

    /** 只记录看起来和交易有关的通知，聊天等其他内容不保存 */
    static synchronized void log(Context c, String pkg, String title, String text, PayParser.Result r, long time) {
        JSONArray arr = read(c, "log"), out = new JSONArray();
        try {
            JSONObject o = new JSONObject();
            o.put("pkg", pkg);
            o.put("title", title);
            o.put("text", text);
            o.put("time", time);
            o.put("result", r == null ? "" : r.toString());
            out.put(o);
            for (int i = 0; i < arr.length() && out.length() < MAX_LOG; i++) out.put(arr.get(i));
        } catch (JSONException ignored) { }
        sp(c).edit().putString("log", out.toString()).apply();
    }

    static synchronized String getLog(Context c) { return read(c, "log").toString(); }

    static synchronized void clearLog(Context c) { sp(c).edit().putString("log", "[]").apply(); }
}
