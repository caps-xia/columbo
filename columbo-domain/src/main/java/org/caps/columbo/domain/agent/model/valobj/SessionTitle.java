package org.caps.columbo.domain.agent.model.valobj;

import org.caps.columbo.types.enums.ResponseCode;
import org.caps.columbo.types.exception.AppException;

/**
 * Domain Primitive：会话标题。改名时的长度规则（1-100 字）原来内联在
 * ChatService 的 if 里；自验证后构造即合法，应用层不再关心规则细节。
 */
public final class SessionTitle {

    private final String value;

    private SessionTitle(String value) {
        this.value = value;
    }

    /** 校验并构造：trim 后 1-100 字，非法抛 AppException(0002) */
    public static SessionTitle of(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > 100) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "标题长度需在 1-100 字之间");
        }
        return new SessionTitle(trimmed);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SessionTitle && ((SessionTitle) o).value.equals(value);
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
