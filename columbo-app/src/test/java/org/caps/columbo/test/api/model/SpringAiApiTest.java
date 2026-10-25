package org.caps.columbo.test.api.model;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.caps.columbo.domain.agent.model.valobj.AiApiEndpointVO;

/**
 * Spring AI Test
 * 文档：<a href="https://docs.spring.io/spring-ai/reference/1.0-SNAPSHOT/api/advisors.html">spring ai</a>
 * @author caps 
 * 2025/12/14 09:15
 */
@Slf4j
public class SpringAiApiTest {

    public static void main(String[] args) {
        String apiKey = System.getenv("LLM_API_KEY");
        String baseUrl = AiApiEndpointVO.of("https://open.bigmodel.cn/api/paas/v4", apiKey, "chat/completions").baseUrl();

        ChatModel chatModel = OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                        .model("glm-5.3")
                        .build())
                .build();

        String call = chatModel.call("hi 你好哇!");

        log.info("测试结果:{}", call);
    }

}
