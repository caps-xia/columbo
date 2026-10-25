package org.caps.columbo.domain.agent.model.valobj;

import org.caps.columbo.types.enums.ResponseCode;
import org.caps.columbo.types.exception.AppException;

/**
 * Domain Primitive：归档空间名（如: 值班、日常）。长度规则（最长 32 字）
 * 原来内联在 ChatService；空串是合法值，语义为"归位未归档"（持久层 NULLIF 转 NULL）。
 */
public final class ArchiveSpace {

    /** 未归档（空串在库内落为 NULL） */
    public static final ArchiveSpace NONE = new ArchiveSpace("");

    private final String value;

    private ArchiveSpace(String value) {
        this.value = value;
    }

    /** 校验并构造：trim 后最长 32 字，空串=未归档 */
    public static ArchiveSpace of(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.length() > 32) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "空间名最长 32 字");
        }
        return trimmed.isEmpty() ? NONE : new ArchiveSpace(trimmed);
    }

    public String value() {
        return value;
    }

    public boolean isNone() {
        return value.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ArchiveSpace && ((ArchiveSpace) o).value.equals(value);
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
