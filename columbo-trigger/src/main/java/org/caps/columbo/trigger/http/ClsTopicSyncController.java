package org.caps.columbo.trigger.http;

import org.caps.columbo.api.response.Response;
import org.caps.columbo.domain.troubleshoot.adapter.port.IClsLogPort;
import org.caps.columbo.domain.troubleshoot.model.valobj.ClsTopicSyncResultVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CLS 主题注册表同步：调 DescribeTopics 拉取线上主题（按日志集过滤）合并进注册表，
 * 新应用自动补录、yml 占位 topicId 自动修正。服务启动时会自动同步一次，这里是手动补拉入口
 * （新增 CLS 主题后无需改配置重启，调一次本接口即可查询）。
 */
@Slf4j
@Tag(name = "智能体服务", description = "腾讯云CLS主题注册表同步")
@RestController
@RequestMapping("/api/v1/")
public class ClsTopicSyncController {

    @Resource
    private IClsLogPort clsLogPort;

    @Operation(summary = "同步CLS主题注册表", description = "拉取各站点线上日志主题合并进注册表，返回逐站点结果")
    @PostMapping("cls_topics_sync")
    public Response<ClsTopicSyncResultVO> sync() {
        log.info("手动同步CLS主题注册表");
        return Response.success(clsLogPort.syncClsTopics());
    }
}
