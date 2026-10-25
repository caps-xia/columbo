package org.caps.columbo.domain.troubleshoot.adapter.port;

import java.util.List;

/**
 * 图片识别端口：多模态模型提取附图中的文字信息（报错文案/异常栈/日志/界面元素）。
 * 实现在 infrastructure，走 OpenAI 兼容的 image_url 多模态接口。
 */
public interface IImageUnderstandingPort {

    /**
     * 识别附图，返回提取的文字描述。
     *
     * <p>注意：只做纯文字提取，不感知也不回答用户问题——把"为什么"类问题带给识别模型
     * 会诱发长思考链（实测烧满 8192 token 产出空正文），分析由主对话模型负责。
     *
     * @param imageDataUrls 图片列表（data URL 格式：data:image/png;base64,....）
     * @return 各图提取的文字内容（已按图编号组织）；识别失败抛出异常，由调用方降级
     */
    String understand(List<String> imageDataUrls);
}
