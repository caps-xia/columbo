package org.caps.columbo.infrastructure.adapter.port.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.caps.columbo.infrastructure.adapter.port.entity.ChatSessionTokenEntity;

/**
 * chat_session_token 表 Mapper：按会话累计的模型调用用量。
 * 累加走「UPDATE 命中 0 行再 INSERT」的双方言通用写法（不依赖方言特有的 upsert 语法）；
 * 并发首写撞 uk 时退回再累加一次。
 *
 * @author caps
 */
public interface ChatSessionTokenMapper extends BaseMapper<ChatSessionTokenEntity> {

    /** 列累加（行存在时）；gmt_modified 双方言均可直接翻新 */
    @Update("UPDATE chat_session_token SET "
            + "input_tokens = input_tokens + #{input}, output_tokens = output_tokens + #{output}, "
            + "cached_tokens = cached_tokens + #{cached}, total_tokens = total_tokens + #{total}, "
            + "llm_calls = llm_calls + #{calls}, gmt_modified = CURRENT_TIMESTAMP "
            + "WHERE app_name = #{appName} AND session_id = #{sessionId}")
    int accumulate(@Param("appName") String appName, @Param("sessionId") String sessionId,
                   @Param("input") long input, @Param("output") long output,
                   @Param("cached") long cached, @Param("total") long total, @Param("calls") long calls);

    /** 首写（行不存在时）；撞 uk 由调用方退回累加 */
    @Insert("INSERT INTO chat_session_token(app_name, session_id, input_tokens, output_tokens, "
            + "cached_tokens, total_tokens, llm_calls) "
            + "VALUES(#{appName}, #{sessionId}, #{input}, #{output}, #{cached}, #{total}, #{calls})")
    int insertUsage(@Param("appName") String appName, @Param("sessionId") String sessionId,
                    @Param("input") long input, @Param("output") long output,
                    @Param("cached") long cached, @Param("total") long total, @Param("calls") long calls);

    @Select("SELECT * FROM chat_session_token "
            + "WHERE app_name = #{appName} AND session_id = #{sessionId} LIMIT 1")
    ChatSessionTokenEntity selectBySession(@Param("appName") String appName, @Param("sessionId") String sessionId);

    /** 会话删除时物理清理（派生数据，无审计价值） */
    @Delete("DELETE FROM chat_session_token WHERE app_name = #{appName} AND session_id = #{sessionId}")
    int deleteBySession(@Param("appName") String appName, @Param("sessionId") String sessionId);
}
