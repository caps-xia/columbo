package org.caps.columbo.infrastructure.adapter.port;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.caps.columbo.domain.agent.adapter.port.ISessionSummarizePort;
import org.caps.columbo.domain.agent.model.valobj.properties.SessionCompactionProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

/**
 * 会话摘要端口实现：调用 OpenAI 兼容 chat completions（文本压缩，小模型即可）。
 * 提示词由 domain 侧传入（摘要 SOP 属于领域知识），本类只负责协议调用与响应解析。
 *
 * @author caps
 */
@Slf4j
@Component
public class SessionSummarizePortImpl implements ISessionSummarizePort {

    /**
     * 限幅思考：摘要不需要推理链（与图片识别同款，oneai 网关 low 档关闭思考，
     * 默认档实测会烧满 token 拖慢翻篇路径）
     */
    private static final String REASONING_EFFORT = "low";

    /** 建立网关连接的超时（读超时由 timeout-ms 控制） */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    @Resource
    private SessionCompactionProperties properties;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpClient httpClient;

    @PostConstruct
    public void init() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    @Override
    public String summarize(String systemPrompt, String userContent) {
        if (!StringUtils.hasText(properties.getBaseUrl()) || !StringUtils.hasText(properties.getModel())) {
            throw new IllegalStateException("会话压缩未配置 base-url/model（ai.agent.session-compaction）");
        }
        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", properties.getModel());
            requestBody.put("temperature", 0.2);
            requestBody.put("max_tokens", 3072);
            requestBody.put("reasoning_effort", REASONING_EFFORT);
            ArrayNode messages = requestBody.putArray("messages");
            ObjectNode system = messages.addObject();
            system.put("role", "system");
            system.put("content", systemPrompt);
            ObjectNode user = messages.addObject();
            user.put("role", "user");
            user.put("content", userContent);

            String url = joinUrl(properties.getBaseUrl(), properties.getCompletionsPath());
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(properties.getTimeoutMs() == null ? 60000 : properties.getTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("会话压缩网关返回 {} : {}", response.statusCode(), abbreviate(response.body()));
                throw new IllegalStateException("会话压缩网关返回 HTTP " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode text = root.path("choices").path(0).path("message").path("content");
            if (!text.isTextual() || text.asText().isBlank()) {
                throw new IllegalStateException("会话压缩返回内容为空");
            }
            return text.asText().strip();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.warn("会话压缩调用失败: {}", e.getMessage());
            throw new IllegalStateException("会话压缩调用失败: " + e.getMessage(), e);
        }
    }

    private static String joinUrl(String baseUrl, String path) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String suffix = path == null ? "v1/chat/completions" : (path.startsWith("/") ? path.substring(1) : path);
        return base + "/" + suffix;
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }
}
