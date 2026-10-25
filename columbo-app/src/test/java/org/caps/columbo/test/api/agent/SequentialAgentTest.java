package org.caps.columbo.test.api.agent;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.SequentialAgent;
import com.google.adk.events.Event;
import com.google.adk.models.springai.SpringAI;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.reactivex.rxjava3.core.Flowable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.caps.columbo.domain.agent.model.valobj.AiApiEndpointVO;

import java.time.Duration;

@Slf4j
public class SequentialAgentTest {

    private static final String APP_NAME = "CodePipelineAgent";
    private static final String USER_ID = "test_user_456";

    public static void main(String[] args) {
        String apiKey = System.getenv("LLM_API_KEY");
        String baseUrl = AiApiEndpointVO.of("https://open.bigmodel.cn/api/paas/v4", apiKey, "chat/completions").baseUrl();

        ChatModel chatModel = OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                        .model("glm-5.3")
                        .toolCallbacks(new SyncMcpToolCallbackProvider(sseMcpClient()).getToolCallbacks())
                        .build())
                .build();

        SequentialAgent codePipelineAgent =
                SequentialAgent.builder()
                        .name("CodePipelineAgent")
                        .description("Executes a sequence of code writing, reviewing, and refactoring.")
                        // The agents will run in the order provided: Writer -> Reviewer -> Refactorer
                        .subAgents(codeWriterAgent(chatModel), codeReviewerAgent(chatModel), codeRefactorerAgent(chatModel))
                        .build();

        // Create an InMemoryRunner
        InMemoryRunner runner = new InMemoryRunner(codePipelineAgent, APP_NAME);
        // InMemoryRunner automatically creates a session service. Create a session using the service
        Session session = runner.sessionService().createSession(APP_NAME, USER_ID).blockingGet();
        Content userMessage = Content.fromParts(Part.fromText("Write a Java function to calculate the factorial of a number."));

        // Run the agent
        Flowable<Event> eventStream = runner.runAsync(USER_ID, session.id(), userMessage);

        // Stream event response
        eventStream.blockingForEach(
                event -> {
                    if (event.finalResponse()) {
                        System.out.println(event.stringifyContent());
                    }
                });

    }

    public static LlmAgent codeWriterAgent(ChatModel chatModel) {
        return LlmAgent.builder()
                .model(new SpringAI(chatModel))
                .name("CodeWriterAgent")
                .description("Writes initial Java code based on a specification.")
                .instruction(
                        """
                                You are a Java Code Generator.
                                Based *only* on the user's request, write Java code that fulfills the requirement.
                                Output *only* the complete Java code block, enclosed in triple backticks (```java ... ```).
                                Do not add any other text before or after the code block.
                                """)
                .outputKey("generated_code")
                .build();
    }

    public static LlmAgent codeReviewerAgent(ChatModel chatModel) {
        return LlmAgent.builder()
                .model(new SpringAI(chatModel))
                .name("CodeReviewerAgent")
                .description("Reviews code and provides feedback.")
                .instruction(
                        """
                                    You are an expert Java Code Reviewer.
                                    Your task is to provide constructive feedback on the provided code.
                                
                                    **Code to Review:**
                                    ```java
                                    {generated_code}
                                    ```
                                
                                    **Review Criteria:**
                                    1.  **Correctness:** Does the code work as intended? Are there logic errors?
                                    2.  **Readability:** Is the code clear and easy to understand? Follows Java style guidelines?
                                    3.  **Efficiency:** Is the code reasonably efficient? Any obvious performance bottlenecks?
                                    4.  **Edge Cases:** Does the code handle potential edge cases or invalid inputs gracefully?
                                    5.  **Best Practices:** Does the code follow common Java best practices?
                                
                                    **Output:**
                                    Provide your feedback as a concise, bulleted list. Focus on the most important points for improvement.
                                    If the code is excellent and requires no changes, simply state: "No major issues found."
                                    Output *only* the review comments or the "No major issues" statement.
                                """)
                .outputKey("review_comments")
                .build();
    }

    public static LlmAgent codeRefactorerAgent(ChatModel chatModel) {
        return LlmAgent.builder()
                .model(new SpringAI(chatModel))
                .name("CodeRefactorerAgent")
                .description("Refactors code based on review comments.")
                .instruction(
                        """
                                You are a Java Code Refactoring AI.
                                Your goal is to improve the given Java code based on the provided review comments.
                                
                                  **Original Code:**
                                  ```java
                                  {generated_code}
                                  ```
                                
                                  **Review Comments:**
                                  {review_comments}
                                
                                **Task:**
                                Carefully apply the suggestions from the review comments to refactor the original code.
                                If the review comments state "No major issues found," return the original code unchanged.
                                Ensure the final code is complete, functional, and includes necessary imports and docstrings.
                                
                                **Output:**
                                Output *only* the final, refactored Java code block, enclosed in triple backticks (```java ... ```).
                                Do not add any other text before or after the code block.
                                """)
                .outputKey("refactored_code")

                .build();
    }

    public static McpSyncClient sseMcpClient() {
        HttpClientSseClientTransport sseClientTransport = HttpClientSseClientTransport.builder("http://appbuilder.baidu.com/v2/ai_search/mcp/")
                .sseEndpoint("sse?api_key=please-replace-your-api-key")
                .build();

        McpSyncClient mcpSyncClient = McpClient.sync(sseClientTransport).requestTimeout(Duration.ofMinutes(360)).build();
        var init_sse = mcpSyncClient.initialize();
        log.info("Tool SSE MCP Initialized {}", init_sse);

        return mcpSyncClient;
    }

}
