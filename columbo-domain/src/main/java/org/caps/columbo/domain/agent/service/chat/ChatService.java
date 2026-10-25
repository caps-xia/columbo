package org.caps.columbo.domain.agent.service.chat;

import org.caps.columbo.domain.agent.adapter.port.ISessionStorePort;
import org.caps.columbo.types.utils.SerialNoGenerator;
import org.caps.columbo.domain.agent.model.entity.ChatCommandEntity;
import org.caps.columbo.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.caps.columbo.domain.agent.model.valobj.AiAgentRegisterVO;
import org.caps.columbo.domain.agent.model.valobj.ChatMessageVO;
import org.caps.columbo.domain.agent.model.valobj.ChatSessionVO;
import org.caps.columbo.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import org.caps.columbo.domain.agent.model.valobj.AgentModelVO;
import org.caps.columbo.domain.agent.model.valobj.TokenUsageVO;
import org.caps.columbo.domain.agent.pipeline.ChatPipelineContext;
import org.caps.columbo.domain.agent.pipeline.Pipelines;
import org.caps.columbo.domain.agent.service.IArmoryService;
import org.caps.columbo.domain.agent.service.IChatService;
import org.caps.columbo.domain.agent.model.valobj.ArchiveSpace;
import org.caps.columbo.domain.agent.model.valobj.SessionTitle;
import org.caps.columbo.domain.agent.service.armory.factory.DefaultArmoryFactory;
import org.caps.columbo.domain.agent.service.session.SessionCompactionService;
import org.caps.columbo.domain.agent.service.session.SessionRolloverPolicy;
import org.caps.columbo.domain.agent.service.session.SessionTitlePolicy;
import org.caps.columbo.domain.agent.service.session.SessionTokenMeter;
import org.caps.columbo.domain.troubleshoot.adapter.port.IImageUnderstandingPort;
import org.caps.columbo.domain.troubleshoot.model.properties.ImageUnderstandingProperties;
import org.caps.columbo.types.enums.ResponseCode;
import org.caps.columbo.types.exception.AppException;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class ChatService implements IChatService {

    @Resource
    private DefaultArmoryFactory defaultArmoryFactory;

    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;

    /** 会话持久化端口：历史会话列表从库读（内存里重启即空；enabled=false 时为 no-op 实现） */
    @Resource
    private ISessionStorePort sessionStorePort;

    @Resource
    private org.caps.columbo.domain.agent.service.session.StreamingMailbox streamingMailbox;

    /** 对话消息预加工链（ai.agent.pipeline.chain.chat，未配置时原样通过） */
    @Resource
    private Pipelines pipelines;

    /** 装配服务：模型切换时对单个 agent 重装配 */
    @Resource
    private IArmoryService armoryService;

    /** 图片识别端口（多模态模型），可选：未启用/未配置时附图只透传不识别 */
    @Resource
    private IImageUnderstandingPort imageUnderstandingPort;

    @Resource
    private ImageUnderstandingProperties imageUnderstandingProperties;

    /** 会话 token 计量器：同步路径在当前线程绑定归属范围，流式路径走运行句柄 */
    @Resource
    private SessionTokenMeter sessionTokenMeter;

    /** 会话压缩：翻篇前把旧会话压缩成摘要结转到新上下文（失败降级普通翻篇） */
    @Resource
    SessionCompactionService sessionCompactionService;

    /** 翻篇链：老 sessionId -> 新 sessionId。前端持有的 sessionId 不变，后端解析到最新上下文（同包测试可注入校验） */
    final Map<String, String> sessionRollover = new ConcurrentHashMap<>();

    /** 单个底层会话事件数超过该值自动翻篇（换新上下文），防止上下文无限膨胀导致网关大请求被拒 */
    @Value("${ai.agent.session-event-limit:80}")
    private int sessionEventLimit;

    /** 单个底层会话事件内容总字符数超过该值也自动翻篇：超大单事件不增加事件数，事件数阈值拦不住 */
    @Value("${ai.agent.session-content-limit-chars:1000000}")
    private long sessionContentLimitChars;

    @Override
    public List<AiAgentConfigTableVO.Agent> queryAiAgentConfigList() {
        Map<String, AiAgentConfigTableVO> tables = aiAgentAutoConfigProperties.getTables();

        List<AiAgentConfigTableVO.Agent> agentList = new ArrayList<>();
        if (null != tables) {
            for (AiAgentConfigTableVO vo : tables.values()) {
                if (null != vo.getAgent()) {
                    agentList.add(vo.getAgent());
                }
            }
        }

        return agentList;
    }

    @Override
    public String createSession(String agentId, String userId) {
        AiAgentRegisterVO aiAgentRegisterVO = requireAgent(agentId);

        String appName = aiAgentRegisterVO.getAppName();
        Runner runner = aiAgentRegisterVO.getRunner();

        // 每次调用创建全新底层会话：前端"新对话"必须拿到干净上下文
        Session session = runner.sessionService().createSession(appName, userId)
                .blockingGet();
        return session.id();
    }

    @Override
    public List<String> handleMessage(String agentId, String userId, String message) {
        requireAgent(agentId);

        String sessionId = createSession(agentId, userId);

        return handleMessage(agentId, userId, sessionId, message);
    }

    @Override
    public List<String> handleMessage(String agentId, String userId, String sessionId, String message) {
        return handleMessage(agentId, userId, sessionId, message, null);
    }

    @Override
    public List<String> handleMessage(String agentId, String userId, String sessionId, String message, List<String> images) {
        AiAgentRegisterVO aiAgentRegisterVO = requireAgent(agentId);
        String appName = aiAgentRegisterVO.getAppName();
        Runner runner = aiAgentRegisterVO.getRunner();
        ResolvedSession resolved = resolveSession(runner, appName, userId, sessionId);

        Content userMsg = Content.fromParts(Part.fromText(enhanceMessage(resolved.applyRolloverNote(
                applyChatPipeline(agentId, userId, resolved.sessionId(), message)), images)));
        // 同步路径的用量归属：阻塞调用不换线程，MySpringAI 在同线程读取绑定范围
        sessionTokenMeter.bind(appName, userId, resolved.sessionId());
        try {
            Flowable<Event> events = runner.runAsync(userId, resolved.sessionId(), userMsg);
            List<String> outputs = new ArrayList<>();
            try {
                events.blockingForEach(event -> outputs.add(event.stringifyContent()));
            } catch (Exception e) {
                // 413 类上下文超长且零产出：紧急压缩翻篇换新上下文，重放本条消息（重试腿再撞即上抛）
                if (!outputs.isEmpty() || !ContextOverflowErrors.isContextOverflow(e)) {
                    throw e;
                }
                log.warn("同步对话上下文超长（{}），紧急压缩翻篇后重试本条消息", e.getMessage());
                ForcedRollover forced = forceRollover(runner, appName, userId, resolved.sessionId());
                runner.runAsync(userId, forced.sessionId(), userMsg)
                        .blockingForEach(event -> outputs.add(event.stringifyContent()));
            }
            return outputs;
        } finally {
            sessionTokenMeter.unbind();
        }
    }

    @Override
    public Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message) {
        return handleMessageStream(agentId, userId, sessionId, message, null);
    }

    @Override
    public Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message, List<String> images) {
        AiAgentRegisterVO aiAgentRegisterVO = requireAgent(agentId);
        String appName = aiAgentRegisterVO.getAppName();
        Runner runner = aiAgentRegisterVO.getRunner();
        ResolvedSession resolved = resolveSession(runner, appName, userId, sessionId);

        Content userMsg = Content.fromParts(Part.fromText(enhanceMessage(resolved.applyRolloverNote(
                applyChatPipeline(agentId, userId, resolved.sessionId(), message)), images)));
        // SSE 模式：BaseLlmFlow 以 streamingMode 调 MySpringAI.generateContent(req, true)，
        // 思考增量（reasoning_content）才能边生成边推送，前端渲染为实时思考面板
        RunConfig streaming = RunConfig.builder().streamingMode(RunConfig.StreamingMode.SSE).build();
        // 登记插入信箱运行句柄：对话进行中 POST /chat_insert 的新指令会在下一工具轮前注入
        streamingMailbox.beginRun(appName, userId, sessionId, resolved.sessionId());
        // 已向下游转发的偏移量：413 类上下文超长只在"零转发"时自动翻篇重试——
        // 已转发再重放会让前端看到重复内容（与 MySpringAI 流停滞安全重试同一语义）
        java.util.concurrent.atomic.AtomicInteger forwarded = new java.util.concurrent.atomic.AtomicInteger();
        return streamRun(runner, userId, sessionId, resolved.sessionId(), userMsg, streaming, forwarded)
                .onErrorResumeNext(error -> retryOnContextOverflow(
                        runner, appName, userId, sessionId, resolved, userMsg, streaming, forwarded, error));
    }

    /** 单次流式运行：转发计数 + 结束时按（前端会话, 本次运行会话）清理信箱 */
    private Flowable<Event> streamRun(Runner runner, String userId, String frontSessionId, String runtimeSessionId,
                                      Content userMsg, RunConfig streaming,
                                      java.util.concurrent.atomic.AtomicInteger forwarded) {
        return runner.runAsync(userId, runtimeSessionId, userMsg, streaming)
                .doOnNext(event -> forwarded.incrementAndGet())
                .doFinally(() -> streamingMailbox.endRun(frontSessionId, runtimeSessionId));
    }

    /** 413 类上下文超长的紧急自救：零产出才触发（防重复内容），压缩翻篇换新上下文重放；重试腿再撞超长原样上抛防循环 */
    private Flowable<Event> retryOnContextOverflow(Runner runner, String appName, String userId, String sessionId,
                                                   ResolvedSession resolved, Content userMsg, RunConfig streaming,
                                                   java.util.concurrent.atomic.AtomicInteger forwarded, Throwable error) {
        if (forwarded.get() > 0 || !ContextOverflowErrors.isContextOverflow(error)) {
            return Flowable.error(error);
        }
        log.warn("对话上下文超长（{}）且本轮零产出，紧急压缩翻篇后重试本条消息", error.getMessage());
        ForcedRollover forced = forceRollover(runner, appName, userId, resolved.sessionId());
        streamingMailbox.beginRun(appName, userId, sessionId, forced.sessionId());
        return streamRun(runner, userId, sessionId, forced.sessionId(), userMsg, streaming, forwarded);
    }

    /**
     * 对话消息预加工链（链名 chat）：处理器可改写消息（脱敏/审计/消息增强），返回 false 拦截本次对话。
     * 链未配置时原样通过，对主流程零侵入。
     */
    private String applyChatPipeline(String agentId, String userId, String sessionId, String message) {
        ChatPipelineContext ctx = ChatPipelineContext.builder()
                .agentId(agentId).userId(userId).sessionId(sessionId).message(message)
                .build();
        if (!pipelines.doExecute("chat", ctx)) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "消息已被预处理链拦截");
        }
        return ctx.getMessage();
    }

    /**
     * 有附图时先调多模态模型提取文字，把【附图识别结果】拼在问题后进入对话；
     * 识别失败不阻断对话，降级为提示文字（主流程仍可用纯文本排查）。
     */
    private String enhanceMessage(String message, List<String> images) {
        if (images == null || images.isEmpty() || !imageUnderstandingProperties.isEnabled()) {
            return message;
        }
        String question = message == null ? "" : message;
        try {
            // 只传图不传问题：识别模型做纯提取，"为什么"类问题会诱发其长思考链
            // 烧满 token 产出空正文（分析由主对话模型负责，问题本就拼在识别结果前）
            String extracted = imageUnderstandingPort.understand(images);
            log.info("附图识别完成，提取 {} 字符", extracted == null ? 0 : extracted.length());
            return question + "\n\n【附图识别结果】\n" + extracted;
        } catch (Exception e) {
            log.warn("附图识别失败，降级为纯文本消息: {}", e.getMessage());
            return question + "\n\n【附图识别结果】图片识别失败（" + e.getMessage()
                    + "），请基于文字描述排查，并提示用户补充图片中的报错信息。";
        }
    }

    /**
     * 解析后的会话：实际可用的底层 sessionId + 是否发生了翻篇 + 翻篇是否携带了前情摘要。
     * 摘要结转（压缩成功）时提示语不同：上下文没丢，关键线索已保留，无需重新查询。
     */
    private record ResolvedSession(String sessionId, boolean rolledOver, boolean summarized) {
        String applyRolloverNote(String message) {
            if (!rolledOver) {
                return message;
            }
            if (summarized) {
                return "（系统提示：此前对话上下文过长，已自动压缩并结转到下方【前情摘要】，"
                        + "关键结论与线索已保留，可在其基础上继续排查。）\n\n" + message;
            }
            return "（系统提示：此前对话上下文过长，已自动开启新的分析上下文，之前查到的细节如有需要请重新查询。）\n\n" + message;
        }
    }

    /**
     * 解析会话到最新底层上下文：沿翻篇链走到最新；若其事件数超限，再自动翻一篇并登记映射。
     * 前端 sessionId 始终不变，翻篇对前端透明；检查失败（会话不存在等）沿用原值，不阻断对话。
     */
    private ResolvedSession resolveSession(Runner runner, String appName, String userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return new ResolvedSession(sessionId, false, false);
        }
        String latest = sessionId;
        for (int i = 0; i < 50; i++) {
            String next = sessionRollover.get(latest);
            if (next == null) {
                break;
            }
            latest = next;
        }
        try {
            Session session = runner.sessionService()
                    .getSession(appName, userId, latest, java.util.Optional.empty()).blockingGet();
            int eventCount = session == null || session.events() == null ? 0 : session.events().size();
            long contentChars = 0;
            if (session != null && session.events() != null) {
                for (Event event : session.events()) {
                    String content = event.stringifyContent();
                    if (content != null) {
                        contentChars += content.length();
                    }
                }
            }
            if (!SessionRolloverPolicy.needRollover(eventCount, sessionEventLimit, contentChars, sessionContentLimitChars)) {
                return new ResolvedSession(latest, false, false);
            }
            // 压缩优先、翻篇兜底（forceRollover 内部处理并发合并与压缩降级）
            ForcedRollover forced = forceRollover(runner.sessionService(), appName, userId, latest, session);
            log.info("会话上下文过长（{} 个事件/上限{}，{} 字符/上限{}），自动翻篇 -> {}{}",
                    eventCount, sessionEventLimit, contentChars, sessionContentLimitChars,
                    forced.sessionId(), forced.summarized() ? "（已携带前情摘要）" : "");
            return new ResolvedSession(forced.sessionId(), true, forced.summarized());
        } catch (Exception e) {
            log.warn("会话翻篇检查失败，沿用原会话: {}", e.getMessage());
            return new ResolvedSession(latest, false, false);
        }
    }

    /** 强制翻篇结果：新底层 sessionId + 是否携带了前情压缩摘要 */
    record ForcedRollover(String sessionId, boolean summarized) {
    }

    /** 强制翻篇（413 紧急恢复入口）：立即换新底层上下文，不走阈值判断；失败抛出（上下文已撑爆，无"沿用原值"可退） */
    ForcedRollover forceRollover(Runner runner, String appName, String userId, String latest) {
        return forceRollover(runner.sessionService(), appName, userId, latest, null);
    }

    /** 会话服务直入的强制翻篇（测试/已知会话对象时复用） */
    ForcedRollover forceRollover(com.google.adk.sessions.BaseSessionService sessions,
                                 String appName, String userId, String latest) {
        return forceRollover(sessions, appName, userId, latest, null);
    }

    /**
     * 强制翻篇：登记翻篇链映射 + 把旧会话压缩成摘要结转（ADK 会话 append-only，
     * "翻篇+摘要结转"与就地压缩上下文效果等价，且保留完整审计轨迹；摘要失败降级普通翻篇）。
     * 并发翻篇只认先到者，后来者复用先到会话，避免多余孤儿会话进入历史列表。
     */
    ForcedRollover forceRollover(com.google.adk.sessions.BaseSessionService sessions,
                                 String appName, String userId, String latest, Session known) {
        try {
            Session old = known != null ? known
                    : sessions.getSession(appName, userId, latest, java.util.Optional.empty()).blockingGet();
            Session fresh = sessions.createSession(appName, userId).blockingGet();
            String winner = sessionRollover.putIfAbsent(latest, fresh.id());
            if (winner != null) {
                log.info("并发翻篇合并 {} -> {}（复用先到会话）", latest, winner);
                return new ForcedRollover(winner, false);
            }
            boolean summarized = false;
            try {
                String summary = sessionCompactionService.summarize(old == null ? null : old.events());
                if (summary != null && !summary.isBlank()) {
                    appendCompactionSummary(sessions, fresh, summary);
                    summarized = true;
                }
            } catch (Exception e) {
                log.warn("会话压缩失败，降级普通翻篇: {}", e.getMessage());
            }
            log.info("会话已强制翻篇 {} -> {}{}（413 紧急压缩/上下文超限）",
                    latest, fresh.id(), summarized ? "（已携带前情摘要）" : "");
            return new ForcedRollover(fresh.id(), summarized);
        } catch (Exception e) {
            throw new IllegalStateException("紧急翻篇失败: " + e.getMessage(), e);
        }
    }

    /** 把前情摘要作为 user 事件追加进新会话（经 PersistentSessionService 自动落库，重启不丢） */
    private void appendCompactionSummary(com.google.adk.sessions.BaseSessionService sessions, Session fresh, String summary) {
        String eventId = SerialNoGenerator.generate("E");
        Event summaryEvent = Event.builder()
                .id(eventId)
                .author("user")
                .content(Content.fromParts(Part.fromText("【前情摘要】（上一段对话的自动压缩，供继续排查参考）\n\n" + summary)))
                .build();
        sessions.appendEvent(fresh, summaryEvent).blockingGet();
    }

    @Override
    public List<String> handleMessage(ChatCommandEntity chatCommandEntity) {
        AiAgentRegisterVO aiAgentRegisterVO = requireAgent(chatCommandEntity.getAgentId());

        List<Part> parts = new ArrayList<>();

        List<ChatCommandEntity.Content.Text> texts = chatCommandEntity.getTexts();
        if (null != texts && !texts.isEmpty()) {
            for (ChatCommandEntity.Content.Text text : texts) {
                parts.add(Part.fromText(text.getMessage()));
            }
        }

        List<ChatCommandEntity.Content.File> files = chatCommandEntity.getFiles();
        if (null != files && !files.isEmpty()) {
            for (ChatCommandEntity.Content.File file : files) {
                parts.add(Part.fromUri(file.getFileUri(), file.getMimeType()));
            }
        }

        List<ChatCommandEntity.Content.InlineData> inlineDatas = chatCommandEntity.getInlineDatas();
        if (null != inlineDatas && !inlineDatas.isEmpty()) {
            for (ChatCommandEntity.Content.InlineData inlineData : inlineDatas) {
                parts.add(Part.fromBytes(inlineData.getBytes(), inlineData.getMimeType()));
            }
        }

        Content content = Content.builder().role("user").parts(parts).build();

        Runner runner = aiAgentRegisterVO.getRunner();

        Flowable<Event> events = runner.runAsync(chatCommandEntity.getUserId(), chatCommandEntity.getSessionId(), content);

        List<String> outputs = new ArrayList<>();
        events.blockingForEach(event -> outputs.add(event.stringifyContent()));

        return outputs;
    }

    @Override
    public List<ChatSessionVO> queryChatSessions(String userId) {
        // appName -> agent 配置反查（装配配置里 appName 与 agent 一一对应；已下线的 agent 兜底用 appName 展示）
        Map<String, AiAgentConfigTableVO.Agent> agentByAppName = new HashMap<>();
        Map<String, AiAgentConfigTableVO> tables = aiAgentAutoConfigProperties.getTables();
        if (tables != null) {
            for (AiAgentConfigTableVO vo : tables.values()) {
                if (vo.getAppName() != null && vo.getAgent() != null) {
                    agentByAppName.put(vo.getAppName(), vo.getAgent());
                }
            }
        }
        return sessionStorePort.loadSessions(userId).stream().map(record -> {
            AiAgentConfigTableVO.Agent agent = agentByAppName.get(record.getAppName());
            return ChatSessionVO.builder()
                    .sessionId(record.getSessionId())
                    .agentId(agent != null ? agent.getAgentId() : record.getAppName())
                    .agentName(agent != null ? agent.getAgentName() : record.getAppName())
                    .title(SessionTitlePolicy.displayTitle(record))
                    .space(record.getSpace())
                    .updatedAt(record.getUpdatedAt())
                    .build();
        }).toList();
    }

    @Override
    public List<ChatMessageVO> queryChatMessages(String agentId, String userId, String sessionId) {
        AiAgentRegisterVO aiAgentRegisterVO = requireAgent(agentId);
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        // 走 getSession：未命中会自动从库重放恢复（与续聊同一路径）
        Session session = aiAgentRegisterVO.getRunner().sessionService()
                .getSession(aiAgentRegisterVO.getAppName(), userId, sessionId, java.util.Optional.empty())
                .blockingGet();
        if (session == null || session.events() == null) {
            return List.of();
        }
        // 连续的模型事件（思考/耗时标记/正文是独立 Event）合并为一条消息，
        // 复刻流式返回的完整文本形态，前端思考解析逻辑可直接复用
        List<ChatMessageVO> messages = new ArrayList<>();
        for (Event event : session.events()) {
            String content = event.stringifyContent();
            if (content == null || content.isBlank()) {
                continue;
            }
            if ("user".equals(event.author())) {
                messages.add(ChatMessageVO.builder().role("user").content(content).build());
            } else if (!messages.isEmpty() && "assistant".equals(messages.get(messages.size() - 1).getRole())) {
                ChatMessageVO last = messages.get(messages.size() - 1);
                last.setContent(last.getContent() + "\n" + content);
            } else {
                messages.add(ChatMessageVO.builder().role("assistant").content(content).build());
            }
        }
        return messages;
    }

    @Override
    public void deleteChatSession(String agentId, String userId, String sessionId) {
        AiAgentRegisterVO aiAgentRegisterVO = requireAgent(agentId);
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        // PersistentSessionService.deleteSession：内存 + 库一起删
        aiAgentRegisterVO.getRunner().sessionService()
                .deleteSession(aiAgentRegisterVO.getAppName(), userId, sessionId).blockingAwait();
        purgeRolloverChain(sessionId);
    }

    /** 删除会话后清掉翻篇链上与之相关的映射，避免残留映射把新对话路由到不存在的会话上 */
    private void purgeRolloverChain(String sessionId) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Map.Entry<String, String> entry : sessionRollover.entrySet()) {
                if (entry.getKey().equals(sessionId) || entry.getValue().equals(sessionId)) {
                    sessionRollover.remove(entry.getKey());
                    changed = true;
                    break;
                }
            }
        }
    }

    @Override
    public void renameChatSession(String agentId, String userId, String sessionId, String title) {
        AiAgentRegisterVO aiAgentRegisterVO = requireAgent(agentId);
        if (sessionId == null || sessionId.isBlank()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "sessionId 不能为空");
        }
        String normalized = SessionTitle.of(title).value();
        // 持久层按 userId+sessionId 定位（孤儿会话/跨 agent 同样生效）；内存侧无标题缓存不用改
        boolean updated = sessionStorePort.renameSession(
                aiAgentRegisterVO.getAppName(), userId, sessionId, normalized);
        if (!updated) {
            throw new AppException(ResponseCode.B001.getCode(), "会话不存在或未保存到持久层，无法重命名");
        }
    }

    @Override
    public void moveChatSession(String agentId, String userId, String sessionId, String space) {
        requireAgent(agentId);
        if (sessionId == null || sessionId.isBlank()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "sessionId 不能为空");
        }
        // ArchiveSpace.of：空串=归位未归档，其余校验 1-32 字
        String normalized = ArchiveSpace.of(space).value();
        boolean updated = sessionStorePort.moveSession(agentId, userId, sessionId, normalized);
        if (!updated) {
            throw new AppException(ResponseCode.B001.getCode(), "会话不存在或未保存到持久层，无法移动");
        }
    }

    @Override
    public TokenUsageVO querySessionTokenUsage(String agentId, String userId, String sessionId) {
        AiAgentRegisterVO aiAgentRegisterVO = requireAgent(agentId);
        if (sessionId == null || sessionId.isBlank()) {
            return TokenUsageVO.builder().build();
        }
        return sessionStorePort.loadTokenUsage(aiAgentRegisterVO.getAppName(), sessionId);
    }

    private AiAgentRegisterVO requireAgent(String agentId) {
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);
        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.NOT_FOUND_AGENT);
        }
        return aiAgentRegisterVO;
    }

    @Override
    public List<AgentModelVO> queryAgentModels() {
        List<AgentModelVO> result = new ArrayList<>();
        Map<String, AiAgentConfigTableVO> tables = aiAgentAutoConfigProperties.getTables();
        if (tables == null) {
            return result;
        }
        for (AiAgentConfigTableVO vo : tables.values()) {
            if (vo.getAgent() == null || vo.getModule() == null || vo.getModule().getChatModel() == null) {
                continue;
            }
            AiAgentConfigTableVO.Module.ChatModel chatModel = vo.getModule().getChatModel();
            List<String> models = chatModel.getModels() != null && !chatModel.getModels().isEmpty()
                    ? chatModel.getModels()
                    : chatModel.getModel() == null ? List.of() : List.of(chatModel.getModel());
            result.add(AgentModelVO.builder()
                    .agentId(vo.getAgent().getAgentId())
                    .agentName(vo.getAgent().getAgentName())
                    .models(models)
                    .current(chatModel.getModel())
                    .build());
        }
        return result;
    }

    @Override
    public synchronized void switchAgentModel(String agentId, String model) {
        if (model == null || model.isBlank()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "model 不能为空");
        }
        Map<String, AiAgentConfigTableVO> tables = aiAgentAutoConfigProperties.getTables();
        AiAgentConfigTableVO target = null;
        if (tables != null) {
            for (AiAgentConfigTableVO vo : tables.values()) {
                if (vo.getAgent() != null && agentId.equals(vo.getAgent().getAgentId())) {
                    target = vo;
                    break;
                }
            }
        }
        if (target == null || target.getModule() == null || target.getModule().getChatModel() == null) {
            throw new AppException(ResponseCode.NOT_FOUND_AGENT);
        }
        AiAgentConfigTableVO.Module.ChatModel chatModel = target.getModule().getChatModel();
        List<String> pool = chatModel.getModels() != null && !chatModel.getModels().isEmpty()
                ? chatModel.getModels()
                : chatModel.getModel() == null ? List.of() : List.of(chatModel.getModel());
        if (!pool.contains(model)) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(),
                    "模型 " + model + " 不在该智能体的可用列表内: " + pool);
        }
        String previous = chatModel.getModel();
        chatModel.setModel(model);
        try {
            armoryService.reArmAgent(target);
            log.info("智能体模型已切换 {}: {} -> {}", agentId, previous, model);
        } catch (Exception e) {
            // 重装配失败回滚模型值，保持配置与注册表一致
            chatModel.setModel(previous);
            throw new AppException(ResponseCode.UN_ERROR.getCode(), "模型切换失败（重装配异常）: " + e.getMessage());
        }
    }

}
