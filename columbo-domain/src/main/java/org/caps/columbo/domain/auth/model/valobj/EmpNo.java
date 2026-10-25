package org.caps.columbo.domain.auth.model.valobj;

import org.caps.columbo.types.enums.ResponseCode;
import org.caps.columbo.types.exception.AppException;

import java.util.regex.Pattern;

/**
 * Domain Primitive：工号。注册时的合法性（2-64 位字母/数字/._-）原来散在
 * Service 的正则常量里；显性化为自验证对象——拿到 EmpNo 即拿到合法工号，
 * 同时是会话归属 userId 的唯一来源。
 */
public final class EmpNo {

    private static final Pattern PATTERN = Pattern.compile("^[a-zA-Z0-9._-]{2,64}$");

    private final String value;

    private EmpNo(String value) {
        this.value = value;
    }

    /** 校验并构造：空/非法格式抛 AppException(0002) */
    public static EmpNo of(String raw) {
        if (raw == null || raw.isBlank() || !PATTERN.matcher(raw.trim()).matches()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                    "工号需为 2-64 位字母/数字/._- 组合");
        }
        return new EmpNo(raw.trim());
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof EmpNo && ((EmpNo) o).value.equals(value);
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
