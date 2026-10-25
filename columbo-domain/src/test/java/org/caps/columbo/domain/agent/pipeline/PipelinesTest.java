package org.caps.columbo.domain.agent.pipeline;

import org.caps.columbo.domain.agent.model.valobj.properties.PipelineProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 处理器链框架单测：顺序执行/上下文改写可见、中止语义、未配置跳过、坏配置报错。
 *
 * @author caps
 */
class PipelinesTest {

    private Pipelines pipelines;
    private PipelineFactory factory;
    private ApplicationContext context;

    @BeforeEach
    void setUp() {
        factory = new PipelineFactory();
        context = Mockito.mock(ApplicationContext.class);
        ReflectionTestUtils.setField(factory, "properties", new PipelineProperties());
        ReflectionTestUtils.setField(factory, "context", context);
        pipelines = new Pipelines();
        ReflectionTestUtils.setField(pipelines, "pipelineFactory", factory);
    }

    private void configChain(Map<String, List<String>> chain) {
        PipelineProperties properties = new PipelineProperties();
        properties.setChain(chain);
        ReflectionTestUtils.setField(factory, "properties", properties);
    }

    @Test
    void 处理器按序执行且上下文改写对后续节点可见() {
        PipelineProcessor<ChatPipelineContext> first = ctx -> {
            ctx.setMessage(ctx.getMessage() + "|p1");
            return true;
        };
        PipelineProcessor<ChatPipelineContext> second = ctx -> {
            ctx.setMessage(ctx.getMessage() + "|p2");
            return true;
        };
        Mockito.when(context.getBean("p1")).thenReturn(first);
        Mockito.when(context.getBean("p2")).thenReturn(second);
        configChain(Map.of("chat", List.of("p1", "p2")));

        ChatPipelineContext ctx = ChatPipelineContext.builder().message("hello").build();
        assertTrue(pipelines.doExecute("chat", ctx));
        assertEquals("hello|p1|p2", ctx.getMessage());
    }

    @Test
    void 处理器返回false中止后续节点() {
        AtomicInteger secondCalls = new AtomicInteger();
        Mockito.when(context.getBean("stop")).thenReturn((PipelineProcessor<ChatPipelineContext>) ctx -> false);
        Mockito.when(context.getBean("after")).thenReturn((PipelineProcessor<ChatPipelineContext>) ctx -> {
            secondCalls.incrementAndGet();
            return true;
        });
        configChain(Map.of("chat", List.of("stop", "after")));

        assertFalse(pipelines.doExecute("chat", ChatPipelineContext.builder().build()));
        assertEquals(0, secondCalls.get(), "中止后不应执行后续节点");
    }

    @Test
    void 链未配置时静默通过() {
        assertTrue(pipelines.doExecute("chat", ChatPipelineContext.builder().message("m").build()));
    }

    @Test
    void 链引用不存在的Bean时报清晰错误() {
        Mockito.when(context.getBean("ghost")).thenThrow(new org.springframework.beans.factory.NoSuchBeanDefinitionException("ghost"));
        configChain(Map.of("chat", List.of("ghost")));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> pipelines.doExecute("chat", ChatPipelineContext.builder().build()));
        assertTrue(e.getMessage().contains("ghost"));
    }
}
