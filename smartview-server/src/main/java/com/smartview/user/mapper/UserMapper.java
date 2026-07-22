package com.smartview.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartview.user.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 用户 Mapper 接口
 *
 * 功能说明：
 * - 提供用户表的 CRUD 操作
 * - 继承 MyBatis-Plus 的 BaseMapper，自动获得基础方法
 * - 无需编写 XML 文件，简单查询由 MyBatis-Plus 自动实现
 *
 * 自动提供的方法（示例）：
 * - insert(User user)：插入一条记录
 * - deleteById(Long id)：根据 ID 逻辑删除（软删除）
 * - updateById(User user)：根据 ID 更新记录
 * - selectById(Long id)：根据 ID 查询记录（自动过滤 deleted=1）
 * - selectList(Wrapper<User> wrapper)：根据条件查询列表
 *
 * 技术要点：
 * - @Mapper 注解标记为 MyBatis Mapper（配合 @MapperScan 自动扫描）
 * - 继承 BaseMapper<User> 自动获得 CRUD 能力
 * - MyBatis-Plus 自动处理逻辑删除，查询时自动添加 deleted=0 条件
 * - 如需复杂查询，可使用 MyBatis-Plus 的 QueryWrapper 或编写自定义方法
 *
 * 扫描配置：
 * - MybatisPlusConfig 已配置 @MapperScan("com.smartview.*.mapper")
 * - 本接口位于 com.smartview.user.mapper 包，会被自动扫描
 *
 * @author SmartView Team
 * @since 2026-07-20
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 仅在账号仍可登录时更新最近登录时间。
     *
     * <p>状态和逻辑删除条件必须与更新时间写入处于同一条 SQL 中，避免管理员并发禁用账号后，
     * 登录流程使用旧实体执行全字段更新并把账号状态错误恢复为 ACTIVE。</p>
     *
     * @return 更新行数；返回 0 表示账号状态已变化，不得继续签发令牌
     */
    @Update("""
            UPDATE `user`
            SET last_login_at = #{lastLoginAt}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{userId}
              AND status = 'ACTIVE'
              AND deleted = 0
            """)
    int updateLastLoginAtIfActive(
            @Param("userId") Long userId,
            @Param("lastLoginAt") LocalDateTime lastLoginAt
    );
}
