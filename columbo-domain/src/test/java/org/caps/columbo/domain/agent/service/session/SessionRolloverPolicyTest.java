package org.caps.columbo.domain.agent.service.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归：翻篇策略除事件数外必须看内容总量（2026-09-15 线上事故：
 * 4 个事件共 30MB，事件数阈值永远不触发，会话被超长上下文砖死）。
 */
class SessionRolloverPolicyTest {

    @Test
    void eventCountThresholdStillWorks() {
        assertTrue(SessionRolloverPolicy.needRollover(80, 80, 0, 1_000_000));
        assertFalse(SessionRolloverPolicy.needRollover(79, 80, 0, 1_000_000));
    }

    @Test
    void contentSizeTriggersRolloverEvenWithFewEvents() {
        assertTrue(SessionRolloverPolicy.needRollover(4, 80, 30_372_114, 1_000_000));
        assertTrue(SessionRolloverPolicy.needRollover(2, 80, 1_000_000, 1_000_000));
    }

    @Test
    void smallSessionDoesNotRollover() {
        assertFalse(SessionRolloverPolicy.needRollover(4, 80, 300, 1_000_000));
    }
}
