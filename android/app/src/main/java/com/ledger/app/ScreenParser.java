package com.ledger.app;

import java.util.Calendar;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从支付宝 / 微信 / 银行 App 的「支付成功」「账单详情」「已收款」页面文字里读出一笔交易。
 * 纯 Java，不依赖安卓，方便单独测试。输入是页面上按顺序排列的文字。
 */
public final class ScreenParser {
    private ScreenParser() {}

    private static final Pattern SUCCESS = Pattern.compile("(支付成功|付款成功|交易成功|转账成功|已支付|支付完成|付款完成|成功付款|已付款|还款成功|充值成功|缴费成功)");
    private static final Pattern INCOME_PAGE = Pattern.compile("(已收款|收款成功|已存入零钱|已存入余额|存入零钱|已存入|你已收款|已收钱|收钱到账|已领取|红包已存入)");
    private static final Pattern DETAIL = Pattern.compile("(账单详情|交易详情|交易单号|订单号|商户单号|支付时间|创建时间|交易时间|转账时间|收款时间|付款时间|到账时间)");
    private static final Pattern REFUND = Pattern.compile("(退款成功|已退款|退款到账|退款详情)");
    // 付款前的页面、付款码、账单列表等：不记
    private static final Pattern EXCLUDE = Pattern.compile("(输入金额|付款码|收款码|设置金额|确认付款|确认支付|待支付|立即支付|去支付|支付密码|输入密码|请输入|本月支出|月账单|全部账单|筛选|收支分析|账单分类统计|待付款|待收款|未领取|领取红包|开红包)");
    private static final Pattern AMOUNT_LINE = Pattern.compile("^\\s*([+\\-−])?\\s*[¥￥]?\\s*([+\\-−])?\\s*(\\d{1,3}(?:,\\d{3})+|\\d+)\\.(\\d{2})\\s*(元)?\\s*$");
    private static final Pattern AMOUNT_IN = Pattern.compile("([+\\-−])?\\s*[¥￥]\\s*([+\\-−])?\\s*(\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.(\\d{1,2}))?");
    private static final Pattern SKIP_LABEL = Pattern.compile("(优惠|原价|余额|可用|手续费|立减|减免|红包抵扣|抵扣|积分|奖励|返现|已省|节省|服务费|限额|额度|应还|待还)");
    private static final Pattern NOT_NAME = Pattern.compile("(成功|完成|详情|账单|交易|订单|单号|时间|状态|方式|返回|查看|分类|备注|标签|服务|客服|联系|投诉|疑问|帮助|更多|分享|首页|关闭|确定|元$|^[\\d\\s:\\-./年月日]+$|[¥￥]|支付宝|微信支付|^微信$|零钱|余额|花呗|银行卡|储蓄卡|信用卡|付款|收款|当前|对此|计入|不计入|收支|推荐|广告|会员|立即|去使用)");
    private static final Pattern NAME_LABEL = Pattern.compile("^(收款方|商户全称|商户名称|商户|商品说明|商品|对方账户|对方|收款人|付款人|转账给|付款给|交易对方|对方户名)[:：]?\\s*(.*)$");
    private static final Pattern PAY_LABEL = Pattern.compile("^(付款方式|支付方式|收款方式|到账方式|退款方式)[:：]?\\s*(.*)$");
    private static final Pattern ORDER = Pattern.compile("(订单号|交易单号|商户单号|流水号|转账单号)[:：]?\\s*([A-Za-z0-9]{10,})?");
    private static final Pattern TIME = Pattern.compile("(\\d{4})[-/年](\\d{1,2})[-/月](\\d{1,2})日?\\s*(\\d{1,2}):(\\d{2})(?::(\\d{2}))?");
    private static final Pattern TIME_MD = Pattern.compile("(?<!\\d)(\\d{1,2})月(\\d{1,2})日\\s*(\\d{1,2}):(\\d{2})");
    private static final Pattern TRANSFER_TO = Pattern.compile("(?:转账给|转给|向)([^\\s,，。:：]{1,16}?)(?:转账|付款)?$");
    private static final Pattern TRANSFER_FROM = Pattern.compile("(?:收到|来自|^)([^\\s,，。:：]{1,16}?)(?:发)?的(?:转账|红包)$");

    /** 页面像不像付款相关页面（用于识别记录，帮助调规则） */
    public static boolean looksRelevant(List<String> lines) {
        String all = String.join(" ", lines);
        return (SUCCESS.matcher(all).find() || INCOME_PAGE.matcher(all).find() || DETAIL.matcher(all).find() || REFUND.matcher(all).find())
            && (all.contains("¥") || all.contains("￥") || Pattern.compile("\\d\\.\\d{2}").matcher(all).find());
    }

    /** 识别不了返回 null */
    public static PayParser.Result parse(String pkg, List<String> raw, long now) {
        if (raw == null || raw.isEmpty()) return null;
        String[] lines = new String[raw.size()];
        for (int i = 0; i < lines.length; i++) lines[i] = raw.get(i) == null ? "" : raw.get(i).replace('\u00a0', ' ').trim();
        String all = String.join("\n", lines);
        boolean success = SUCCESS.matcher(all).find(), income = INCOME_PAGE.matcher(all).find(), detail = DETAIL.matcher(all).find(), refund = REFUND.matcher(all).find();
        if (!success && !income && !detail && !refund) return null;
        if (EXCLUDE.matcher(all).find()) return null;

        // 金额：优先「整行就是金额」的那一行（页面上的大字），其次带 ¥ 的
        int ai = -1; long amount = 0; String sign = null;
        for (int pass = 0; pass < 2 && ai < 0; pass++) {
            for (int i = 0; i < lines.length; i++) {
                Matcher m = pass == 0 ? AMOUNT_LINE.matcher(lines[i]) : AMOUNT_IN.matcher(lines[i]);
                if (!(pass == 0 ? m.matches() : m.find())) continue;
                if (SKIP_LABEL.matcher(lines[i]).find()) continue;
                if (i > 0 && SKIP_LABEL.matcher(lines[i - 1]).find() && lines[i - 1].length() <= 8) continue;
                String sg = m.group(1) != null ? m.group(1) : m.group(2);
                String dec = pass == 0 ? m.group(4) : (m.group(4) == null ? "0" : m.group(4));
                long v = PayParser.toCents(m.group(3) + "." + dec);
                if (v <= 0 || v > 100_000_000_00L) continue;
                ai = i; amount = v; sign = sg; break;
            }
        }
        if (ai < 0) return null;

        PayParser.Result r = new PayParser.Result();
        r.amount = amount;
        if (refund && !success) r.type = "refund";
        else if ("+".equals(sign)) r.type = "inc";
        else if (sign != null) r.type = "exp";
        else r.type = income && !success ? "inc" : "exp";

        // 来源和账户
        if (PayParser.PKG_WECHAT.equals(pkg)) { r.source = "微信"; r.account = "微信"; }
        else if (PayParser.PKG_ALIPAY.equals(pkg)) { r.source = "支付宝"; r.account = "支付宝"; }
        else if (PayParser.PKG_UNIONPAY.equals(pkg)) { r.source = "云闪付"; r.account = "银行卡"; }
        else { r.source = "银行App"; r.account = "银行卡"; }
        for (int i = 0; i < lines.length; i++) {
            Matcher m = PAY_LABEL.matcher(lines[i]);
            if (!m.find()) continue;
            String v = m.group(2).isEmpty() && i + 1 < lines.length ? lines[i + 1] : m.group(2);
            if (v.contains("零钱")) r.account = "微信零钱";
            else if (v.contains("花呗")) r.account = "花呗";
            else if (v.contains("信用卡")) r.account = "信用卡 " + v;
            else if (v.contains("银行") || v.contains("储蓄卡")) r.account = "银行卡 " + v;
            else if (v.contains("余额")) r.account = r.source;
            break;
        }

        // 商户 / 对方
        for (int i = 0; i < lines.length && r.merchant.isEmpty(); i++) {
            Matcher m = NAME_LABEL.matcher(lines[i]);
            if (!m.find()) continue;
            String v = m.group(2).isEmpty() && i + 1 < lines.length ? lines[i + 1] : m.group(2);
            if (okName(v)) r.merchant = clean(v);
        }
        for (String l : lines) {
            if (!r.merchant.isEmpty()) break;
            Matcher m = TRANSFER_FROM.matcher(l);
            if (m.find() && okName(m.group(1))) { r.merchant = m.group(1); break; }
            m = TRANSFER_TO.matcher(l);
            if (m.find() && okName(m.group(1))) r.merchant = m.group(1);
        }
        for (int i = ai - 1; i >= 0 && i >= ai - 4 && r.merchant.isEmpty(); i--) if (okName(lines[i])) r.merchant = clean(lines[i]);
        for (int i = ai + 1; i < lines.length && i <= ai + 2 && r.merchant.isEmpty(); i++) if (okName(lines[i])) r.merchant = clean(lines[i]);

        // 订单号
        for (int i = 0; i < lines.length; i++) {
            Matcher m = ORDER.matcher(lines[i]);
            if (!m.find()) continue;
            String v = m.group(2);
            if (v == null && i + 1 < lines.length && lines[i + 1].matches("[A-Za-z0-9]{10,}")) v = lines[i + 1];
            if (v != null) { r.orderNo = v; break; }
        }

        // 时间：账单详情里有交易时间；太久以前的账单（翻看旧账）不记
        r.time = now;
        Matcher tm = TIME.matcher(all);
        Calendar c = Calendar.getInstance();
        if (tm.find()) {
            c.set(Integer.parseInt(tm.group(1)), Integer.parseInt(tm.group(2)) - 1, Integer.parseInt(tm.group(3)), Integer.parseInt(tm.group(4)), Integer.parseInt(tm.group(5)), tm.group(6) == null ? 0 : Integer.parseInt(tm.group(6)));
            c.set(Calendar.MILLISECOND, 0);
            r.time = c.getTimeInMillis();
        } else {
            Matcher md = TIME_MD.matcher(all);
            if (md.find()) {
                c.setTimeInMillis(now);
                c.set(Calendar.MONTH, Integer.parseInt(md.group(1)) - 1);
                c.set(Calendar.DAY_OF_MONTH, Integer.parseInt(md.group(2)));
                c.set(Calendar.HOUR_OF_DAY, Integer.parseInt(md.group(3)));
                c.set(Calendar.MINUTE, Integer.parseInt(md.group(4)));
                c.set(Calendar.SECOND, 0);
                c.set(Calendar.MILLISECOND, 0);
                if (c.getTimeInMillis() > now + 60_000L) c.add(Calendar.YEAR, -1);
                r.time = c.getTimeInMillis();
            }
        }
        if (r.time > now + 10 * 60_000L || now - r.time > 24 * 3600_000L) return null;
        return r;
    }

    private static boolean okName(String s) {
        if (s == null) return false;
        s = s.trim();
        return s.length() >= 2 && s.length() <= 24 && !s.matches(".*[，。！!？?].*") && !NOT_NAME.matcher(s).find() && !s.matches(".*\\d{5,}.*");
    }

    private static String clean(String s) { return s.trim().replaceAll("[>›》]+$", "").trim(); }
}
