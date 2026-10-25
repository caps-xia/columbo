package org.caps.columbo.types.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 统一响应码。约定：HTTP 状态码只表达请求送达（正常走统一 200），业务成败一律看 body 里的 code，
 * 前端/调用方只需要一套判断逻辑。code 分段规则：
 * <pre>
 * 0000         成功
 * 0001 ~ 0999  通用错误（系统/参数/路由），由脚手架内置维护
 * A001 ~ A999  业务错误，按业务域分段扩展（A = Agent 智能体域；新增业务域时追加新段前缀）
 * </pre>
 * 扩展错误码时统一在本枚举登记，<b>不要</b>另建错误码常量类——单一事实来源，
 * 避免出现「枚举一套、常量一套」两边对不上的问题。
 *
 * @author caps
 */
@Getter
@AllArgsConstructor
public enum ResponseCode {

    SUCCESS("0000", "调用成功"),

    /** 通用段：系统兜底异常（具体堆栈只进日志，不外露） */
    UN_ERROR("0001", "系统繁忙，请稍后重试"),
    /** 通用段：参数校验不通过 */
    ILLEGAL_PARAMETER("0002", "非法参数"),
    /** 通用段：请求的目标方法不存在 */
    NOT_FOUND_METHOD("0003", "方法未找到"),

    /** Agent 段：智能体域业务错误 */
    NOT_FOUND_AGENT("A001", "智能体ID不存在"),
    /** Agent 段：单活跃流被占用（同用户并发流防护） */
    STREAM_OCCUPIED("A002", "当前有其他对话正在进行，请稍后再试"),
    NOT_LOGIN("0301", "未登录或登录已过期"),

    /** B = 排查域（troubleshoot 工具相关，迁自 trace-bot） */
    B001("B001", "日志渠道查询失败"),
    B002("B002", "日志渠道密钥未配置"),

    ;

    private final String code;
    private final String info;

}
