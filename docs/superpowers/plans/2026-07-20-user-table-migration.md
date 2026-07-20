# 用户表与基础迁移 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 创建用户表（user）及其实体类、Mapper 接口，使用 Flyway 自动执行数据库迁移

**Architecture:** 使用 Flyway 管理数据库版本化迁移，MyBatis-Plus 提供 ORM 能力和逻辑删除支持，MyMetaObjectHandler 自动填充时间字段

**Tech Stack:** Spring Boot 3.5.16, MyBatis-Plus 3.5.17, Flyway, MySQL 8.0+, Lombok

## Global Constraints

- 所有代码必须包含必要的中文注释，重点注释复杂逻辑、关键流程、边界条件
- 使用 Java 21 语法
- 遵循 Spring Boot 3.x 规范
- 数据库字段使用下划线命名，Java 字段使用驼峰命名
- 所有时间字段使用 LocalDateTime 类型
- 软删除字段 deleted: 0=未删除，1=已删除

---

## File Structure

### Files to Create
- `smartview-server/src/main/java/com/smartview/user/entity/User.java` — 用户实体类
- `smartview-server/src/main/java/com/smartview/user/mapper/UserMapper.java` — 用户数据访问接口

### Files Already Exist (Verify Only)
- `smartview-server/src/main/resources/db/migration/V1__create_user_table.sql` — 用户表迁移脚本（已存在）
- `smartview-server/src/main/java/com/smartview/config/MyMetaObjectHandler.java` — 字段自动填充处理器（已存在）
- `smartview-server/src/main/java/com/smartview/config/MybatisPlusConfig.java` — MyBatis-Plus 配置（已存在）
- `smartview-server/src/main/resources/application.yml` — Flyway 配置（已存在）

---

### Task 1: 创建用户实体类

**Files:**
- Create: `smartview-server/src/main/java/com/smartview/user/entity/User.java`

**Interfaces:**
- Consumes: 无（基础实体类）
- Produces: 
  - `User` 实体类，包含所有用户表字段
  - 字段：`Long id`, `String username`, `String passwordHash`, `String nickname`, `String email`, `String phone`, `String status`, `LocalDateTime lastLoginAt`, `LocalDateTime createdAt`, `LocalDateTime updatedAt`, `Integer deleted`

- [ ] **Step 1: 创建 User 实体类**

创建文件 `smartview-server/src/main/java/com/smartview/user/entity/User.java`：

```java
package com.smartview.user.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户实体类
 *
 * 功能说明：
 * - 映射数据库 user 表
 * - 支持软删除（deleted 字段配合 @TableLogic）
 * - 支持字段自动填充（createdAt 和 updatedAt 配合 MyMetaObjectHandler）
 *
 * 技术要点：
 * - @TableName("user")：映射到 user 表
 * - @TableId(type = IdType.AUTO)：主键自增策略
 * - @TableLogic：标记 deleted 字段为逻辑删除字段（0=未删除，1=已删除）
 * - @TableField(fill = FieldFill.INSERT)：插入时自动填充 createdAt
 * - @TableField(fill = FieldFill.INSERT_UPDATE)：插入和更新时自动填充 updatedAt
 *
 * 字段映射：
 * - Java 驼峰命名自动映射到数据库下划线命名（由 MyBatis-Plus 配置）
 * - 例如：createdAt -> created_at, passwordHash -> password_hash
 *
 * @author SmartView Team
 * @since 2026-07-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("user")
public class User {

    /**
     * 用户 ID，主键，数据库自增
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 登录用户名，要求全局唯一（数据库唯一索引约束）
     */
    private String username;

    /**
     * 加密后的密码哈希值
     * 使用 BCrypt 算法加密，禁止存储明文密码
     */
    private String passwordHash;

    /**
     * 用户昵称，用于前端展示
     */
    private String nickname;

    /**
     * 用户邮箱，可选字段
     * 如果填写则要求唯一（仅对未删除用户生效，通过数据库组合唯一索引实现）
     */
    private String email;

    /**
     * 用户手机号，可选字段
     * 如果填写则要求唯一（仅对未删除用户生效，通过数据库组合唯一索引实现）
     */
    private String phone;

    /**
     * 用户状态：ACTIVE=正常，DISABLED=禁用，LOCKED=锁定
     * 禁用和锁定的用户无法登录系统
     */
    private String status;

    /**
     * 最近一次登录时间，用于统计用户活跃度
     */
    private LocalDateTime lastLoginAt;

    /**
     * 记录创建时间
     * 由 MyMetaObjectHandler 在插入时自动填充，业务代码无需手动设置
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 记录最后更新时间
     * 由 MyMetaObjectHandler 在插入和更新时自动填充，业务代码无需手动设置
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 软删除标记：0=未删除，1=已删除
     * 配合 @TableLogic 注解实现逻辑删除：
     * - 查询时自动添加 deleted=0 条件
     * - 调用 deleteById 时执行 UPDATE 而非 DELETE
     */
    @TableLogic
    private Integer deleted;
}
```

- [ ] **Step 2: 编译验证**

运行编译命令，确保代码无语法错误：

```bash
cd E:/AllProject/SmartView/smartview-server
mvn compile -DskipTests
```

预期输出：`BUILD SUCCESS`

- [ ] **Step 3: 提交代码**

```bash
git add smartview-server/src/main/java/com/smartview/user/entity/User.java
git commit -m "feat: 创建用户实体类 User

- 映射 user 表的所有字段
- 配置主键自增策略
- 配置逻辑删除字段（deleted）
- 配置字段自动填充（createdAt 和 updatedAt）
- 添加详细的中文注释说明字段用途和技术要点

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: 创建用户 Mapper 接口

**Files:**
- Create: `smartview-server/src/main/java/com/smartview/user/mapper/UserMapper.java`

**Interfaces:**
- Consumes: 
  - `User` 实体类（来自 Task 1）
- Produces:
  - `UserMapper` 接口，继承 `BaseMapper<User>`
  - 自动提供 CRUD 方法：`insert()`, `deleteById()`, `updateById()`, `selectById()`, `selectList()` 等

- [ ] **Step 1: 创建 UserMapper 接口**

创建文件 `smartview-server/src/main/java/com/smartview/user/mapper/UserMapper.java`：

```java
package com.smartview.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartview.user.entity.User;
import org.apache.ibatis.annotations.Mapper;

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
    // 继承 BaseMapper 后自动获得 CRUD 方法，无需手动定义
    // 如需自定义查询方法，可在此添加方法声明
}
```

- [ ] **Step 2: 编译验证**

运行编译命令，确保代码无语法错误：

```bash
cd E:/AllProject/SmartView/smartview-server
mvn compile -DskipTests
```

预期输出：`BUILD SUCCESS`

- [ ] **Step 3: 提交代码**

```bash
git add smartview-server/src/main/java/com/smartview/user/mapper/UserMapper.java
git commit -m "feat: 创建用户 Mapper 接口

- 继承 MyBatis-Plus 的 BaseMapper<User>
- 自动获得 CRUD 方法（insert, delete, update, select）
- 支持逻辑删除和字段自动填充
- 添加详细的中文注释说明接口用途和自动提供的方法

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: 验证数据库迁移和功能

**Files:**
- Verify: `smartview-server/src/main/resources/db/migration/V1__create_user_table.sql`
- Verify: `smartview-server/src/main/resources/application.yml`

**Interfaces:**
- Consumes:
  - `User` 实体类（来自 Task 1）
  - `UserMapper` 接口（来自 Task 2）
  - Flyway 配置（已存在）
  - MyMetaObjectHandler（已存在）
- Produces:
  - 数据库中创建的 user 表
  - 验证通过的迁移和功能

- [ ] **Step 1: 确认 Docker 基础设施运行**

确保 MySQL 数据库正在运行：

```bash
cd E:/AllProject/SmartView/smartview-infra
docker-compose ps
```

预期输出：包含 `mysql` 服务且状态为 `Up`

如果未运行，启动基础设施：

```bash
docker-compose up -d mysql
```

- [ ] **Step 2: 启动 Spring Boot 应用**

启动应用，观察 Flyway 迁移日志：

```bash
cd E:/AllProject/SmartView/smartview-server
mvn spring-boot:run
```

预期日志（Flyway 执行迁移）：
```
Flyway Community Edition 9.x.x by Redgate
Database: jdbc:mysql://localhost:3306/smartview (MySQL 8.0)
Successfully validated 1 migration (execution time 00:00.015s)
Creating Schema History table `smartview`.`flyway_schema_history` ...
Current version of schema `smartview`: << Empty Schema >>
Migrating schema `smartview` to version "1 - create user table"
Successfully applied 1 migration to schema `smartview`, now at version v1
```

预期日志（应用启动成功）：
```
Started SmartViewServerApplication in X.XXX seconds
```

如果启动失败，检查：
- MySQL 连接配置（application.yml）
- 数据库是否存在（smartview）
- 迁移脚本语法是否正确

- [ ] **Step 3: 验证表结构**

连接数据库，验证 user 表是否创建成功：

```bash
# 使用 MySQL 客户端连接
mysql -h localhost -P 3306 -u smartview -psmartview_password smartview
```

执行 SQL 验证：

```sql
-- 查看表结构
SHOW CREATE TABLE user;

-- 预期输出：包含所有字段（id, username, password_hash, nickname, email, phone, status, last_login_at, created_at, updated_at, deleted）

-- 查看索引
SHOW INDEX FROM user;

-- 预期输出：7 个索引
-- PRIMARY (id)
-- uk_username (username)
-- uk_email_deleted (email, deleted)
-- uk_phone_deleted (phone, deleted)
-- idx_status (status)
-- idx_deleted (deleted)
-- idx_created_at (created_at)

-- 查看迁移历史
SELECT * FROM flyway_schema_history;

-- 预期输出：一条记录，version=1, description='create user table', success=1
```

- [ ] **Step 4: 测试唯一约束**

验证 username 唯一约束是否生效：

```sql
-- 插入测试数据
INSERT INTO user (username, password_hash, nickname, status) 
VALUES ('testuser', '$2a$10$...', '测试用户', 'ACTIVE');

-- 预期：插入成功

-- 尝试插入重复 username
INSERT INTO user (username, password_hash, nickname, status) 
VALUES ('testuser', '$2a$10$...', '测试用户2', 'ACTIVE');

-- 预期：报错 Duplicate entry 'testuser' for key 'uk_username'

-- 清理测试数据
DELETE FROM user WHERE username = 'testuser';
```

- [ ] **Step 5: 测试软删除**

验证逻辑删除是否生效（需要编写简单的测试代码或使用 MyBatis-Plus 提供的方法）：

创建临时测试文件（可选，用于手动验证）：

```java
// 临时测试代码（不提交）
// 在 SmartViewServerApplication 中添加 @PostConstruct 方法进行测试

@Autowired
private UserMapper userMapper;

@PostConstruct
public void testSoftDelete() {
    // 插入测试数据
    User user = User.builder()
            .username("deletetest")
            .passwordHash("hash")
            .status("ACTIVE")
            .build();
    userMapper.insert(user);
    Long userId = user.getId();
    
    // 逻辑删除
    userMapper.deleteById(userId);
    
    // 查询（应该查不到，因为 deleted=1）
    User deletedUser = userMapper.selectById(userId);
    System.out.println("查询结果（应为 null）: " + deletedUser);
    
    // 直接查数据库验证 deleted 字段
    // SELECT * FROM user WHERE id = userId;
    // 预期：deleted = 1
}
```

或使用 SQL 直接验证：

```sql
-- 插入测试数据
INSERT INTO user (username, password_hash, status) 
VALUES ('deletetest', 'hash', 'ACTIVE');

-- 记录 ID
SET @test_id = LAST_INSERT_ID();

-- 模拟逻辑删除（手动执行 MyBatis-Plus 会执行的 SQL）
UPDATE user SET deleted = 1 WHERE id = @test_id;

-- 验证 deleted 字段
SELECT id, username, deleted FROM user WHERE id = @test_id;
-- 预期：deleted = 1

-- 清理测试数据
DELETE FROM user WHERE id = @test_id;
```

- [ ] **Step 6: 测试字段自动填充**

验证 createdAt 和 updatedAt 是否自动填充：

```sql
-- 插入测试数据（不指定 created_at 和 updated_at）
INSERT INTO user (username, password_hash, status) 
VALUES ('timetest', 'hash', 'ACTIVE');

-- 查询验证时间字段
SELECT id, username, created_at, updated_at 
FROM user 
WHERE username = 'timetest';

-- 预期：created_at 和 updated_at 都有值，且接近当前时间

-- 更新记录
UPDATE user SET nickname = '更新昵称' WHERE username = 'timetest';

-- 再次查询验证 updated_at 是否刷新
SELECT id, username, created_at, updated_at 
FROM user 
WHERE username = 'timetest';

-- 预期：updated_at 时间晚于 created_at

-- 清理测试数据
DELETE FROM user WHERE username = 'timetest';
```

- [ ] **Step 7: 文档化验收结果**

在 `docs/plans/task2.1_user_table_plan.md` 末尾追加验收结果：

```markdown
## 验收结果

**执行时间：** 2026-07-20

### ✅ 应用启动后自动创建/迁移用户表
- Flyway 成功执行 V1__create_user_table.sql
- user 表创建成功，包含所有字段
- flyway_schema_history 表记录迁移历史

### ✅ 用户名唯一约束生效
- 插入重复 username 时抛出 Duplicate entry 错误
- 数据库 uk_username 索引生效

### ✅ 软删除字段存在且生效
- deleted 字段默认为 0
- 逻辑删除后 deleted 更新为 1
- MyBatis-Plus 查询自动过滤 deleted=1 的记录

### ✅ 字段自动填充生效
- 插入记录时 createdAt 和 updatedAt 自动填充
- 更新记录时 updatedAt 自动刷新

**验收结论：** Task 2.1 所有验收标准通过，用户表及基础迁移功能正常。
```

- [ ] **Step 8: 提交文档**

```bash
git add docs/plans/task2.1_user_table_plan.md
git commit -m "docs: 添加 Task 2.1 验收结果

- 记录 Flyway 迁移成功
- 验证唯一约束生效
- 验证软删除功能
- 验证字段自动填充

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Plan Self-Review

### Spec Coverage
- ✅ 创建 user 表 → Task 3 验证迁移
- ✅ 建立通用字段（id, created_at, updated_at, deleted） → Task 1 实体类包含所有字段
- ✅ 使用迁移脚本管理表结构 → Task 3 使用 Flyway
- ✅ 验收标准 1（应用启动后自动创建表） → Task 3 Step 2
- ✅ 验收标准 2（用户名唯一约束） → Task 3 Step 4
- ✅ 验收标准 3（软删除字段） → Task 3 Step 5

### Placeholder Scan
- ✅ 无 TBD, TODO, "fill in details"
- ✅ 无 "add appropriate error handling"（无需额外错误处理，数据库约束自动生效）
- ✅ 所有代码步骤都包含完整代码
- ✅ 所有命令都包含预期输出

### Type Consistency
- ✅ User 实体类字段类型一致
- ✅ UserMapper 继承 BaseMapper<User> 类型匹配
- ✅ 所有时间字段使用 LocalDateTime
- ✅ 所有字符串字段使用 String
- ✅ deleted 字段使用 Integer（匹配数据库 TINYINT）

---

## Execution Complete

实现计划已完成并保存到 `docs/superpowers/plans/2026-07-20-user-table-migration.md`。
