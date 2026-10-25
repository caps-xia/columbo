package org.caps.columbo.infrastructure.adapter.port;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.caps.columbo.domain.troubleshoot.adapter.port.IImageUnderstandingPort;
import org.caps.columbo.domain.troubleshoot.model.properties.ImageUnderstandingProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

/**
 * 图片识别端口实现：调用 OpenAI 兼容多模态 chat completions（image_url + data URL），
 * 把截图/照片中的报错文案、异常栈、日志行提取为文字。
 */
@Slf4j
@Component
public class ImageUnderstandingPortImpl implements IImageUnderstandingPort {

    /**
     * 提取提示词：纯 OCR，不带用户问题（"为什么"类问题会诱发模型长思考链，
     * 实测烧满 8192 token 产出空正文）；日志墙不逐行转录（密集日志逐行转录
     * 实测 50s+），重复行合并控制输出长度。
     */
    private static final String EXTRACT_PROMPT = """
            你是问题排查助手的图片识别模块，只做文字提取，不分析问题原因、不回答任何问题。提取图片中的文字信息，要求：
            1. 报错弹窗/页面提示/按钮文案，逐字保留原文（不要翻译、不要总结改写）；
            2. 异常栈/控制台日志/终端输出：类名、方法名、行号、时间戳、日志级别、traceId、
               错误码逐字保留；连续重复或高度相似的日志行不要逐行转录，合并为
               "同类日志共N条"并附一行最有代表性的样例；
            3. 表格或列表中的关键数据（订单号、手机号、金额、状态）一并提取；
            4. ⚠️ 时间信息单独提取并置顶输出：页面上任何可见时间都要抓——消息/记录的时间戳、
               "最后更新/更新于/创建时间"字段、页面角落或状态栏的显示时间、弹窗里的发生时间。
               每个时间标注来源，格式如"页面时间: 10:47"、"最后更新: 2026-09-22 10:47:32"。
               时间是排查定位的关键锚点，宁可多提取不可遗漏；
            5. 多张图按【图1】【图2】编号输出；没有文字的图用一句话描述界面内容。
            直接输出提取结果，不要输出思考过程、建议或分析。""";

    /**
     * 限幅思考：oneai 网关支持 reasoning_effort，low 档会关闭思考链（实测密集日志
     * 截图 13.5s 完成且报错行逐字精确；默认思考档同图 50-73s 还会烧满 token 产空正文）。
     * 纯 OCR 不需要推理，识别精度靠原始分辨率而不是思考链。
     */
    private static final String REASONING_EFFORT = "low";

    /** 生成上限：提取结果的兜底护栏，防模型偶发跑飞烧 token（截断也保证有正文可拿） */
    private static final int MAX_TOKENS = 3072;

    /**
     * 发模型前的最长边上限：只拦病态巨图（防视觉 token 失控）。常规 retina 截图
     * （≤3000px）必须保持原始分辨率——实测 low 思考档下 2880px 逐字精确、
     * 压到 1568px 小字就会被读串（license→lionen），OCR 精度靠像素不靠思考。
     */
    static final int MAX_SIDE = 4096;

    /** 建立网关连接的超时（读超时另算：整体生成耗时由 timeout-ms 控制，二者不该共用） */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    @Resource
    private ImageUnderstandingProperties properties;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpClient httpClient;

    @PostConstruct
    public void init() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    @Override
    public String understand(List<String> imageDataUrls) {
        if (!StringUtils.hasText(properties.getBaseUrl()) || !StringUtils.hasText(properties.getModel())) {
            throw new IllegalStateException("图片识别未配置 base-url/model（troubleshoot.image-understanding）");
        }
        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", properties.getModel());
            requestBody.put("max_tokens", MAX_TOKENS);
            requestBody.put("reasoning_effort", REASONING_EFFORT);
            ArrayNode messages = requestBody.putArray("messages");
            ObjectNode message = messages.addObject();
            message.put("role", "user");
            ArrayNode content = message.putArray("content");
            ObjectNode textPart = content.addObject();
            textPart.put("type", "text");
            textPart.put("text", EXTRACT_PROMPT);
            List<String> images = clipImages(imageDataUrls);
            for (String dataUrl : images) {
                ObjectNode imagePart = content.addObject();
                imagePart.put("type", "image_url");
                imagePart.putObject("image_url").put("url", downscaleDataUrl(dataUrl));
            }

            String url = joinUrl(properties.getBaseUrl(), properties.getCompletionsPath());
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(properties.getTimeoutMs() == null ? 30000 : properties.getTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("图片识别网关返回 {} : {}", response.statusCode(), abbreviate(response.body()));
                throw new IllegalStateException("图片识别网关返回 HTTP " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode text = root.path("choices").path(0).path("message").path("content");
            if (!text.isTextual() || text.asText().isBlank()) {
                throw new IllegalStateException("图片识别返回内容为空");
            }
            return text.asText();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.warn("图片识别调用失败", e);
            throw new IllegalStateException("图片识别调用失败: " + e.getMessage(), e);
        }
    }

    /** 超过数量/大小上限的图直接丢弃，避免网关 413 或超长耗时 */
    private List<String> clipImages(List<String> imageDataUrls) {
        int maxImages = properties.getMaxImages() == null ? 4 : properties.getMaxImages();
        int maxBytes = properties.getMaxImageBytes() == null ? 5242880 : properties.getMaxImageBytes();
        List<String> clipped = new ArrayList<>();
        for (String dataUrl : imageDataUrls) {
            if (clipped.size() >= maxImages) {
                break;
            }
            if (dataUrl != null && dataUrl.startsWith("data:image/") && dataUrl.length() <= maxBytes * 4 / 3) {
                clipped.add(dataUrl);
            }
        }
        return clipped;
    }

    /**
     * 最长边超过 {@link #MAX_SIDE} 的 data URL 图等比降采样后重新编码；
     * 解析/解码失败（ImageIO 不支持的格式等）或降采样反而不省体积时原样返回。
     */
    static String downscaleDataUrl(String dataUrl) {
        if (dataUrl == null) {
            return null;
        }
        int comma = dataUrl.indexOf(',');
        if (!dataUrl.startsWith("data:image/") || comma < 0) {
            return dataUrl;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(dataUrl.substring(comma + 1));
            byte[] scaled = downscaleImage(bytes);
            if (scaled == bytes) {
                return dataUrl;
            }
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(scaled);
        } catch (Exception e) {
            return dataUrl;
        }
    }

    /** 位图降采样（PNG 重编码）；无需缩放/无法解码时返回原字节数组引用 */
    static byte[] downscaleImage(byte[] imageBytes) throws IOException {
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(imageBytes));
        if (source == null) {
            return imageBytes;
        }
        int longSide = Math.max(source.getWidth(), source.getHeight());
        if (longSide <= MAX_SIDE) {
            return imageBytes;
        }
        double ratio = (double) MAX_SIDE / longSide;
        int targetW = Math.max(1, (int) Math.round(source.getWidth() * ratio));
        int targetH = Math.max(1, (int) Math.round(source.getHeight() * ratio));
        BufferedImage target = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, targetW, targetH);
            graphics.drawImage(source, 0, 0, targetW, targetH, null);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(target, "png", out);
        byte[] scaled = out.toByteArray();
        return scaled.length < imageBytes.length ? scaled : imageBytes;
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
