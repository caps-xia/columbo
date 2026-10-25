package org.caps.columbo.domain.troubleshoot.model.valobj;

import org.springframework.util.StringUtils;

/**
 * Domain Primitive：日志环境标识。product/qa 原是裸 String 从工具层
 * 一路透传到网关拼 SQL；显性化后按渠道归一。CLS 多站点后各站点支持的
 * 环境不同（默认 product/qa），白名单收敛到端口按站点校验。
 */
public final class LogEnv {

    public static final String PRODUCT = "product";
    public static final String QA = "qa";

    private final String value;

    private LogEnv(String value) {
        this.value = value;
    }

    /**
     * CLS 环境值归一：仅做 trim/小写（空返回 null=端口用站点默认）。
     * 不做白名单拦截——各站点支持的环境不同（默认 product/qa），非法值交端口
     * 按站点白名单报错，避免工具层把站点支持的环境值静默吞成默认环境。
     */
    public static String normalizeClsValue(String raw) {
        return StringUtils.hasText(raw) ? raw.trim().toLowerCase() : null;
    }

    /** 归一后的值；可能为 null（调用方语义：用站点默认） */
    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof LogEnv && ((LogEnv) o).value.equals(value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
