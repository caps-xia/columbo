package org.caps.columbo.domain.agent.service.session;

import com.google.adk.events.Event;
import org.caps.columbo.domain.agent.adapter.port.ISessionSummarizePort;
import org.caps.columbo.domain.agent.model.valobj.properties.SessionCompactionProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 会话压缩服务：翻篇前把旧会话压缩成"可续接"的摘要。
 *
 * <p>定位：压缩优先、翻篇兜底两级上下文治理。翻篇（rollover）是硬切换——新上下文空白，
 * 长排查中途被翻篇会丢失已查到的线索；压缩把旧会话总结成摘要结转过去，关键结论/证据/
 * 未完成事项保留。ADK 会话是 append-only（事件不可改写、持久层按顺序重放），无法原地
 * 改写历史，因此压缩实现为"翻篇 + 摘要结转"：触发点与就地压缩相同、上下文效果等价，
 * 且保留完整审计轨迹。
 *
 * <p>失败降级：摘要模型超时/异常/未启用时返回 null，调用方退回普通翻篇，不阻断对话。
 *
 * @author caps
 */
@Slf4j
@Component
public class SessionCompactionService {

    /**
     * 摘要 SOP：摘要是给"继续排查的助手"看的，不是给人看的会议纪要——
     * 保留可复用的证据与锚点（时间/ID/代码位置/初步结论），舍弃寒暄与过程性试错。
     */
    static final String SUMMARY_SOP = """
            你是排查助手的"会话续接摘要器"。上一段排查对话上下文过长即将翻篇，请把它压缩成一段摘要，\
            供新上下文中的排查助手继续工作。要求：
            1. 保留问题背景：谁反馈了什么问题、涉及的环境/应用/业务线/关键单号；
            2. 保留已确认的事实与证据：日志关键行（时间、级别、traceId、报错原文）、定位到的\
               代码文件与行号、初步根因假设、已确认的数据结论（memberId/订单状态等）；
            3. 保留未完成事项与下一步排查方向（如"已排除 A，待核实 B"）；
            4. 严格基于对话原文，不编造未出现的信息；不输出寒暄、建议或与排查无关的内容；
            5. 直接输出摘要正文，控制在 %d 字以内。""";

    private final ISessionSummarizePort summarizePort;

    private final SessionCompactionProperties properties;

    public SessionCompactionService(ISessionSummarizePort summarizePort, SessionCompactionProperties properties) {
        this.summarizePort = summarizePort;
        this.properties = properties;
    }

    /**
     * 压缩一次会话事件流。
     *
     * @param events 旧会话的全部事件（含 user/model）
     * @return 摘要正文；未启用/事件太少/摘要失败时返回 null（调用方降级普通翻篇）
     */
    public String summarize(List<Event> events) {
        if (!properties.isEnabled() || events == null || events.isEmpty()) {
            return null;
        }
        int minEvents = properties.getMinEvents() == null ? 3 : properties.getMinEvents();
        if (events.size() < minEvents) {
            return null;
        }
        String transcript = buildTranscript(events);
        if (transcript == null || transcript.isBlank()) {
            return null;
        }
        if (!configured()) {
            log.info("会话压缩未配置摘要模型（ai.agent.session-compaction），按普通翻篇处理");
            return null;
        }
        int maxSummaryChars = properties.getMaxSummaryChars() == null ? 2000 : properties.getMaxSummaryChars();
        String summary;
        try {
            summary = summarizePort.summarize(SUMMARY_SOP.formatted(maxSummaryChars), transcript);
        } catch (Exception e) {
            log.warn("会话摘要模型调用失败，降级普通翻篇: {}", e.getMessage());
            return null;
        }
        if (summary == null || summary.isBlank()) {
            return null;
        }
        summary = summary.strip();
        // 护栏截断：模型偶发超长时保证摘要可控（省略号标记截断点）
        if (summary.length() > maxSummaryChars) {
            summary = summary.substring(0, maxSummaryChars) + "…（已截断）";
        }
        log.info("会话压缩完成：原文 {} 字符 -> 摘要 {} 字符", transcript.length(), summary.length());
        return summary;
    }

    /** 拼接会话记录（作者+正文）；partial 中间帧与空内容跳过，总长超限从头部截断 */
    private String buildTranscript(List<Event> events) {
        int maxChars = properties.getMaxTranscriptChars() == null ? 100000 : properties.getMaxTranscriptChars();
        StringBuilder transcript = new StringBuilder();
        for (Event event : events) {
            if (event.partial().orElse(false)) {
                continue;
            }
            String content = event.stringifyContent();
            if (content == null || content.isBlank()) {
                continue;
            }
            String line = "[" + (event.author() == null ? "unknown" : event.author()) + "] "
                    + content.strip() + "\n\n";
            if (transcript.length() + line.length() > maxChars) {
                transcript.append("…（会话记录过长，仅保留前 ")
                        .append(maxChars).append(" 字符参与压缩）\n");
                break;
            }
            transcript.append(line);
        }
        return transcript.toString();
    }

    private boolean configured() {
        return properties.getBaseUrl() != null && !properties.getBaseUrl().isBlank()
                && properties.getModel() != null && !properties.getModel().isBlank();
    }
}
