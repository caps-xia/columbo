package org.caps.columbo.domain.troubleshoot.model.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 图片识别配置：多模态模型把用户附图（截图/照片）转成文字，拼入排查问题文本。
 * 模型需支持 OpenAI 兼容的 image_url 多模态输入（如 glm-5.3-flash）。
 */
@Data
@ConfigurationProperties(prefix = "troubleshoot.image-understanding", ignoreInvalidFields = true)
public class ImageUnderstandingProperties {

    /** 是否启用图片识别；false 或未配置密钥时附图仅存储、不识别 */
    private boolean enabled = false;

    /** OpenAI 兼容网关地址，如 https://open.bigmodel.cn/api/paas/v4 */
    private String baseUrl;

    /** chat completions 路径，默认 v1/chat/completions */
    private String completionsPath = "v1/chat/completions";

    /** API Key */
    private String apiKey;

    /** 多模态模型名，如 glm-5.3-flash */
    private String model;

    /** 识别请求超时（毫秒） */
    private Integer timeoutMs = 30000;

    /** 单条消息最多附图数 */
    private Integer maxImages = 4;

    /** 单图大小上限（字节，base64 前的原始数据） */
    private Integer maxImageBytes = 5242880;
}
