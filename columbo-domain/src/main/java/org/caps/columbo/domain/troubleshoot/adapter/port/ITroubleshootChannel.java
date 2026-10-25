package org.caps.columbo.domain.troubleshoot.adapter.port;

/**
 * 客服问题排查数据渠道 SPI。新增排查渠道（日志、订单、工单、监控等）时实现本接口，
 * 由基础设施层 adapter/port 提供具体实现，即可被 TroubleshootTools 自动发现并暴露给 LLM。
 */
public interface ITroubleshootChannel {

    /** 渠道类型标识，如 tencent-cls-log */
    String getChannelType();

    /** 渠道名称，如 腾讯云CLS实时日志 */
    String getChannelName();

    /** 渠道能力描述，供 LLM 判断何时选用该渠道 */
    String getChannelDesc();

}
