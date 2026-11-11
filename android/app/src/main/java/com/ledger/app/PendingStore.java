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

    /** 加入队列；如果 3 分钟内已有同金额同方向的一笔（比如微信通知 + 银行短信），合并来源，返回 null */
    static synchronized JSONObject add(Context c, PayParser.Result r, String pkg, String raw, long time) {
        JSONArray arr = read(c, "pending");
        try {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (PayParser.sameTx(o.getLong("amount"), o.getString("type"), o.getLong("time"), r.amount, r.type, time)) {
                    String src = o.optString("source");
                    if (!src.contains(r.source)) o.put("source", src + " + " + r.source);
                    if (o.optString("merchant").isEmpty() && !r.merchant.isEmpty()) o.put("merchant", r.merchant);
                    o.put("raw", o.optString("raw") + "\n" + raw);
                    sp(c).edit().putString("pending", arr.toString()).apply();
                    return null;
                }
            }
            JSONObject o = new JSONObject();
            o.put("id", "p" + time + "_" + (int) (Math.random() * 1e6));
            o.put("amount", r.amount);
            o.put("type", r.type);
            o.put("merchant", r.merchant);
            o.put("account", r.account);
            o.put("source", r.source);
            o.put("bank", r.bank);
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

    static synchronized void remove(Context c, String id) {
        JSONArray arr = read(c, "pending"), out = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && !id.equals(o.optString("id"))) out.put(o);
        }
        sp(c).edit().putString("pending", out.toString()).apply();
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
