package org.caps.columbo.domain.troubleshoot.adapter.port;

import org.caps.columbo.domain.troubleshoot.model.entity.ClsLogContextQuery;
import org.caps.columbo.domain.troubleshoot.model.entity.ClsLogQuery;
import org.caps.columbo.domain.troubleshoot.model.valobj.ClsLogContextResultVO;
import org.caps.columbo.domain.troubleshoot.model.valobj.ClsLogCountResultVO;
import org.caps.columbo.domain.troubleshoot.model.valobj.ClsLogResultVO;
import org.caps.columbo.domain.troubleshoot.model.valobj.ClsTopicSyncResultVO;

/**
 * 腾讯云日志服务 CLS 查询端口（多站点：南京/海南），由基础设施层 adapter/port 提供实现。
 */
public interface IClsLogPort extends ITroubleshootChannel {

    /** 分页拉取日志（时间倒序；单应用支持 Context 游标翻页） */
    ClsLogResultVO queryClsLog(ClsLogQuery query);

    /** 探量：按主题 SQL 统计命中条数 + 时间分桶分布，查询前预估数据量用 */
    ClsLogCountResultVO countClsLog(ClsLogQuery query);

    /** 上下文检索：以某条日志为锚点取前后各 N 条（不支持上下文API的地域自动降级时间窗口模拟） */
    ClsLogContextResultVO queryClsLogContext(ClsLogContextQuery query);

    /** 主题注册表同步：DescribeTopics 拉取线上主题合并进注册表（新应用自动补录、占位 topicId 自动修正） */
    ClsTopicSyncResultVO syncClsTopics();
}
