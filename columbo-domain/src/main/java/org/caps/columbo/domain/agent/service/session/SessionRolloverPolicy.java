package org.caps.columbo.domain.agent.service.session;

/**
 * 领域策略：会话上下文翻篇规则。单个底层会话事件数超过阈值即换新上下文
 * （前端 sessionId 不变的"翻篇"），防止长会话把网关大请求打爆。
 * 阈值本身由配置注入（ai.agent.session-event-limit，默认 80），
 * "超过即翻"这条规则属于领域知识，收拢于此。
 */
public final class SessionRolloverPolicy {

    private SessionRolloverPolicy() {
    }

    /** 事件数达到阈值即需要翻篇（≥ 视为已膨胀） */
    public static boolean needRollover(int eventCount, int limit) {
        return eventCount >= limit;
    }

    /**
     * 事件数或内容总字符数任一达到阈值即需要翻篇：超大内容（如流式重复 bug 产生的
     * 30MB 单事件）不增加事件数，纯事件数阈值永远不触发，会话会被超长上下文"砖死"
     * （2026-09-15 线上事故：4 个事件 30MB，后续提问全部 400 prompt is too long）。
     */
    public static boolean needRollover(int eventCount, int eventLimit, long contentChars, long contentLimitChars) {
        return needRollover(eventCount, eventLimit) || contentChars >= contentLimitChars;
    }
}
