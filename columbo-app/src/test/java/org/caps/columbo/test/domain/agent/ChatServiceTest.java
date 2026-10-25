package org.caps.columbo.test.domain.agent;

import org.caps.columbo.domain.agent.model.entity.ChatCommandEntity;
import org.caps.columbo.domain.agent.service.IChatService;
import lombok.extern.slf4j.Slf4j;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.util.MimeTypeUtils;

import jakarta.annotation.Resource;
import java.io.IOException;
import java.util.List;

@Slf4j
@Ignore("依赖真实模型网关，默认跳过；需要时手动启用（跑前确认网关与 agent 配置可用）")
@RunWith(SpringRunner.class)
@SpringBootTest
public class ChatServiceTest {

    @Resource
    private IChatService chatService;

    @Value("classpath:file/dog.png")
    private org.springframework.core.io.Resource imageResource;

    @Test
    public void test_handleMessage_01() {
        List<String> message = chatService.handleMessage("100003", "caps", "你具备哪些skill技能");
        log.info("测试结果:{}", String.valueOf(message));
    }

    @Test
    public void test_handleMessage_04_withImage() throws IOException {
        String agentId = "100003";
        String userId = "caps";

        String sessionId = chatService.createSession(agentId, userId);

        ChatCommandEntity chatCommandEntity = ChatCommandEntity.builder()
                .agentId(agentId)
                .userId(userId)
                .sessionId(sessionId)
                .texts(List.of(new ChatCommandEntity.Content.Text("请识别这个图片。告诉我它是什么动物，并用一句话描述。")))
                .files(List.of())
                .inlineDatas(List.of(new ChatCommandEntity.Content.InlineData(imageResource.getContentAsByteArray(), MimeTypeUtils.IMAGE_PNG_VALUE)))
                .build();

        List<String> message = chatService.handleMessage(chatCommandEntity);
        log.info("测试结果:{}", String.valueOf(message));
    }

}
