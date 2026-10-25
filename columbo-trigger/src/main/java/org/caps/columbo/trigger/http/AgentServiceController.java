package org.caps.columbo.trigger.http;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.caps.columbo.api.IAgentService;
import org.caps.columbo.api.dto.AiAgentConfigResponseDTO;
import org.caps.columbo.api.dto.AgentModelResponseDTO;
import org.caps.columbo.api.dto.ChatMessageResponseDTO;
import org.caps.columbo.api.dto.ChatRequestDTO;
import org.caps.columbo.api.dto.ChatResponseDTO;
import org.caps.columbo.api.dto.ChatSessionResponseDTO;
import org.caps.columbo.api.dto.CreateSessionRequestDTO;
import org.caps.columbo.api.dto.CreateSessionResponseDTO;
import org.caps.columbo.api.dto.SessionTokenUsageResponseDTO;
import org.caps.columbo.api.dto.SwitchModelRequestDTO;
import org.caps.columbo.api.response.Response;
import org.caps.columbo.domain.agent.service.IChatService;
import org.caps.columbo.domain.agent.service.chat.StreamingSessionAppService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.util.StringUtils;

import io.reactivex.rxjava3.disposables.Disposable;

import jakarta.validation.Valid;
import org.caps.columbo.trigger.filter.TraceIdFilter;
import org.slf4j.MDC;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 智能体服务接口。异常统一由 {@link GlobalExceptionHandler} 处理，业务方法只写正常流程。
 *
 * @author caps
 * 2026/1/20 08:23
 */
@Slf4j
@Tag(name = "智能体服务", description = "智能体列表、会话创建与对话（含流式）")
@RestController
@RequestMapping("/api/v1/")
public class AgentServiceController implements IAgentService {

    @Resource
    private IChatService chatService;

    /** 会话归属：鉴权开启时一律取登录工号，前端传参被覆盖（数据隔离） */
    @Resource
    private CurrentUser currentUser;

    @Resource
    private org.caps.columbo.domain.agent.service.session.StreamingMailbox streamingMailbox;

    @Operation(summary = "查询会话 token 用量", description = "按会话累计的模型调用成本（输入/输出/缓存命中/调用次数）")
    @GetMapping("query_session_token_usage")
    @Override
    public Response<SessionTokenUsageResponseDTO> querySessionTokenUsage(@RequestParam String agentId,
                                                                         @RequestParam String userId,
                                                                         @RequestParam String sessionId) {
        userId = currentUser.resolve(userId);
        org.caps.columbo.domain.agent.model.valobj.TokenUsageVO usage =
                chatService.querySessionTokenUsage(agentId, userId, sessionId);
        SessionTokenUsageResponseDTO dto = new SessionTokenUsageResponseDTO();
        dto.setInputTokens(usage.getInputTokens());
        dto.setOutputTokens(usage.getOutputTokens());
        dto.setCachedTokens(usage.getCachedTokens());
        dto.setTotalTokens(usage.getTotalTokens());
        dto.setLlmCalls(usage.getLlmCalls());
        return Response.success(dto);
    }

    @Operation(summary = "查询可用智能体列表")
    @GetMapping("query_ai_agent_config_list")
    @Override
    public Response<List<AiAgentConfigResponseDTO>> queryAiAgentConfigList() {
        List<AiAgentConfigResponseDTO> responseDTOS = chatService.queryAiAgentConfigList().stream().map(agentConfig -> {
            AiAgentConfigResponseDTO responseDTO = new AiAgentConfigResponseDTO();
            responseDTO.setAgentId(agentConfig.getAgentId());
            responseDTO.setAgentName(agentConfig.getAgentName());
            responseDTO.setAgentDesc(agentConfig.getAgentDesc());
            return responseDTO;
        }).collect(Collectors.toList());

        return Response.success(responseDTOS);
    }

    @Operation(summary = "创建会话", description = "按智能体ID + 用户ID新建一次会话")
    @PostMapping("create_session")
    @Override
    public Response<CreateSessionResponseDTO> createSession(@Valid @RequestBody CreateSessionRequestDTO requestDTO) {
        requestDTO.setUserId(currentUser.resolve(requestDTO.getUserId()));
        String sessionId = chatService.createSession(requestDTO.getAgentId(), requestDTO.getUserId());

        CreateSessionResponseDTO responseDTO = new CreateSessionResponseDTO();
        responseDTO.setSessionId(sessionId);
        return Response.success(responseDTO);
    }

    @Operation(summary = "智能体对话", description = "sessionId 为空时服务端自动创建会话")
    @PostMapping("chat")
    @Override
    public Response<ChatResponseDTO> chat(@Valid @RequestBody ChatRequestDTO requestDTO) {
        requestDTO.setUserId(currentUser.resolve(requestDTO.getUserId()));
        log.info("智能体对话 agentId:{} userId:{}", requestDTO.getAgentId(), requestDTO.getUserId());

        String sessionId = requestDTO.getSessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = chatService.createSession(requestDTO.getAgentId(), requestDTO.getUserId());
        }

        List<String> messages = chatService.handleMessage(requestDTO.getAgentId(), requestDTO.getUserId(), sessionId, requestDTO.getMessage());

        ChatResponseDTO chatResponse = parseChatResponse(messages);
        // 回传 sessionId：外部调用方（workbuddy skill / 脚本）持有它才能续聊追问
        chatResponse.setSessionId(sessionId);
        return Response.success(chatResponse);
    }

    /**
     * SSE 异步超时：须大于 agent 单轮最坏耗时（MySpringAI 工具循环守护上限 40 轮 × 日志单查 4-16s
     * ≈ 数十分钟量级）。设小了会在长排查中途掐断前端连接，而后台仍在跑（2026-09-18 线上 10 分钟
     * 超时误杀 13.7 分钟的排查）；与 chat_events 保持一致。
     */
    private static final long EMITTER_TIMEOUT_MS = 30 * 60 * 1000L;

    /** 流式会话应用服务：心跳与收尾编排下沉（chat_stream / chat_events 共用语义） */
    @Resource
    private StreamingSessionAppService streamingSessionAppService;

    @Operation(summary = "智能体流式对话", description = "SSE 风格流式返回；sessionId 为空时服务端自动创建会话")
    @PostMapping("chat_stream")
    @Override
    public ResponseBodyEmitter chatStream(@Valid @RequestBody ChatRequestDTO requestDTO) {
        requestDTO.setUserId(currentUser.resolve(requestDTO.getUserId()));
        log.info("流式对话 agentId:{} userId:{} sessionId:{}", requestDTO.getAgentId(), requestDTO.getUserId(), requestDTO.getSessionId());

        String sessionId = requestDTO.getSessionId();
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = chatService.createSession(requestDTO.getAgentId(), requestDTO.getUserId());
        }
        final String sid = sessionId;

        ResponseBodyEmitter emitter = new ResponseBodyEmitter(EMITTER_TIMEOUT_MS);
        // 订阅回调运行在其它线程，MDC（ThreadLocal）带不过去，这里显式携带 traceId 便于排障
        String traceId = MDC.get(TraceIdFilter.TRACE_ID);
        java.util.concurrent.atomic.AtomicReference<Disposable> disposableRef = new java.util.concurrent.atomic.AtomicReference<>();
        // 心跳与收尾编排委托应用服务；先注册收尾再建流，建流同步失败也不泄漏周期任务
        StreamingSessionAppService.HeartbeatHandle heartbeat =
                streamingSessionAppService.startHeartbeat(
                        () -> {
                            try {
                                emitter.send(":ping\n\n");
                            } catch (IOException ignored) {
                                // 连接已断：由收尾回调清理，心跳静默
                            }
                        },
                        () -> {
                            Disposable d = disposableRef.get();
                            if (d != null && !d.isDisposed()) {
                                d.dispose();
                            }
                        });
        emitter.onCompletion(heartbeat::end);
        emitter.onTimeout(heartbeat::end);
        emitter.onError(t -> heartbeat.end());
        try {
            Disposable disposable = chatService.handleMessageStream(requestDTO.getAgentId(), requestDTO.getUserId(), sid, requestDTO.getMessage())
                    .subscribe(
                            event -> {
                                try {
                                    emitter.send(event.stringifyContent());
                                } catch (Exception e) {
                                    log.error("流式对话发送失败 traceId:{}", traceId, e);
                                    emitter.completeWithError(e);
                                }
                            },
                            e -> {
                                log.error("流式对话异常 traceId:{}", traceId, e);
                                emitter.completeWithError(e);
                            },
                            emitter::complete
                    );
            disposableRef.set(disposable);
        } catch (Exception e) {
            // 建流同步失败（智能体不存在/管线拦截等）：收尾心跳与 emitter，异常继续抛给全局处理器
            heartbeat.end();
            throw e;
        }
        return emitter;
    }

    /** 插入消息请求体 */
    public record ChatInsertRequest(@jakarta.validation.constraints.NotBlank String sessionId,
                                    @jakarta.validation.constraints.NotBlank String message) {
    }

    @Operation(summary = "插入消息到正在生成的对话", description = "对话进行中插入新指令：模型在下一个工具调用轮前可见并据此调整；返回 false 表示会话当前无进行中的流（前端应转为排队）")
    @PostMapping("chat_insert")
    public Response<Boolean> chatInsert(@Valid @RequestBody ChatInsertRequest request) {
        boolean accepted = streamingMailbox.insert(request.sessionId().trim(), request.message().trim());
        log.info("插入消息 sessionId={} accepted={}", request.sessionId(), accepted);
        return Response.success(accepted);
    }

    /** 停止流式对话请求体 */
    public record ChatStopRequest(@jakarta.validation.constraints.NotBlank String sessionId) {
    }

    @Operation(summary = "停止正在生成的对话", description = "置取消标志：模型流在下一个增量/轮边界中止、不再执行工具或续跑新一轮，已生成内容按阶段性结果落会话；返回 false 表示会话当前无进行中的流")
    @PostMapping("chat_stop")
    public Response<Boolean> chatStop(@Valid @RequestBody ChatStopRequest request) {
        boolean accepted = streamingMailbox.cancel(request.sessionId().trim());
        log.info("停止流式对话 sessionId={} accepted={}", request.sessionId(), accepted);
        return Response.success(accepted);
    }

    @Operation(summary = "查询用户历史会话列表", description = "按最后活跃倒序；会话持久化关闭时返回空列表")
    @GetMapping("query_chat_sessions")
    @Override
    public Response<List<ChatSessionResponseDTO>> queryChatSessions(@RequestParam String userId) {
        userId = currentUser.resolve(userId);
        List<ChatSessionResponseDTO> responseDTOS = chatService.queryChatSessions(userId).stream().map(vo -> {
            ChatSessionResponseDTO dto = new ChatSessionResponseDTO();
            dto.setSessionId(vo.getSessionId());
            dto.setAgentId(vo.getAgentId());
            dto.setAgentName(vo.getAgentName());
            dto.setTitle(vo.getTitle());
            dto.setSpace(vo.getSpace());
            dto.setUpdatedAt(vo.getUpdatedAt());
            return dto;
        }).collect(Collectors.toList());
        return Response.success(responseDTOS);
    }

    @Operation(summary = "查询会话历史消息", description = "一轮回复的思考/正文已合并为一条消息，与流式形态一致")
    @GetMapping("query_chat_messages")
    @Override
    public Response<List<ChatMessageResponseDTO>> queryChatMessages(@RequestParam String agentId,
                                                                    @RequestParam String userId,
                                                                    @RequestParam String sessionId) {
        userId = currentUser.resolve(userId);
        List<ChatMessageResponseDTO> responseDTOS = chatService.queryChatMessages(agentId, userId, sessionId).stream().map(vo -> {
            ChatMessageResponseDTO dto = new ChatMessageResponseDTO();
            dto.setRole(vo.getRole());
            dto.setContent(vo.getContent());
            return dto;
        }).collect(Collectors.toList());
        return Response.success(responseDTOS);
    }

    @Operation(summary = "删除会话", description = "内存与持久层一起删除")
    @PostMapping("delete_session")
    @Override
    public Response<Boolean> deleteSession(@RequestParam String agentId,
                                           @RequestParam String userId,
                                           @RequestParam String sessionId) {
        userId = currentUser.resolve(userId);
        chatService.deleteChatSession(agentId, userId, sessionId);
        return Response.success(true);
    }

    @Operation(summary = "重命名会话", description = "自定义标题（1-100字），覆盖默认的首条用户消息标题")
    @PostMapping("rename_session")
    @Override
    public Response<Boolean> renameSession(@RequestParam String agentId,
                                           @RequestParam String userId,
                                           @RequestParam String sessionId,
                                           @RequestParam String title) {
        userId = currentUser.resolve(userId);
        chatService.renameChatSession(agentId, userId, sessionId, title);
        return Response.success(true);
    }

    @Operation(summary = "移动会话到归档空间", description = "拖拽归档；space 传空串归位到未归档")
    @PostMapping("move_session")
    @Override
    public Response<Boolean> moveSession(@RequestParam String agentId,
                                         @RequestParam String userId,
                                         @RequestParam String sessionId,
                                         @RequestParam String space) {
        userId = currentUser.resolve(userId);
        chatService.moveChatSession(agentId, userId, sessionId, space);
        return Response.success(true);
    }

    @Operation(summary = "查询智能体可用模型", description = "chat-model.models 配置多个模型后支持动态切换")
    @GetMapping("query_agent_models")
    @Override
    public Response<List<AgentModelResponseDTO>> queryAgentModels() {
        List<AgentModelResponseDTO> responseDTOS = chatService.queryAgentModels().stream().map(vo -> {
            AgentModelResponseDTO dto = new AgentModelResponseDTO();
            dto.setAgentId(vo.getAgentId());
            dto.setAgentName(vo.getAgentName());
            dto.setModels(vo.getModels());
            dto.setCurrent(vo.getCurrent());
            return dto;
        }).collect(Collectors.toList());
        return Response.success(responseDTOS);
    }

    @Operation(summary = "切换智能体模型", description = "运行时单 agent 重装配，历史会话保留；重启后回到 yml 默认模型")
    @PostMapping("switch_agent_model")
    @Override
    public Response<Boolean> switchAgentModel(@Valid @RequestBody SwitchModelRequestDTO requestDTO) {
        log.info("切换智能体模型 agentId:{} model:{}", requestDTO.getAgentId(), requestDTO.getModel());
        chatService.switchAgentModel(requestDTO.getAgentId(), requestDTO.getModel());
        return Response.success(true);
    }

    /** 结构化结果解析：宽松模式（多余字段忽略，解析失败由调用方兜底全文拼接） */
    private static final ObjectMapper CHAT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 思考块剥离（与 ChatEventsController 对齐）：脚本/外部 agent 消费 /chat 时无需渲染思考面板 */
    private static final java.util.regex.Pattern THINK_BLOCK =
            java.util.regex.Pattern.compile(
                    "⟦think⟧[\\s\\S]*?⟦/think⟧(?:⟦thinktime⟧\\d+⟦/thinktime⟧)?|⟦thinktime⟧\\d+⟦/thinktime⟧");

    /** 取最后一条输出尝试解析为结构化结果；解析失败或解析结果无内容则退回全文拼接 */
    private ChatResponseDTO parseChatResponse(List<String> messages) {
        String result = messages.stream().reduce((first, second) -> second).orElse("");
        try {
            ChatResponseDTO parsed = CHAT_MAPPER.readValue(result, ChatResponseDTO.class);
            if (null != parsed && StringUtils.hasText(parsed.getContent())) {
                if (null == parsed.getType()) {
                    parsed.setType("user");
                }
                parsed.setContent(THINK_BLOCK.matcher(parsed.getContent()).replaceAll("").trim());
                return parsed;
            }
        } catch (Exception ignored) {
        }
        ChatResponseDTO responseDTO = new ChatResponseDTO();
        responseDTO.setType("user");
        // 兜底路径同样剥思考标记：裸文本事件不是 JSON，大多走的是这条路
        responseDTO.setContent(THINK_BLOCK.matcher(String.join("\n", messages)).replaceAll("").trim());
        return responseDTO;
    }

}
