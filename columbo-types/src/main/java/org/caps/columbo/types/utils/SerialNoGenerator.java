package org.caps.columbo.types.utils;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.util.Random;

/**
 * 全局单号/ID 生成器（Snowflake 思想的定制实现）
 *
 * <pre>
 * ======================= 单号结构 =======================
 *
 * SerialNo = Head + Body
 *
 * Head（变长）：
 *   prefix(1)     业务前缀（大写字母/数字）
 *   subPrefix(0-1) 子业务前缀，可空
 *   envMark(1)    环境标识位（默认 X，可用 init() 覆盖）
 *
 * Body（固定 17 位，Base36）：
 *   base36(毫秒时间差)  —— 时间维度，保证整体有序
 *   nodeId(3)           —— JVM 节点号，区分不同实例
 *   seq(2)              —— 毫秒内自增序列，保证高并发唯一
 *   mix                 —— hash 混淆补位，保证长度固定
 *
 * ======================= 设计目标 =======================
 *
 * - Body 长度固定、可读性强（Base36）
 * - 单 JVM 内严格唯一；高并发下无序列回环
 * - 支持多实例部署（节点号按 hostname+PID+启动时间 hash，免配置）
 * - 容忍小幅时间回拨（NTP / 虚拟化环境，&lt;= 5ms）
 * </pre>
 *
 * <p>提炼自 YL hospital-interface-web / hospital-admin-api-web 的 SerialNoGenerator，
 * 去除了部署环境枚举耦合：环境标识改为可配置的单字符，默认 'X'，无需初始化即可使用。
 *
 * <p>注意：EPOCH_MILLIS 一旦上线严禁修改。
 */
public final class SerialNoGenerator {

    /** 自定义时间纪元（2026-01-01 00:00:00.000），用于缩短时间戳长度 */
    private static final long EPOCH_MILLIS = 1767225600000L;

    /** Base36 字符表（大写） */
    private static final String BASE36 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    /** Body 固定长度 */
    private static final int BODY_LEN = 17;

    /** 毫秒内最大序列号：36^2 = 1296 */
    private static final int SEQ_LIMIT = 1296;

    private static final int MAX_NODE_ID = 36 * 36 * 36; // 46656

    /** 环境标识位（默认 X；如需区分部署环境可在启动期调用 init 覆盖） */
    private static volatile String envMark = "X";

    /** JVM 节点号：hostname + JVM 启动时间 hash，用于区分不同 JVM 实例 */
    private static final int NODE_ID = resolveNodeId();

    /** 上一次生成单号所使用的毫秒时间 */
    private static long lastMillis = -1L;

    /** 当前毫秒内的自增序列 */
    private static int seq = 0;

    /** 全局锁：保证 lastMillis + seq 的原子性 */
    private static final Object LOCK = new Object();

    private SerialNoGenerator() {
    }

    /**
     * 初始化环境标识（Spring 启动阶段调用一次即可，不调用则默认 X）。
     *
     * @param mark 1 位大写字母或数字；传入小写会自动转大写
     */
    public static void init(String mark) {
        if (mark == null || mark.trim().isEmpty()) {
            throw new IllegalArgumentException("env mark is empty");
        }
        String m = mark.trim().toUpperCase();
        if (m.length() != 1 || BASE36.indexOf(m.charAt(0)) < 0) {
            throw new IllegalArgumentException("env mark must be 1 alphanumeric char: " + mark);
        }
        envMark = m;
    }

    /**
     * 生成单号：prefix + envMark + 17 位 Body。
     *
     * @param prefix     业务前缀（1 位）
     * @param subPrefix  子业务前缀（1 位，可为空）
     */
    public static String generate(String prefix, String subPrefix) {
        if (prefix == null || prefix.trim().isEmpty()) {
            throw new IllegalArgumentException("prefix is empty");
        }

        String head = canonicalPrefix(prefix, subPrefix) + envMark;

        long timePart;
        int currentSeq;

        synchronized (LOCK) {
            long now = System.currentTimeMillis();

            // 时间回拨处理（容忍 <= 5ms）
            long offset = lastMillis - now;
            if (offset > 0) {
                if (offset <= 5) {
                    now = lastMillis;
                } else {
                    throw new IllegalStateException("Clock moved backwards too much: " + offset + " ms");
                }
            }

            if (now == lastMillis) {
                seq++;
                if (seq >= SEQ_LIMIT) {
                    // 当前毫秒序列用尽，阻塞到下一毫秒
                    now = waitNextMillis(now);
                    seq = 0;
                }
            } else {
                seq = 0;
            }

            lastMillis = now;
            timePart = now - EPOCH_MILLIS;
            currentSeq = seq;
        }

        String bodyRaw = toBase36(timePart)
                + leftPadBase36(NODE_ID, 3)
                + leftPadBase36(currentSeq, 2);

        return head + normalizeBody(bodyRaw, BODY_LEN, head, timePart, NODE_ID, currentSeq);
    }

    /** 生成单号（无子前缀）：prefix + envMark + 17 位 Body。 */
    public static String generate(String prefix) {
        return generate(prefix, null);
    }

    /** 阻塞等待进入下一个毫秒 */
    private static long waitNextMillis(long last) {
        long now;
        do {
            now = System.currentTimeMillis();
        } while (now <= last);
        return now;
    }

    /** Body 长度标准化（不足补 hash 混淆位 / 超长安全截断） */
    private static String normalizeBody(String body, int len, String prefix,
                                        long timePart, int nodeId, int seqVal) {
        if (body.length() == len) {
            return body;
        }
        if (body.length() > len) {
            // 超长时保留高位时间，尾部用 hash 混淆
            String hashTail = leftPadBase36(Math.abs(body.hashCode()) % SEQ_LIMIT, 3);
            return body.substring(0, len - 3) + hashTail;
        }
        // 不足时基于多因子 hash 补齐
        String seed = prefix + timePart + nodeId + seqVal;
        String mix = toBase36(Math.abs(seed.hashCode()));
        StringBuilder sb = new StringBuilder(len);
        sb.append(body);
        int i = 0;
        while (sb.length() < len) {
            sb.append(mix.charAt(i++ % mix.length()));
        }
        return sb.toString();
    }

    /** long → Base36 字符串 */
    private static String toBase36(long val) {
        if (val == 0) {
            return "0";
        }
        StringBuilder sb = new StringBuilder();
        while (val > 0) {
            sb.append(BASE36.charAt((int) (val % 36)));
            val /= 36;
        }
        return sb.reverse().toString();
    }

    /** Base36 左补 0 */
    private static String leftPadBase36(int val, int len) {
        String s = Integer.toString(val, 36).toUpperCase();
        while (s.length() < len) {
            s = "0" + s;
        }
        return s;
    }

    /** 前缀规范化：trim + 大写（子前缀可空） */
    private static String canonicalPrefix(String prefix, String subPrefix) {
        String p = prefix.trim().toUpperCase();
        if (subPrefix == null || subPrefix.trim().isEmpty()) {
            return p;
        }
        return p + subPrefix.trim().toUpperCase();
    }

    /** 计算 JVM 节点号（hostname + pid + 启动时间 + nano 组合种子） */
    private static int resolveNodeId() {
        try {
            String hostname = InetAddress.getLocalHost().getHostName();
            String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
            long jvmStart = ManagementFactory.getRuntimeMXBean().getStartTime();
            long nano = System.nanoTime();
            String seed = hostname + "|" + pid + "|" + jvmStart + "|" + nano;
            return Math.abs(seed.hashCode()) % MAX_NODE_ID;
        } catch (Exception e) {
            return new Random().nextInt(MAX_NODE_ID);
        }
    }
}
