package org.caps.columbo.test;
import org.caps.columbo.domain.agent.service.armory.matter.patch.MySpringAI;
import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.models.springai.SpringAI;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.caps.columbo.domain.agent.model.valobj.AiApiEndpointVO;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.io.InputStream;

@Slf4j
@Ignore("依赖真实模型网关，默认跳过；需要时手动启用（跑前确认网关与 agent 配置可用）")
@RunWith(SpringRunner.class)
@SpringBootTest
public class ApiTest {

    public static void main(String[] args) throws Exception {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        InputStream resourceAsStream = classLoader.getResourceAsStream("dog.png");
        Resource resource = new ClassPathResource("dog.png", classLoader);
        assert resourceAsStream != null;

        String apiKey = System.getenv("LLM_API_KEY");
        String baseUrl = AiApiEndpointVO.of("https://open.bigmodel.cn/api/paas/v4", apiKey, "chat/completions").baseUrl();

        ChatModel chatModel = OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                        .model("glm-5.3")
                        .build())
                .build();

        // 模型测试，没问题可以识别图片
//        ChatResponse response = chatModel.call(new Prompt(
//                UserMessage.builder()
//                        .text("请描述这张图片的主要内容，并说明图中物品的可能用途。")
//                        .media(Media.builder()
//                                .mimeType(MimeType.valueOf(MimeTypeUtils.IMAGE_PNG_VALUE))
//                                .data(resource)
//                                .build())
//                        .build(),
//                OpenAiChatOptions.builder()
//                        .model("glm-5.3")
//                        .build()));
//
//        System.out.println("测试结果" + JSON.toJSONString(response));


        // agent 测试
        LlmAgent agent = LlmAgent.builder()
                .name("test")
                .description("Chess coach agent")
                .model(new MySpringAI(chatModel))
                .instruction("""
                        You are a knowledgeable chess coach
                        who helps chess players train and sharpen their chess skills.
                        """)
                .build();

        InMemoryRunner runner = new InMemoryRunner(agent);

        Session session = runner
                .sessionService()
                .createSession("test", "fzw")
                .blockingGet();

        Flowable<Event> events = runner.runAsync("fzw", session.id(),
                Content.fromParts(Part.fromText("这是什么图片"),
                        Part.fromBytes(resource.getContentAsByteArray(), MimeTypeUtils.IMAGE_PNG_VALUE)));

        System.out.print("\nAgent > ");
        events.blockingForEach(event -> System.out.println(event.stringifyContent()));

    }

}
