package org.caps.columbo.domain.agent.service.armory.matter.patch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 工具错误文案分级回归：凭证/限流类禁止重试引导，参数类保留修正引导 */
class ToolErrorTextsTest {

    @Test
    void credentialErrorsAreMarkedNonRetryable() {
        assertTrue(ToolErrorTexts.isNonRetryable("线上查询失败 HTTP 200 code=1001 重新登录（access_token 无效或已过期）"));
        assertTrue(ToolErrorTexts.isNonRetryable("CLS日志查询失败：密钥未授权，请重新登录"));
        assertTrue(ToolErrorTexts.isNonRetryable(
                "ToolCallLimitExceededException: Tool call limit (40) exceeded for tool 'queryOnlineData'"));
        assertTrue(ToolErrorTexts.isNonRetryable("HTTP 403 Forbidden"));
    }

    @Test
    void paramErrorsStayRetryable() {
        assertFalse(ToolErrorTexts.isNonRetryable("连接超时"));
        assertFalse(ToolErrorTexts.isNonRetryable("参数校验失败：env 仅支持 prod/qa"));
        assertFalse(ToolErrorTexts.isNonRetryable(null));
    }

    @Test
    void nonRetryableTextForbidsRetryAndRetryableTextKeepsParamGuidance() {
        Exception credential = new IllegalStateException("线上查询失败 code=1001 重新登录");
        String credentialText = ToolErrorTexts.forExecutionFailure(credential);
        assertTrue(credentialText.contains("请勿再次调用"));
        assertTrue(!credentialText.contains("请检查调用参数后重试"));

        Exception timeout = new IllegalStateException("连接超时");
        String retryableText = ToolErrorTexts.forExecutionFailure(timeout);
        assertTrue(retryableText.contains("请检查调用参数后重试"));
        assertTrue(!retryableText.contains("请勿再次调用"));
    }
}
