package org.caps.columbo.domain.agent.model.valobj;

import org.caps.columbo.types.exception.AppException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DP 单测：会话标题/归档空间自验证
 */
class SessionTitleAndArchiveSpaceTest {

    @Test
    void titleAcceptsLegalRange() {
        assertEquals("值班9月12日", SessionTitle.of(" 值班9月12日 ").value());
        assertEquals("a".repeat(100), SessionTitle.of("a".repeat(100)).value());
    }

    @Test
    void titleRejectsBlankOrTooLong() {
        assertThrows(AppException.class, () -> SessionTitle.of(null));
        assertThrows(AppException.class, () -> SessionTitle.of("  "));
        assertThrows(AppException.class, () -> SessionTitle.of("a".repeat(101)));
    }

    @Test
    void spaceAcceptsEmptyAsNone() {
        assertTrue(ArchiveSpace.of(null).isNone());
        assertTrue(ArchiveSpace.of("  ").isNone());
        assertEquals("", ArchiveSpace.of("").value());
    }

    @Test
    void spaceRejectsTooLong() {
        assertEquals("值班", ArchiveSpace.of(" 值班 ").value());
        assertThrows(AppException.class, () -> ArchiveSpace.of("长".repeat(33)));
    }
}
