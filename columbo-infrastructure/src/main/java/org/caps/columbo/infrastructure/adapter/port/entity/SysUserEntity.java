package org.caps.columbo.infrastructure.adapter.port.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * sys_user 表实体（登录用户，按阿里规范含审计与逻辑删除字段）。
 * H2 方言无 ON UPDATE，gmt_modified 由更新 SQL 显式翻新（同 chat_session.touch 模式）。
 */
@Data
@TableName("sys_user")
public class SysUserEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 工号（登录名，同时作为会话归属 userId） */
    private String username;

    /** 姓名 */
    private String name;

    /** BCrypt 哈希 */
    private String passwordHash;

    /** 逻辑删除：0-否，1-是 */
    private Integer isDeleted;

    /** 创建人（系统种子=system，注册=本人） */
    private String createBy;

    /** 修改人 */
    private String modifiedBy;

    /** 创建时间 */
    private LocalDateTime gmtCreate;

    /** 更新时间 */
    private LocalDateTime gmtModified;
}
