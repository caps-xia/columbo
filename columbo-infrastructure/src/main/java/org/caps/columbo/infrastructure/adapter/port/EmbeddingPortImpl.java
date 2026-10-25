package org.caps.columbo.infrastructure.adapter.port;

import org.caps.columbo.domain.agent.adapter.port.IEmbeddingPort;
import org.caps.columbo.domain.troubleshoot.model.properties.KnowledgeProperties;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 嵌入网关端口实现（提炼自 VectorKnowledgeService 的 HTTP 调用，COLA 归位：
 * 网关客户端属基础设施，领域只经 {@link IEmbeddingPort} 拿向量）。
 * 网关地址/模型/密钥取自 troubleshoot.knowledge.* 配置。
 *
 * @author caps
 */
@Component
public class EmbeddingPortImpl implements IEmbeddingPort {

    @Resource
    private KnowledgeProperties properties;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Override
    public double[][] embed(List<String> texts) {
        String url = properties.getEmbeddingsUrl();
        String key = properties.getEmbeddingsApiKey();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            if (properties.getEmbeddingsModel() != null && !properties.getEmbeddingsModel().isBlank()) {
                body.put("model", properties.getEmbeddingsModel());
            }
            body.put("input", texts);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + key)
                    .POST(HttpRequest.BodyPublishers.ofString(writeJson(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                throw new EmbeddingNotSupportedException("嵌入接口 HTTP 404: "
                        + response.body().substring(0, Math.min(120, response.body().length())));
            }
            if (response.statusCode() != 200) {
                throw new IllegalStateException("嵌入接口返回 HTTP " + response.statusCode() + ": "
                        + response.body().substring(0, Math.min(120, response.body().length())));
            }
            return parseVectors(readJson(response.body()), texts.size());
        } catch (EmbeddingNotSupportedException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("嵌入接口调用失败: " + e.getMessage(), e);
        }
    }

    private String writeJson(Map<String, Object> body) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body);
    }

    private com.fasterxml.jackson.databind.JsonNode readJson(String json) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
    }

    /** 解析 OpenAI 兼容响应：data[i].index 对位回填，任一缺失即异常（防静默错位） */
    private static double[][] parseVectors(com.fasterxml.jackson.databind.JsonNode root, int expected) {
        double[][] vectors = new double[expected][];
        for (var item : root.path("data")) {
            int idx = item.path("index").asInt(0);
            if (idx < 0 || idx >= expected) {
                continue;
            }
            var arr = item.path("embedding");
            double[] vector = new double[arr.size()];
            for (int i = 0; i < arr.size(); i++) {
                vector[i] = arr.get(i).asDouble();
            }
            vectors[idx] = vector;
        }
        for (double[] v : vectors) {
            if (v == null) {
                throw new IllegalStateException("嵌入接口返回缺失向量");
            }
        }
        return vectors;
    }
}
