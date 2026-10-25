package org.caps.columbo.test.api.tool.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.caps.columbo.domain.agent.model.valobj.AiApiEndpointVO;
import org.springframework.ai.tool.ToolCallback;

import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;

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

        ChatModel chatModel = OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                        .model("glm-5.3")
                        .toolCallbacks(SyncMcpToolCallbackProvider.builder()
                                .mcpClients(sseMcpClient()).build()
                                .getToolCallbacks())
                        .build())
                .build();

        String call = chatModel.call("你哪有哪些工具能力");

        log.info("测试结果:{}", call);
    }

    /**
     * 百度搜索MCP服务(url)；https://sai.baidu.com/zh/detail/e014c6ffd555697deabf00d058baf388
     * 百度搜索MCP服务(key)；https://console.bce.baidu.com/iam/?_=1753597622044#/iam/apikey/list
     */
    public static McpSyncClient sseMcpClient() {

        // api_key 需自己申请（百度 AppBuilder），不要把真实 key 提交进仓库
        HttpClientSseClientTransport sseClientTransport = HttpClientSseClientTransport.builder("http://appbuilder.baidu.com")
                .sseEndpoint("/v2/ai_search/mcp/sse?api_key=please-replace-your-api-key")
                .build();

        McpSyncClient mcpSyncClient = McpClient.sync(sseClientTransport).requestTimeout(Duration.ofMinutes(360)).build();
        var init_sse = mcpSyncClient.initialize();
        log.info("Tool SSE MCP Initialized {}", init_sse);

        return mcpSyncClient;
    }

    @Test
    public void test_url() throws MalformedURLException {
        String fullUrl = "http://appbuilder.baidu.com/v2/ai_search/mcp/sse?api_key=please-replace-your-api-key";

        fullUrl = "http://127.0.0.1:9999/sse?apiKey=xxxx";

        URL url = new URL(fullUrl);

        String protocol = url.getProtocol();
        String host = url.getHost();
        int port = url.getPort();

        String baseUrl = port == -1 ? protocol + "://" + host : protocol + "://" + host + ":" + port;
        String endpoint = "";

        int index = fullUrl.indexOf(baseUrl);
        if (index != -1) {
            endpoint = fullUrl.substring(index + baseUrl.length());
        }

        log.info("baseUrl:{}", baseUrl);
        log.info("endpoint:{}", endpoint);
    }

}
