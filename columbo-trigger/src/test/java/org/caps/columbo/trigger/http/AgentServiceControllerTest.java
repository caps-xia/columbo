package org.caps.columbo.trigger.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.caps.columbo.domain.agent.service.IChatService;
import org.caps.columbo.types.enums.ResponseCode;
import org.caps.columbo.types.exception.AppException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 接口行为回归：参数校验、非法 JSON、业务异常、正常链路与新增的异常映射。
 * standalone 模式不启动容器，也不依赖 agent 配置/网络密钥，可在 CI 直接运行。
 */
class AgentServiceControllerTest {

    private final IChatService chatService = mock(IChatService.class);
    private final GlobalExceptionHandler exceptionHandler = new GlobalExceptionHandler();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AgentServiceController controller = new AgentServiceController();
        ReflectionTestUtils.setField(controller, "chatService", chatService);
        // 免登录模式（enabled=false）的 CurrentUser：原样透传请求里的 userId
        ReflectionTestUtils.setField(controller, "currentUser",
                new CurrentUser(new org.caps.columbo.domain.auth.model.properties.AuthProperties()));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(exceptionHandler)
                .build();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonNode performOk(String method, String uri, String body) throws Exception {
        // classpath 上存在 jackson-dataformat-xml，不指定 Accept 会被协商成 XML；接口契约是 JSON
        var request = "GET".equals(method) ? get(uri).accept(MediaType.APPLICATION_JSON)
                : post(uri).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).content(body == null ? "" : body);
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void 参数缺失时返回0002并携带字段提示() throws Exception {
        JsonNode response = performOk("POST", "/api/v1/chat", "{}");
        assertEquals("0002", response.path("code").asText());
        // 三处 @NotBlank 的提示都要带出去
        assertEquals(3, response.path("info").asText().split("；").length);
    }

    @Test
    void 非法JSON返回0002而不是兜底0001() throws Exception {
        JsonNode response = performOk("POST", "/api/v1/chat", "not-a-json");
        assertEquals("0002", response.path("code").asText());
    }

    @Test
    void 业务异常按异常携带的code返回() throws Exception {
        when(chatService.createSession(anyString(), anyString()))
                .thenThrow(new AppException(ResponseCode.NOT_FOUND_AGENT));
        JsonNode response = performOk("POST", "/api/v1/create_session",
                "{\"agentId\":\"no_such_agent\",\"userId\":\"tester\"}");
        assertEquals("A001", response.path("code").asText());
        assertEquals("智能体ID不存在", response.path("info").asText());
    }

    @Test
    void 列表查询正常返回0000() throws Exception {
        JsonNode response = performOk("GET", "/api/v1/query_ai_agent_config_list", null);
        assertEquals("0000", response.path("code").asText());
    }

    @Test
    void 对话返回解析失败的兜底拼接内容() throws Exception {
        // sessionId 为 null 时 anyString() 不匹配，须用 any()
        when(chatService.handleMessage(any(), any(), any(), any()))
                .thenReturn(List.of("第一段", "第二段"));
        JsonNode response = performOk("POST", "/api/v1/chat",
                "{\"agentId\":\"100000\",\"userId\":\"tester\",\"message\":\"hi\"}");
        assertEquals("0000", response.path("code").asText());
        assertEquals("第一段\n第二段", response.path("data").path("content").asText());
    }

    @Test
    void 路径不存在映射0003() {
        assertEquals("0003", exceptionHandler
                .handleNoResourceFoundException(new NoResourceFoundException(HttpMethod.GET, "/api/v1/none", "Not Found"))
                .getCode());
    }

    @Test
    void 请求方式不支持映射0003() {
        assertEquals("0003", exceptionHandler
                .handleHttpRequestMethodNotSupportedException(new HttpRequestMethodNotSupportedException("GET"))
                .getCode());
    }

}
