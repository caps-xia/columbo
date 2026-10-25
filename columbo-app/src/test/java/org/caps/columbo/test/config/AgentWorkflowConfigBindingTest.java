package org.caps.columbo.test.config;

import org.caps.columbo.domain.agent.model.valobj.AiAgentConfigTableVO;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * agent 配置装配一致性守护（离线，无 Spring 上下文）：
 * 用生产同款 YamlPropertySourceLoader + Binder 松散绑定加载 classpath:agent/*.yml，
 * 校验装配链（AgentNode → AgentWorkflowNode → RunnerNode）依赖的结构不变量——
 * 1. runner.agent-name 必须能在 agents ∪ agent-workflows 里找到（找不到 = RunnerNode
 *    直接 ILLEGAL_PARAMETER，装配失败）；
 * 2. agent-workflows.sub-agents 引用的名字必须存在（配错时 queryAgentList 只告警
 *    静默跳过，表现为"模型不干活"，这里构建期红灯）；
 * 3. 工作流 type 必须是 loop/parallel/sequential（AgentWorkflowNode 遇到未知 type
 *    直接抛 RuntimeException）。
 * log-code-troubleshoot 已改为两阶段 Sequential 工作流（取证→成文），此处一并固化。
 */
class AgentWorkflowConfigBindingTest {

    private static final List<String> AGENT_FILES = List.of(
            "agent/log-code-troubleshoot.yml",
            "agent/log-code-troubleshoot-direct.yml",
            "agent/db-query.yml");

    @Test
    void allAgentConfigsBindAndReferenceExistingAgents() {
        List<String> problems = new ArrayList<>();
        for (String file : AGENT_FILES) {
            AiAgentConfigTableVO table = bind(file);
            assertNotNull(table, file + " 应能绑定出配置表");
            assertNotNull(table.getModule(), file + " 缺 module");
            assertNotNull(table.getModule().getRunner(), file + " 缺 runner");
            assertNotNull(table.getModule().getAgents(), file + " 缺 agents 列表");
            assertFalse(table.getModule().getAgents().isEmpty(), file + " agents 列表为空");

            Set<String> known = new HashSet<>();
            table.getModule().getAgents().forEach(a -> {
                assertNotNull(a.getName(), file + " 存在未命名的 agent 条目");
                known.add(a.getName());
            });

            List<AiAgentConfigTableVO.Module.AgentWorkflow> workflows =
                    table.getModule().getAgentWorkflows();
            if (workflows != null) {
                for (AiAgentConfigTableVO.Module.AgentWorkflow workflow : workflows) {
                    assertNotNull(workflow.getType(), file + " 工作流缺 type");
                    assertTrue(Set.of("loop", "parallel", "sequential").contains(workflow.getType()),
                            file + " 工作流 type 非法: " + workflow.getType());
                    known.add(workflow.getName());
                    List<String> subs = workflow.getSubAgents();
                    assertNotNull(subs, file + " 工作流 " + workflow.getName() + " 缺 sub-agents");
                    for (String sub : subs) {
                        if (!known.contains(sub)) {
                            problems.add(file + " 工作流 " + workflow.getName()
                                    + " 引用了未装配的子智能体: " + sub
                                    + "（注意：嵌套工作流必须配在引用它的外层工作流之前）");
                        }
                    }
                }
            }

            String runnerAgent = table.getModule().getRunner().getAgentName();
            assertTrue(known.contains(runnerAgent),
                    file + " runner.agent-name 指向不存在的智能体/工作流: " + runnerAgent);
        }
        assertTrue(problems.isEmpty(), "agent 配置引用不一致:\n" + String.join("\n", problems));
    }

    @Test
    void troubleshootWorkflowIsTwoStageSequential() {
        AiAgentConfigTableVO table = bind("agent/log-code-troubleshoot.yml");
        List<AiAgentConfigTableVO.Module.AgentWorkflow> workflows = table.getModule().getAgentWorkflows();
        assertNotNull(workflows);
        assertEquals(1, workflows.size());
        AiAgentConfigTableVO.Module.AgentWorkflow flow = workflows.get(0);
        assertEquals("sequential", flow.getType());
        assertEquals(List.of("logEvidenceAgent", "logReportAgent"), flow.getSubAgents());
        assertEquals("logCodeTroubleshootFlow", table.getModule().getRunner().getAgentName());
        // 成文 agent 零工具：上下文隔离的前提（取证侧扛全部工具）
        AiAgentConfigTableVO.Module.Agent report = table.getModule().getAgents().stream()
                .filter(a -> "logReportAgent".equals(a.getName())).findFirst().orElseThrow();
        assertTrue(report.getToolMcpList() == null || report.getToolMcpList().isEmpty(),
                "成文 agent 应保持零工具");
        AiAgentConfigTableVO.Module.Agent evidence = table.getModule().getAgents().stream()
                .filter(a -> "logEvidenceAgent".equals(a.getName())).findFirst().orElseThrow();
        assertEquals(4, evidence.getToolMcpList().size(), "取证 agent 应挂全部 4 个工具提供者（CLS排查/本地代码/知识库/数据库核查）");
    }

    /** 生产同款加载路径：YamlPropertySourceLoader 读文件，Binder 松散绑定到 VO */
    private static AiAgentConfigTableVO bind(String path) {
        try {
            Resource resource = new ClassPathResource(path);
            YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
            Map<String, Object> flat = new HashMap<>();
            loader.load("test-" + path, resource).forEach(source -> {
                if (source.getSource() instanceof Map<?, ?> map) {
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        flatten(String.valueOf(entry.getKey()), entry.getValue(), flat);
                    }
                }
            });
            Binder binder = new Binder(
                    new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(flat));
            // camelCase 的 table 键（logCodeTroubleshoot）不是规范名，需 adapt 成宽松名再绑定
            org.springframework.boot.context.properties.source.ConfigurationPropertyName name =
                    org.springframework.boot.context.properties.source.ConfigurationPropertyName
                            .adapt(prefixOf(path), '.');
            return binder.bind(name, Bindable.of(AiAgentConfigTableVO.class)).orElse(null);
        } catch (Exception e) {
            throw new IllegalStateException("加载 agent 配置失败: " + path, e);
        }
    }

    /** 配置文件路径 → 绑定前缀（文件内 table 键名与文件一一对应，取文件里实际的 key） */
    private static String prefixOf(String path) {
        return "ai.agent.config.tables." + tableKeyOf(path);
    }

    private static final Map<String, String> TABLE_KEYS = Map.of(
            "agent/log-code-troubleshoot.yml", "logCodeTroubleshoot",
            "agent/log-code-troubleshoot-direct.yml", "logCodeTroubleshootDirect",
            "agent/db-query.yml", "dbQuery");

    private static String tableKeyOf(String path) {
        String key = TABLE_KEYS.get(path);
        assertNotNull(key, "未登记的 agent 配置文件: " + path + "（请在 TABLE_KEYS 登记）");
        return key;
    }

    /** YamlPropertySourceLoader 产出的源已是扁平 key=value；值是 OriginTrackedValue 包装，解开再交给 Binder */
    private static void flatten(String key, Object value, Map<String, Object> out) {
        if (value instanceof org.springframework.boot.origin.OriginTrackedValue tracked) {
            value = tracked.getValue();
        }
        out.put(key, value);
    }
}
