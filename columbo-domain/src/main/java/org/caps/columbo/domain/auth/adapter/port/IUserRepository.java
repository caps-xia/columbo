package org.caps.columbo.domain.auth.adapter.port;

import org.caps.columbo.domain.auth.model.entity.AuthUser;

import java.util.Optional;

/**
 * 用户仓储端口：按工号取用户（实现走 sys_user 表，infrastructure 提供）。
 */
public interface IUserRepository {

    /** 按工号查用户（逻辑删除的不算）；不存在返回 empty */
    Optional<AuthUser> findByUsername(String username);

    /** 工号是否已被占用（注册查重） */
    boolean existsByUsername(String username);

    /** 新增用户（operator 记入 create_by/modified_by 审计字段） */
    void save(AuthUser user, String operator);
}
