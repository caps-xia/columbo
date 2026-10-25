package org.caps.columbo.infrastructure.adapter.port.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.caps.columbo.infrastructure.adapter.port.entity.ChatSessionEntity;
import org.caps.columbo.infrastructure.adapter.port.entity.ChatSessionListRow;

import java.util.List;

/**
 * chat_session 表 Mapper。唯一键是 (app_name, session_id) 而非主键，
 * 幂等 upsert 等 MySQL 特有语法按 databaseId 双方言适配（H2 文件库 / MySQL）。
 * 阿里规范：删除为逻辑删除（is_deleted），查询一律过滤；gmt_modified 翻新带 modified_by。
 *
 * @author caps
 */
public interface ChatSessionMapper extends BaseMapper<ChatSessionEntity> {

    /** 登记会话：重复创建同 id 只翻新时间与修改人（MySQL 方言；会话 ID 是 UUID，软删行不会复用撞键） */
    @Insert(value = "INSERT INTO chat_session(app_name, session_id, user_id, create_by, modified_by) "
            + "VALUES(#{appName}, #{sessionId}, #{userId}, #{userId}, #{userId}) "
            + "ON DUPLICATE KEY UPDATE gmt_modified = CURRENT_TIMESTAMP, modified_by = VALUES(modified_by)", databaseId = "mysql")
    @Insert(value = "MERGE INTO chat_session(app_name, session_id, user_id, create_by, modified_by, gmt_modified) "
            + "KEY(app_name, session_id) VALUES(#{appName}, #{sessionId}, #{userId}, #{userId}, #{userId}, CURRENT_TIMESTAMP)", databaseId = "h2")
    int upsert(ChatSessionEntity entity);

    /** 新事件即会话活跃：翻新列表排序用的时间（两方言通用；软删会话不再翻新） */
    @Update("UPDATE chat_session SET gmt_modified = CURRENT_TIMESTAMP "
            + "WHERE app_name = #{appName} AND session_id = #{sessionId} AND is_deleted = 0")
    int touch(@Param("appName") String appName, @Param("sessionId") String sessionId);

    /** 按用户列有效会话（最后活跃倒序，带首条用户事件 JSON 与毫秒时间戳）。双方言差异在时间戳转换：
     *  MySQL 的 UNIX_TIMESTAMP 本身返回 BIGINT；H2 的返回 INT，乘 1000 会溢出，须先 CAST */
    @Select(value = """
            SELECT s.session_id, s.app_name, s.title, s.space,
              (SELECT e.event_json FROM chat_session_event e
                WHERE e.app_name = s.app_name AND e.session_id = s.session_id AND e.author = 'user' AND e.is_deleted = 0
                ORDER BY e.id ASC LIMIT 1) AS first_user,
              UNIX_TIMESTAMP(s.gmt_modified) * 1000 AS updated_ms
            FROM chat_session s WHERE s.user_id = #{userId} AND s.is_deleted = 0
            ORDER BY s.gmt_modified DESC""", databaseId = "mysql")
    @Select(value = """
            SELECT s.session_id, s.app_name, s.title, s.space,
              (SELECT e.event_json FROM chat_session_event e
                WHERE e.app_name = s.app_name AND e.session_id = s.session_id AND e.author = 'user' AND e.is_deleted = 0
                ORDER BY e.id ASC LIMIT 1) AS first_user,
              CAST(UNIX_TIMESTAMP(s.gmt_modified) AS BIGINT) * 1000 AS updated_ms
            FROM chat_session s WHERE s.user_id = #{userId} AND s.is_deleted = 0
            ORDER BY s.gmt_modified DESC""", databaseId = "h2")
    List<ChatSessionListRow> selectUserSessions(@Param("userId") String userId);

    /** 按 (userId, sessionId) 反查有效会话归属的真实 appName：
     *  agent 下线/传错 agentId 时仍能定位到行，避免更新/删除静默匹配 0 行 */
    @Select("SELECT app_name FROM chat_session WHERE user_id = #{userId} AND session_id = #{sessionId} "
            + "AND is_deleted = 0 LIMIT 1")
    String selectAppNameByUserSession(@Param("userId") String userId, @Param("sessionId") String sessionId);

    /** 会话重命名：自定义标题覆盖默认的首条用户消息标题（软删会话不可改名） */
    @Update("UPDATE chat_session SET title = #{title}, gmt_modified = CURRENT_TIMESTAMP, modified_by = #{operator} "
            + "WHERE user_id = #{userId} AND session_id = #{sessionId} AND is_deleted = 0")
    int renameSession(@Param("userId") String userId, @Param("sessionId") String sessionId,
                      @Param("title") String title, @Param("operator") String operator);

    /** 会话移动到归档空间；空串归位未归档（软删会话不可移动） */
    @Update("UPDATE chat_session SET space = NULLIF(#{space}, ''), gmt_modified = CURRENT_TIMESTAMP, modified_by = #{operator} "
            + "WHERE user_id = #{userId} AND session_id = #{sessionId} AND is_deleted = 0")
    int moveSession(@Param("userId") String userId, @Param("sessionId") String sessionId,
                    @Param("space") String space, @Param("operator") String operator);

    /** 逻辑删除（阿里规范不物理删行）：is_deleted=1，operator 记入 modified_by */
    @Update("UPDATE chat_session SET is_deleted = 1, gmt_modified = CURRENT_TIMESTAMP, modified_by = #{operator} "
            + "WHERE app_name = #{appName} AND session_id = #{sessionId} AND is_deleted = 0")
    int markDeleted(@Param("appName") String appName, @Param("sessionId") String sessionId,
                    @Param("operator") String operator);
}
