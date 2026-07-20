# Task 0.1 — 创建根目录规范文件 错误修复计划

## 审查发现的问题

### 问题 1：架构规则内容不完整 ⚠️ 高优先级

**问题描述：**
- AGENTS.md 和 .claude/CLAUDE.md 中追加的架构规则缺少**测试示例说明**部分
- 当前只有测试验收的标题和要求概述，没有展示正确示例 vs 错误示例
- README.md 中包含完整的测试示例说明，但 AGENTS.md 和 .claude/CLAUDE.md 缺失

**影响：**
开发者可能不清楚如何正确描述测试完成情况

**修复方案：**
在 AGENTS.md 和 .claude/CLAUDE.md 的"测试验收规范"一节中补充测试示例说明

### 问题 2：契约说明文件存在逻辑缺陷 ⚠️ 中优先级

**问题描述：**
- `contracts/ai-api/README.md` 中"调用规范"强调必须通过 AiServiceClient
- 但"接口分类"一节提到同步接口和异步接口（通过 MQ）
- 缺少明确说明哪些场景使用 AiServiceClient，哪些场景使用 MQ

**影响：**
开发者可能混淆同步调用和异步调用的使用场景

**修复方案：**
在"调用规范"一节补充"同步调用 vs 异步调用"说明

### 问题 3：MQ 契约说明存在业务理解偏差 ⚠️ 中优先级

**问题描述：**
- `contracts/mq/README.md` 中 FastAPI Worker 的幂等性检查示例代码注释说"通过 Spring Boot 提供的 HTTP API"
- 这违反了职责分离原则：FastAPI Worker 不应该回调 Spring Boot HTTP API 查询任务状态

**影响：**
误导开发者在 FastAPI Worker 中调用 Spring Boot HTTP API，破坏架构边界

**修复方案：**
修改 FastAPI Worker 的幂等性检查示例，改为依赖消息队列去重机制或 FastAPI 自己维护任务处理记录

### 问题 4：.gitignore 覆盖不足 ⚠️ 低优先级

**问题描述：**
.gitignore 缺少以下常见忽略项：
- Python 相关：*.egg、.eggs/
- Java 相关：*.nar、hs_err_pid*
- 前端相关：package-lock.json、.next/、.nuxt/
- 向量库数据：*.chroma/、chroma_db/

**影响：**
可能将不必要的文件提交到版本控制

**修复方案：**
补充缺失的忽略项到 .gitignore

### 问题 5：目录结构验证缺失 ⚠️ 低优先级

**问题描述：**
审查报告未验证以下目录是否真实存在：
- docs/api/
- docs/architecture/
- contracts 子目录（除 README.md 外是否有其他文件占位）
- knowledge/
- smartview-infra/

**影响：**
无法确认目录结构完整性

**修复方案：**
执行目录树验证命令确认所有目录已创建

## 修复优先级

### P0 - 必须立即修复
1. 补全 AGENTS.md 和 .claude/CLAUDE.md 中的测试示例说明

### P1 - 应该修复
2. 修正 contracts/ai-api/README.md 的同步/异步调用说明
3. 修正 contracts/mq/README.md 的 FastAPI 幂等性检查示例

### P2 - 建议修复
4. 补充 .gitignore 缺失项
5. 验证目录结构完整性

## 修复步骤

### 步骤 1：修复 P0 问题
1. 在 AGENTS.md 的"测试验收规范"一节补充测试示例说明
2. 在 .claude/CLAUDE.md 的"测试验收规范"一节补充相同的测试示例说明
3. 验证两个文件内容一致性

### 步骤 2：修复 P1 问题
1. 编辑 contracts/ai-api/README.md，在"调用规范"一节补充"同步调用 vs 异步调用"说明
2. 编辑 contracts/mq/README.md，修改 FastAPI Worker 的幂等性检查示例

### 步骤 3：修复 P2 问题
1. 补充 .gitignore 缺失项
2. 执行目录验证命令

### 步骤 4：重新审查
调用子 Agent 重新审查修复后的代码

## 验收标准

- [ ] AGENTS.md 和 .claude/CLAUDE.md 包含完整的测试示例说明
- [ ] 两个文件的测试示例说明内容完全一致
- [ ] contracts/ai-api/README.md 明确区分同步调用和异步调用场景
- [ ] contracts/mq/README.md 的 FastAPI 幂等性检查示例符合架构边界
- [ ] .gitignore 覆盖所有常见忽略项
- [ ] 所有必需目录已创建
- [ ] 子 Agent 重新审查通过
