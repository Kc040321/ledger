package com.ledger.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从支付/银行通知文字里提取一笔交易。纯 Java，不依赖安卓，方便单独测试。
 * 只认「像交易」的通知：必须有交易关键词 + 带货币标记的金额；余额、额度、验证码一律排除。
 * 结果只用于预填，最终由用户在记账本里确认。
 */
public final class PayParser {

    public static final class Result {
        public long amount;        // 分
        public String type;        // exp | inc | refund
        public String merchant = "";
        public String account = ""; // 微信 / 支付宝 / 信用卡 / 银行卡 / 云闪付
        public String source = "";  // 显示用来源
        public String bank = "";    // 短信签名里的银行名
        @Override public String toString() {
            return type + " " + amount + " merchant=" + merchant + " account=" + account + " source=" + source + " bank=" + bank;
        }
    }

    public static final String PKG_WECHAT = "com.tencent.mm";
    public static final String PKG_ALIPAY = "com.eg.android.AlipayGphone";
    public static final String PKG_UNIONPAY = "com.unionpay";

    private static final Pattern MONEY = Pattern.compile(
        "(¥|￥|人民币|RMB|CNY)?\\s*((?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{1,2})?)\\s*(元|块)?");
    private static final Pattern TX_WORDS = Pattern.compile(
        "(消费|支出|支付|付款|扣款|扣费|转出|收入|入账|到账|转入|存入|收款|退款|交易|已付|代扣|还款|取现)");
    private static final Pattern INCOME_WORDS = Pattern.compile("(收入|入账|到账|转入|存入|转存|汇入|收款|收到|退回|向您转|向你转|转给你|给你转)");
    private static final Pattern REFUND_WORDS = Pattern.compile("(退款|退回|撤销)");
    private static final Pattern EXPENSE_WORDS = Pattern.compile("(消费|支出|支付|付款|扣款|扣费|转出|已付|代扣|取现)");
    private static final Pattern SKIP_BEFORE = Pattern.compile("(余额|可用|额度|积分|优惠|立减|减免|抵扣|红包抵|最低还款|账单金额|应还|限额|手续费为)\\s*[:：为是]?\\s*$");
    private static final Pattern NOISE = Pattern.compile("(验证码|校验码|动态码|密码|登录|注册|快递|取件|广告|退订|领取.{0,4}红包|理财|贷款|借款|额度提升)");
    private static final Pattern SMS_SIGN = Pattern.compile("[【\\[]([^】\\]]{2,12})[】\\]]");

    private PayParser() {}

    /** 识别不了时返回 null */
    public static Result parse(String pkg, String title, String text) {
        String t = ((title == null ? "" : title) + " " + (text == null ? "" : text))
            .replace('，', ',').replace('：', ':').replace('（', '(').replace('）', ')').trim();
        if (t.isEmpty()) return null;
        String noComma = t;
        boolean isPayApp = PKG_WECHAT.equals(pkg) || PKG_ALIPAY.equals(pkg) || PKG_UNIONPAY.equals(pkg);
        if (NOISE.matcher(t).find() && !isPayApp) return null;
        if (!TX_WORDS.matcher(t).find()) return null;

        // 找金额：必须有 ¥/人民币/RMB 前缀或「元」后缀，或者是支付 App 里带两位小数的数字
        List<long[]> cands = new ArrayList<>(); // {amount, start, score}
        Matcher m = MONEY.matcher(noComma);
        while (m.find()) {
            String num = m.group(2);
            boolean cur = m.group(1) != null, yuan = m.group(3) != null, twoDec = num.matches(".*\\.\\d{2}$");
            if (!cur && !yuan && !(isPayApp && twoDec)) continue;
            // 排除尾号、日期、时间
            String before = noComma.substring(Math.max(0, m.start() - 10), m.start());
            if (before.matches(".*(尾号|末四位|卡号|账户|账号)\\s*$") && !cur && !yuan) continue;
            if (SKIP_BEFORE.matcher(before).find()) continue;
            long v = toCents(num);
            if (v <= 0 || v > 100_000_000_00L) continue;
            long score = 0;
            String near = noComma.substring(Math.max(0, m.start() - 14), m.start());
            if (TX_WORDS.matcher(near).find()) score += 10;
            if (cur) score += 3;
            if (twoDec) score += 1;
            cands.add(new long[]{v, m.start(), score});
        }
        if (cands.isEmpty()) return null;
        long[] best = cands.get(0);
        for (long[] c : cands) if (c[2] > best[2]) best = c;

        Result r = new Result();
        r.amount = best[0];
        // 收支方向：看金额前面最近的关键词
        String head = noComma.substring(0, (int) best[1]);
        int iInc = lastIndexOf(INCOME_WORDS, head), iExp = lastIndexOf(EXPENSE_WORDS, head), iRef = lastIndexOf(REFUND_WORDS, head);
        if (iInc < 0 && iExp < 0 && iRef < 0) { // 关键词在金额后面，比如「23.80元的支出」
            String tail = noComma.substring((int) best[1]);
            iInc = firstIndexOf(INCOME_WORDS, tail); iExp = firstIndexOf(EXPENSE_WORDS, tail); iRef = firstIndexOf(REFUND_WORDS, tail);
            r.type = iRef >= 0 ? "refund" : (iInc >= 0 && (iExp < 0 || iInc < iExp)) ? "inc" : "exp";
        } else {
            r.type = (iRef >= 0 && iRef >= iInc && iRef >= iExp) ? "refund" : (iInc > iExp) ? "inc" : "exp";
        }

        // 来源和账户
        Matcher sign = SMS_SIGN.matcher(t);
        if (sign.find()) r.bank = sign.group(1);
        if (PKG_WECHAT.equals(pkg)) { r.source = "微信"; r.account = "微信"; }
        else if (PKG_ALIPAY.equals(pkg)) { r.source = "支付宝"; r.account = "支付宝"; }
        else if (PKG_UNIONPAY.equals(pkg)) { r.source = "云闪付"; r.account = "银行卡"; }
        else {
            r.source = r.bank.isEmpty() ? "银行通知" : r.bank;
            r.account = Pattern.compile("信用卡|贷记卡").matcher(t).find() ? "信用卡" : "银行卡";
        }
        if (r.account.equals("微信") && t.contains("零钱")) r.account = "微信零钱";

        // 商户
        String[] pats = {
            "在([^,，。;；\\s]{2,24}?)(?:消费|支付|付款|使用)",
            "向([^,，。;；\\s]{2,24}?)(?:付款|支付|转账)",
            "(?:支付给|付款给|付给|转账给|收款方|商户名称|商户|对方户名|对方)[:为]?\\s*([^,，。;；\\s]{2,24})",
            "(?:来自|从)([^,，。;；\\s]{2,16}?)(?:的)?(?:转账|收款|入账|汇款)",
        };
        for (String p : pats) {
            Matcher mm = Pattern.compile(p).matcher(t);
            if (mm.find()) { r.merchant = mm.group(1).replaceAll("[()]$", ""); break; }
        }
        return r;
    }

    private static int lastIndexOf(Pattern p, String s) { Matcher m = p.matcher(s); int i = -1; while (m.find()) i = m.start(); return i; }
    private static int firstIndexOf(Pattern p, String s) { Matcher m = p.matcher(s); return m.find() ? m.start() : -1; }

    static long toCents(String num) {
        String s = num.replace(",", "");
        String[] p = s.split("\\.", 2);
        long y = Long.parseLong(p[0]);
        long f = 0;
        if (p.length > 1) { String d = (p[1] + "00").substring(0, 2); f = Long.parseLong(d); }
        return y * 100 + f;
    }

    /** 同一笔钱可能同时收到支付 App 通知和银行短信：金额、方向相同且相隔 3 分钟内，视为同一笔 */
    public static boolean sameTx(long amountA, String typeA, long timeA, long amountB, String typeB, long timeB) {
        return amountA == amountB && typeA.equals(typeB) && Math.abs(timeA - timeB) <= 3 * 60 * 1000L;
    }

    static String lower(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT); }
}
