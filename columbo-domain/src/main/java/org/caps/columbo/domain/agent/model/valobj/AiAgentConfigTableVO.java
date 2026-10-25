package org.caps.columbo.domain.agent.model.valobj;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Ai Agent 智能体配置表值对象
 *
 * @author caps 
 * 2025/11/29 10:54
 */
@Data
public class AiAgentConfigTableVO {

    /**
     * 应用名称
     */
    private String appName;

    /**
     * 智能体配置
     */
    private Agent agent;

    /**
     * 智能体模块
     */
    private Module module;

    @Data
    public static class Agent {

        /**
         * 智能体ID
         */
        private String agentId;

        /**
         * 智能体名称
         */
        private String agentName;

        /**
         * 智能体描述
         */
        private String agentDesc;

    }

    @Data
    public static class Module {

        private AiApi aiApi;

        private ChatModel chatModel;

        private List<Agent> agents;

        private List<AgentWorkflow> agentWorkflows;

        private Runner runner;

        @Data
        public static class AiApi {
            private String baseUrl;
            private String apiKey;
            private String completionsPath = "/v1/chat/completions";
            private String embeddingsPath = "/v1/embeddings";

        }

        @Data
        public static class ChatModel {

            private String model;

            /**
             * 可切换模型池（可选）：配置多个后前端可动态切换模型，切换为运行时重装配
             * （registerBean 覆盖注册，会话服务共享不丢历史），重启后回到 model 指定的默认值。
             * 未配置时池 = [model]，切换接口不可用。
             */
            private List<String> models;

        }

        @Data
        public static class ToolMcp {

            private SSEServerParameters sse;

            private StdioServerParameters stdio;

            private LocalParameters local;

            @Data
            public static class SSEServerParameters {
                private String name;
                private String baseUri;
                private String sseEndpoint;
                private Integer requestTimeout = 3000;

            }

            @Data
            public static class StdioServerParameters {
                private String name;
                private Integer requestTimeout = 3000;
                private ServerParameters serverParameters;

                @Data
                public static class ServerParameters {
                    private String command;
                    private List<String> args;
                    private Map<String, String> env;

                }
            }

            @Data
            public static class LocalParameters {
                private String name;
            }

        }

        @Data
        public static class ToolSkills {

            /**
             * 类型；directory（用户配置的，映射进来的）、resource（放到工程下的）
             */
            private String type = "directory";

            /**
             * 路径；
             */
            private String path;

        }

        @Data
        public static class Agent {
            private String name;
            private String instruction;
            private String description;
            private String outputKey;

            /** Agent 级工具（MCP/local），装配到 LlmAgent.tools，每个 Agent 独立生效 */
            private List<ToolMcp> toolMcpList;

            /** Agent 级工具（skills 目录），装配到 LlmAgent.tools */
            private List<ToolSkills> toolSkillsList;

        }

        @Data
        public static class AgentWorkflow {
            /**
             * 类型；loop、parallel、sequential
             */
            private String type;
            private String name;
            private List<String> subAgents;
            private String description;
            private Integer maxIterations = 3;

        }

        @Data
        public static class Runner {
            private String agentName;
            private List<String> pluginNameList;
        }
    }

}
