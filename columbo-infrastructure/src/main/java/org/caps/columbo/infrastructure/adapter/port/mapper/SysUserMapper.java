package org.caps.columbo.infrastructure.adapter.port.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.caps.columbo.infrastructure.adapter.port.entity.SysUserEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * sys_user 表 Mapper（复用会话库 SqlSessionFactory；查询一律过滤逻辑删除）。
 */
public interface SysUserMapper extends BaseMapper<SysUserEntity> {

    /** 按工号查有效用户（逻辑删除的不参与登录/查重） */
    @Select("SELECT * FROM sys_user WHERE username = #{username} AND is_deleted = 0 LIMIT 1")
    SysUserEntity selectByUsername(@Param("username") String username);

    @Select("SELECT COUNT(*) FROM sys_user WHERE is_deleted = 0")
    long countActive();

    /** 新增用户（审计字段齐全；gmt 双时间走列默认值） */
    @Insert("INSERT INTO sys_user(username, name, password_hash, create_by, modified_by) "
            + "VALUES(#{username}, #{name}, #{passwordHash}, #{operator}, #{operator})")
    int insertUser(@Param("username") String username, @Param("name") String name,
                   @Param("passwordHash") String passwordHash, @Param("operator") String operator);
}
