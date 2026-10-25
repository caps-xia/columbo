package org.caps.columbo.domain.auth.model.valobj;

import org.caps.columbo.types.exception.AppException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * DP 单测：工号自验证
 */
class EmpNoTest {

    @Test
    void acceptsLegalEmpNo() {
        assertEquals("zhangsan", EmpNo.of("zhangsan").value());
        assertEquals("caps.dev-01", EmpNo.of(" caps.dev-01 ").value());
    }

    @Test
    void rejectsIllegalEmpNo() {
        assertThrows(AppException.class, () -> EmpNo.of("非法工号!"));
        assertThrows(AppException.class, () -> EmpNo.of("a"));
        assertThrows(AppException.class, () -> EmpNo.of(null));
        assertThrows(AppException.class, () -> EmpNo.of(" "));
    }
}
