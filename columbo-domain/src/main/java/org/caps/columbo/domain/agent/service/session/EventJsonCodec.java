package org.caps.columbo.domain.agent.service.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.adk.events.Event;

/**
 * ADK Event 与 JSON 互转（会话持久化统一编解码）。
 * Jdk8Module 处理 Optional 字段，GuavaModule 处理 EventActions.stateDelta 的 ImmutableMap；
 * NON_EMPTY 省略缺席字段，否则回读时 AutoValue builder 收到 null 抛 NPE。
 *
 * @author caps
 */
public final class EventJsonCodec {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new Jdk8Module())
            .registerModule(new GuavaModule())
            .setSerializationInclusion(JsonInclude.Include.NON_EMPTY);

    private EventJsonCodec() {
    }

    public static String write(Event event) {
        try {
            return MAPPER.writeValueAsString(event);
        } catch (Exception e) {
            throw new IllegalStateException("Event 序列化失败: " + e.getMessage(), e);
        }
    }

    public static Event parse(String json) {
        try {
            return MAPPER.readValue(json, Event.class);
        } catch (Exception e) {
            throw new IllegalStateException("Event 反序列化失败: " + e.getMessage(), e);
        }
    }
}
