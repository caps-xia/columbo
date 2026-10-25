package org.caps.columbo.domain.troubleshoot.service;

import org.caps.columbo.domain.agent.service.armory.matter.tools.TroubleshootTools;
import org.junit.jupiter.api.Test;
import org.springframework.ai.util.json.schema.JsonSchemaGenerator;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 回归：@Tool 参数名必须编译进字节码（-parameters），否则 Spring AI 生成的 schema
 * 以 arg0 为键，模型传参无法绑定，工具方法收到 null request。
 */
class ToolSchemaTest {

    @Test
    void toolParametersCompiledWithNames() throws Exception {
        Method method = TroubleshootTools.class.getMethod("queryClsLog", TroubleshootTools.ClsLogQueryRequest.class);
        assertEquals("request", method.getParameters()[0].getName(),
                "参数名未编入字节码：需要 -parameters（maven-compiler-plugin >= 3.2 且 <parameters>true</parameters>）");

        String schema = JsonSchemaGenerator.generateForMethodInput(method);
        assertFalse(schema.contains("arg0"), "工具 schema 出现 arg0 包装键，模型参数将无法绑定: " + schema);
    }

    @Test
    void countToolParametersCompiledWithNames() throws Exception {
        Method method = TroubleshootTools.class.getMethod("countClsLog", TroubleshootTools.ClsLogCountRequest.class);
        assertEquals("request", method.getParameters()[0].getName(),
                "参数名未编入字节码：需要 -parameters（maven-compiler-plugin >= 3.2 且 <parameters>true</parameters>）");

        String schema = JsonSchemaGenerator.generateForMethodInput(method);
        assertFalse(schema.contains("arg0"), "工具 schema 出现 arg0 包装键，模型参数将无法绑定: " + schema);
    }

}
