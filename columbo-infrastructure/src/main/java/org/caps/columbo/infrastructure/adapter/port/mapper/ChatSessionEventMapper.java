package org.caps.columbo.infrastructure.adapter.port.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.caps.columbo.infrastructure.adapter.port.entity.ChatSessionEventEntity;

import java.util.List;

/**
 * chat_session_event 表 Mapper（append-only：只增不改，author 即创建人，无 modified_by）。
 * 阿里规范：删除为逻辑删除（is_deleted，随会话软删），查询一律过滤。
 *
 * @author caps
 */
public interface ChatSessionEventMapper extends BaseMapper<ChatSessionEventEntity> {

    /** uk_event 幂等去重：恢复竞态下同一事件可能被追加两次（MySQL：INSERT IGNORE；H2：MERGE 同值幂等） */
    @Insert(value = "INSERT IGNORE INTO chat_session_event(app_name, session_id, event_id, author, event_json) "
            + "VALUES(#{appName}, #{sessionId}, #{eventId}, #{author}, #{eventJson})", databaseId = "mysql")
    @Insert(value = "MERGE INTO chat_session_event(app_name, session_id, event_id, author, event_json) "
            + "KEY(app_name, session_id, event_id) "
            + "VALUES(#{appName}, #{sessionId}, #{eventId}, #{author}, #{eventJson})", databaseId = "h2")
    int insertIgnore(ChatSessionEventEntity entity);

    /** 按落库顺序返回该会话全部有效事件 JSON（软删的随会话一并隐藏） */
    @Select("SELECT event_json FROM chat_session_event "
            + "WHERE app_name = #{appName} AND session_id = #{sessionId} AND is_deleted = 0 ORDER BY id ASC")
    List<String> selectEventJsons(@Param("appName") String appName, @Param("sessionId") String sessionId);

    /** 逻辑删除（随会话）：is_deleted=1 */
    @Update("UPDATE chat_session_event SET is_deleted = 1 "
            + "WHERE app_name = #{appName} AND session_id = #{sessionId} AND is_deleted = 0")
    int markDeleted(@Param("appName") String appName, @Param("sessionId") String sessionId);
}
