package org.caps.columbo.domain.agent.service.armory.matter.patch;

/**
 * 工具执行失败回传模型的文案分级：可重试错误给参数修正引导，凭证/限流类错误
 * 明确"重试无效、直接向用户说明"——弱模型（GLM）会把"请检查参数后重试"当成重试
 * 指令，对 token 过期/限流原样重发直到触发循环熔断（2026-10-05 线上 7 次熔断中
 * 两类各占其半，错误文案措辞是直接诱因之一）。
 */
final class ToolErrorTexts {

    private ToolErrorTexts() {
    }

    /** 工具执行失败的兜底响应文本：按错误类型给不同处置引导 */
    static String forExecutionFailure(Exception error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        if (isNonRetryable(message)) {
            return "工具未执行：执行失败（" + message + "）。"
                    + "该错误为凭证/限流/环境问题，重试或修改参数均无法解决，请勿再次调用本工具；"
                    + "请直接基于已有证据向用户说明现状与建议。";
        }
        return "工具未执行：执行失败（" + message + "）。"
                + "请检查调用参数后重试；若无法构造合法参数，请直接用文字回答用户。";
    }

    /** 凭证/限流/环境类错误识别：重试不可能成功，回传文本必须阻止原样重发 */
    static boolean isNonRetryable(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("重新登录") || lower.contains("access_token") || lower.contains("无权限")
                || lower.contains("未授权") || lower.contains("unauthorized") || lower.contains("forbidden")
                || lower.contains("http 401") || lower.contains("http 403")
                || lower.contains("toolcalllimitexceeded") || lower.contains("tool call limit");
    }
}
