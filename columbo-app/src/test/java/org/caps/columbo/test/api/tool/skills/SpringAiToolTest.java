package org.caps.columbo.test.api.tool.skills;

import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.caps.columbo.domain.agent.model.valobj.AiApiEndpointVO;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.ClassPathResource;

import java.util.ArrayList;

/**
 * Spring Ai Tool
 *
 * @author caps 
 * 2025/12/14 09:51
 */
@Slf4j
public class SpringAiToolTest {

    public static void main(String[] args) {
        String apiKey = System.getenv("LLM_API_KEY");
        String baseUrl = AiApiEndpointVO.of("https://open.bigmodel.cn/api/paas/v4", apiKey, "chat/completions").baseUrl();

        ToolCallback toolCallback02 = SkillsTool.builder()
                .addSkillsResource(new ClassPathResource("agent/skills"))
                .build();

        ChatModel chatModel = OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                        .model("glm-5.3")
                        .toolCallbacks(new ArrayList<>(){{
                            add(toolCallback02);
                        }})
                        .build())
                .build();

        String call = chatModel.call("基于 skill 解答，电脑性能优化");

        log.info("测试结果:{}", call);
    }

}
