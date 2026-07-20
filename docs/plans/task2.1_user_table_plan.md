# Task 2.1 — 创建用户表与基础迁移设计文档

**日期：** 2026-07-20  
**状态：** 已批准  
**作者：** SmartView Team

---

## 1. 概述

本文档描述 Task 2.1 的设计方案，目标是创建 `user` 表，建立通用字段（`id`、`created_at`、`updated_at`、`deleted`），并使用迁移脚本管理表结构。

### 1.1 验收标准

1. 应用启动后可自动创建或迁移用户表
2. 用户名唯一约束生效
3. 软删除字段存在且生效

---

## 2. 整体架构设计

### 2.1 技术栈

- **数据库迁移工具：** Flyway（已配置）
- **ORM 框架：** MyBatis-Plus 3.5.17
- **数据库：** MySQL 8.0+
- **开发辅助：** Lombok

### 2.2 分层架构

```
┌─────────────────────────────────┐
│  数据库迁移层（Flyway）          │
│  - V1__create_user_table.sql    │
└────────────┬────────────────────┘
             │
┌────────────▼────────────────────┐
│  实体层（Entity）                │
│  - User.java                    │
└────────────┬────────────────────┘
             │
┌────────────▼────────────────────┐
│  数据访问层（Mapper）            │
│  - UserMapper.java              │
└─────────────────────────────────┘
```

### 2.3 核心设计决策

**使用现有基础设施：** 项目已配置 Flyway、MyBatis-Plus 和 `MyMetaObjectHandler`，本任务将验证和完善现有实现，而非从零开始。

**主键策略：** 使用 `BIGINT AUTO_INCREMENT`，由数据库生成 ID。

**时间字段处理：** 使用 `LocalDateTime` 类型，配合 `MyMetaObjectHandler` 自动填充 `createdAt` 和 `updatedAt`。

**软删除机制：** `deleted` 字段（`TINYINT(1)`）配合 MyBatis-Plus 的 `@TableLogic` 注解实现逻辑删除。

---

## 3. 数据库表设计

### 3.1 用户表结构

| 字段            | 类型           | 约束                      | 说明                                   |
| --------------- | -------------- | ------------------------- | -------------------------------------- |
| `id`            | BIGINT         | PRIMARY KEY AUTO_INCREMENT | 用户 ID                                |
| `username`      | VARCHAR(50)    | NOT NULL, UNIQUE          | 登录用户名                             |
| `password_hash` | VARCHAR(255)   | NOT NULL                  | 加密后的密码（BCrypt）                 |
| `nickname`      | VARCHAR(100)   | NULL                      | 用户昵称                               |
| `email`         | VARCHAR(100)   | NULL                      | 用户邮箱                               |
| `phone`         | VARCHAR(20)    | NULL                      | 用户手机号                             |
| `status`        | VARCHAR(20)    | NOT NULL DEFAULT 'ACTIVE' | 用户状态（ACTIVE/DISABLED/LOCKED）     |
| `last_login_at` | DATETIME       | NULL                      | 最近登录时间                           |
| `created_at`    | DATETIME       | NOT NULL DEFAULT NOW()    | 创建时间                               |
| `updated_at`    | DATETIME       | NOT NULL DEFAULT NOW() ON UPDATE NOW() | 更新时间 |
| `deleted`       | TINYINT(1)     | NOT NULL DEFAULT 0        | 软删除标记（0=未删除，1=已删除）       |

### 3.2 索引设计

| 索引名              | 类型   | 字段              | 说明                                   |
| ------------------- | ------ | ----------------- | -------------------------------------- |
| `PRIMARY`           | 主键   | `id`              | 主键索引                               |
| `uk_username`       | 唯一   | `username`        | 用户名唯一索引，用于登录查询           |
| `uk_email_deleted`  | 唯一   | `email`, `deleted` | 邮箱唯一索引（仅对未删除用户生效）     |
| `uk_phone_deleted`  | 唯一   | `phone`, `deleted` | 手机号唯一索引（仅对未删除用户生效）   |
| `idx_status`        | 普通   | `status`          | 状态索引，用于查询特定状态用户         |
| `idx_deleted`       | 普通   | `deleted`         | 软删除索引，MyBatis-Plus 逻辑删除依赖  |
| `idx_created_at`    | 普通   | `created_at`      | 创建时间索引，用于排序和统计           |

### 3.3 索引设计说明

**邮箱和手机号唯一约束：**
- 使用 `(email, deleted)` 和 `(phone, deleted)` 组合唯一索引
- MySQL 不支持部分索引（WHERE 子句），因此使用组合索引模拟
- 软删除后的邮箱和手机号可以被新用户重复使用
- 应用层需确保 `email` 为 NULL 或 `deleted=0` 时才校验唯一性

---

## 4. 实体类设计

### 4.1 User.java 实体类

**文件路径：** `smartview-server/src/main/java/com/smartview/user/entity/User.java`

**设计要点：**

1. **表映射**
   - 使用 `@TableName("user")` 映射到 user 表
   - 使用 `@TableId(type = IdType.AUTO)` 配置主键自增

2. **字段映射**
   - 所有字段使用驼峰命名（Java 规范）
   - MyBatis-Plus 自动转换为下划线（数据库规范）
   - `createdAt` 使用 `@TableField(fill = FieldFill.INSERT)` 标记插入时自动填充
   - `updatedAt` 使用 `@TableField(fill = FieldFill.INSERT_UPDATE)` 标记插入和更新时自动填充
   - `deleted` 使用 `@TableLogic` 标记为逻辑删除字段

3. **数据类型选择**
   - `id`: `Long`（对应 BIGINT）
   - `username`, `passwordHash`, `nickname`, `email`, `phone`, `status`: `String`
   - `lastLoginAt`, `createdAt`, `updatedAt`: `LocalDateTime`（对应 DATETIME）
   - `deleted`: `Integer`（对应 TINYINT，0=未删除，1=已删除）

4. **Lombok 注解**
   - `@Data`：自动生成 getter/setter/toString/equals/hashCode
   - `@Builder`：支持构建器模式
   - `@NoArgsConstructor`, `@AllArgsConstructor`：生成构造函数
   - `@TableName("user")`：映射表名

### 4.2 字段自动填充机制

**已有的 `MyMetaObjectHandler` 配置：**
- 插入时自动填充 `createdAt` 和 `updatedAt` 为当前时间
- 更新时自动填充 `updatedAt` 为当前时间
- 使用 `strictInsertFill` 和 `strictUpdateFill`，仅当字段值为 null 时才填充

**实体类配合：**
- `createdAt` 字段使用 `@TableField(fill = FieldFill.INSERT)`
- `updatedAt` 字段使用 `@TableField(fill = FieldFill.INSERT_UPDATE)`

---

## 5. 数据访问层设计

### 5.1 UserMapper.java 接口

**文件路径：** `smartview-server/src/main/java/com/smartview/user/mapper/UserMapper.java`

**设计要点：**

1. **继承 BaseMapper**
   - `UserMapper extends BaseMapper<User>`
   - 自动获得 CRUD 方法：insert、deleteById、updateById、selectById、selectList 等
   - MyBatis-Plus 自动处理逻辑删除（查询时自动添加 `deleted=0` 条件）

2. **Mapper 扫描配置**
   - 已有的 `MybatisPlusConfig` 配置了 `@MapperScan("com.smartview.*.mapper")`
   - UserMapper 应放在 `com.smartview.user.mapper` 包下，会被自动扫描

3. **注解方式**
   - 使用 `@Mapper` 注解（可选，因为已有 MapperScan）
   - 简单查询无需 XML 文件

### 5.2 目录结构

```
smartview-server/src/main/java/com/smartview/user/
├── entity/
│   └── User.java
└── mapper/
    └── UserMapper.java
```

---

## 6. 数据库迁移脚本

### 6.1 Flyway 配置

**已有配置（application.yml）：**
```yaml
spring:
  flyway:
    enabled: true
    baseline-on-migrate: true
    locations: classpath:db/migration
    baseline-version: 0
    encoding: UTF-8
    validate-on-migrate: true
```

### 6.2 迁移脚本

**文件路径：** `smartview-server/src/main/resources/db/migration/V1__create_user_table.sql`

**状态：** ✅ 已存在

**内容：**
- 创建 `user` 表
- 创建 6 个索引（主键、username 唯一索引、email+deleted 组合索引、phone+deleted 组合索引、status 索引、deleted 索引、created_at 索引）
- 详细的中文注释说明每个字段和索引的用途

---

## 7. 验收方案

### 7.1 验收标准

1. **应用启动后自动创建/迁移用户表**
   - Flyway 在应用启动时自动执行 `V1__create_user_table.sql`
   - 数据库中存在 `user` 表及所有索引
   - `flyway_schema_history` 表记录迁移历史

2. **用户名唯一约束生效**
   - 插入重复 username 时抛出 `DuplicateKeyException`
   - MyBatis-Plus 或应用层可捕获并处理

3. **软删除字段存在且生效**
   - `deleted` 字段默认为 0
   - MyBatis-Plus 查询时自动添加 `deleted=0` 条件
   - 调用 `deleteById` 执行 `UPDATE user SET deleted=1` 而非物理删除

4. **字段自动填充生效**
   - 插入记录时 `createdAt` 和 `updatedAt` 自动填充当前时间
   - 更新记录时 `updatedAt` 自动刷新

### 7.2 测试方案

#### 7.2.1 启动测试

1. 清空数据库 `smartview` 的所有表
2. 启动 Spring Boot 应用
3. 检查 `user` 表是否创建成功
4. 查看日志确认 Flyway 执行成功

**预期日志：**
```
Flyway Community Edition 9.x.x by Redgate
Database: jdbc:mysql://localhost:3306/smartview (MySQL 8.0)
Successfully validated 1 migration (execution time 00:00.015s)
Creating Schema History table `smartview`.`flyway_schema_history` ...
Current version of schema `smartview`: << Empty Schema >>
Migrating schema `smartview` to version "1 - create user table"
Successfully applied 1 migration to schema `smartview`, now at version v1 (execution time 00:00.045s)
```

#### 7.2.2 表结构验证

**SQL 命令：**
```sql
-- 查看表结构
SHOW CREATE TABLE user;

-- 查看索引
SHOW INDEX FROM user;

-- 查看迁移历史
SELECT * FROM flyway_schema_history;
```

**预期结果：**
- `user` 表存在，包含所有字段
- 存在 6 个索引：PRIMARY, uk_username, uk_email_deleted, uk_phone_deleted, idx_status, idx_deleted, idx_created_at
- `flyway_schema_history` 表中有一条记录，version=1

#### 7.2.3 单元测试（后续任务）

**测试内容：**
1. `UserMapperTest`: 测试 CRUD 操作
2. 测试唯一约束违反场景
3. 测试逻辑删除行为
4. 测试字段自动填充

**测试文件：** `smartview-server/src/test/java/com/smartview/user/mapper/UserMapperTest.java`

---

## 8. 实现计划

### 8.1 文件清单

| 文件路径 | 状态 | 说明 |
| -------- | ---- | ---- |
| `smartview-server/src/main/resources/db/migration/V1__create_user_table.sql` | ✅ 已存在 | 用户表迁移脚本 |
| `smartview-server/src/main/java/com/smartview/user/entity/User.java` | ❌ 待创建 | 用户实体类 |
| `smartview-server/src/main/java/com/smartview/user/mapper/UserMapper.java` | ❌ 待创建 | 用户 Mapper 接口 |

### 8.2 实现步骤

1. **创建用户实体类 User.java**
   - 定义所有字段
   - 添加 MyBatis-Plus 注解
   - 添加 Lombok 注解

2. **创建用户 Mapper 接口 UserMapper.java**
   - 继承 BaseMapper<User>
   - 添加 @Mapper 注解

3. **验证迁移脚本**
   - 启动应用
   - 检查 user 表是否创建成功

4. **编写单元测试（后续任务）**
   - 测试 CRUD 操作
   - 测试唯一约束
   - 测试逻辑删除
   - 测试字段自动填充

---

## 9. 风险和注意事项

### 9.1 邮箱和手机号唯一约束

**风险：** MySQL 不支持部分索引（WHERE 子句），使用组合唯一索引 `(email, deleted)` 和 `(phone, deleted)` 模拟。

**影响：** 
- 软删除后的邮箱和手机号可以被新用户重复使用
- 应用层需确保 `email` 为 NULL 或 `deleted=0` 时才校验唯一性

**解决方案：**
- 在业务层添加邮箱和手机号唯一性校验
- 在用户注册和更新时检查 `email` 和 `phone` 是否已被未删除用户使用

### 9.2 密码存储安全

**风险：** `password_hash` 字段存储加密后的密码，必须使用强加密算法。

**解决方案：**
- 使用 BCrypt 算法加密密码（Spring Security 默认）
- 密码字段长度预留 255 字符，兼容未来算法升级

### 9.3 软删除数据膨胀

**风险：** 软删除会导致 `user` 表数据量增长，影响查询性能。

**解决方案：**
- 定期归档或物理删除长期未使用的软删除数据
- 为 `deleted` 字段添加索引（已包含在设计中）

---

## 10. 总结

本设计文档描述了 Task 2.1 的完整实现方案，包括数据库表设计、实体类设计、数据访问层设计和验收方案。核心设计决策包括：

1. 使用 Flyway 管理数据库迁移
2. 使用 MyBatis-Plus 提供 CRUD 能力和逻辑删除支持
3. 使用 `MyMetaObjectHandler` 自动填充时间字段
4. 使用组合唯一索引模拟部分索引，确保软删除场景下的数据一致性

下一步将调用 writing-plans 技能创建详细的实现计划。
