# 并行判定增强（层次 1 + 层次 2）设计文档

## 背景

上一轮「流式单工具执行」重构把 `AgentOrchestrator` 从「批量执行 + 延迟写回」改成「流式单工具 + 即时写回 + 依赖工具触发重新决策」，消灭了 `isExclusiveTurnBoundaryTool` 独占补丁。但并行判定仍有两个缺陷：

1. **handler 级覆盖没生效**：`ToolExecutionPlanner.plan` 和 `stepHasDependencyTool` 都不传 handler，`ToolHandlerConcurrencyHint`（handler 级并行声明）根本被用到——只有 `MemoryLoadToolHandler` 实现了它，但白搭。
2. **写工具一律串行**：`file_write`/`file_edit`/`file_move` 一律 `SERIAL_BARRIER`，即使两个 `file_write` 写到不同路径、完全独立，也被强制串行，白白多一次模型调用。

## 目标

把并行判定从「单工具静态白名单」升级为两层：
- **单工具层**：handler 声明「这个工具本身可并行」（`ToolHandlerConcurrencyHint`）
- **整批层**：`ToolExecutionPlanner.plan` 在合并并行步时，额外做**路径冲突检测**——同批写工具若写到同一路径则拆开

## 设计

### 层次 1：让 handler 级覆盖真正生效

**改动点：**

1. `AgentToolExecutor` 接口（`app/src/main/java/cn/com/omnimind/bot/agent/runtime/AgentRuntimeContracts.kt`）加方法：
   ```kotlin
   fun handlerFor(toolName: String): ToolHandler? = null
   ```
   默认返回 null（不破坏现有实现）。

2. `AgentToolRouter`（`app/src/main/java/cn/com/omnimind/bot/agent/tool/AgentToolRouter.kt`）实现它——从已有的 `handlerMap` 查：
   ```kotlin
   override fun handlerFor(toolName: String): ToolHandler? = handlerMap[toolName]
   ```

3. `ToolExecutionPlanner.plan`（`app/src/main/java/cn/com/omnimind/bot/agent/runtime/ToolExecutionPlanner.kt`）接收 `handlerFor: (String) -> ToolHandler?` 参数，传给 `classify(name, args, handler)`：
   ```kotlin
   fun plan(
       calls: List<AssistantToolCall>,
       parsedArgs: Map<String, JsonObject>,
       handlerFor: (String) -> ToolHandler? = { null },
   ): List<ToolExecutionStep>
   ```

4. `AgentOrchestrator`（`app/src/main/java/cn/com/omnimind/bot/agent/runtime/AgentOrchestrator.kt`）：
   - `ToolExecutionPlanner.plan(validatedCalls, parsedArgsMap, toolRouter::handlerFor)`
   - `stepHasDependencyTool` 判定处也传 handler：`classify(name, args, toolRouter.handlerFor(name))`

### 层次 2：写工具路径冲突检测

**改动点：**

1. `FileToolHandler`（`app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/FileToolHandler.kt`）实现 `ToolHandlerConcurrencyHint`，`concurrencyFor` 对 `file_write`/`file_edit`/`file_move` 返回 `PARALLEL_SAFE`（声明「本身可并行」）：
   ```kotlin
   class FileToolHandler(...) : ToolHandler, ToolHandlerConcurrencyHint {
       override fun concurrencyFor(toolName: String, args: JsonObject): ToolConcurrency? {
           return if (toolName in WRITE_TOOLS) ToolConcurrency.PARALLEL_SAFE else null
       }
   }
   ```
   其中 `WRITE_TOOLS = setOf("file_write", "file_edit", "file_move")`。

2. `ToolExecutionPlanner.plan` 在合并并行步时，额外做**路径冲突检测**：
   - 同批多个写工具，若解析后的目标路径**不同** → 保持并行
   - 若**相同** → 拆开（避免写冲突）
   - 路径解析用 `workspaceManager.resolvePath`（与 handler 一致）

   **关键设计**：`concurrencyFor` 只声明「单工具可并行」，`plan` 的路径检测处理「整批是否冲突」。因为 `concurrencyFor(toolName, args)` 只接收单个工具，无法判断「这批工具之间是否冲突」。

   **路径冲突检测的签名**：`plan` 需要能解析每个写工具的 path。但 `plan` 是纯逻辑（不依赖 Android），所以路径解析不能直接调 `workspaceManager`。设计为：`plan` 接收一个可选的 `pathResolver: (String, JsonObject) -> String?` 函数，默认 null（不检测）。`AgentOrchestrator` 传入 `workspaceManager` 的路径解析。

   ```kotlin
   fun plan(
       calls: List<AssistantToolCall>,
       parsedArgs: Map<String, JsonObject>,
       handlerFor: (String) -> ToolHandler? = { null },
       pathResolver: (String, JsonObject) -> String? = { _, _ -> null },
   ): List<ToolExecutionStep>
   ```

   **冲突检测逻辑**：合并并行步时，若该步含多个写工具，收集它们的解析路径；若出现重复路径，则把冲突的写工具拆成单独步。

### 涉及文件

| 文件 | 改动 |
|---|---|
| `AgentRuntimeContracts.kt` | `AgentToolExecutor` 加 `handlerFor` |
| `AgentToolRouter.kt` | 实现 `handlerFor` |
| `ToolExecutionPlanner.kt` | 接收 `handlerFor` + `pathResolver`，加路径冲突检测 |
| `AgentOrchestrator.kt` | 传 `handlerFor` + `pathResolver` |
| `FileToolHandler.kt` | 实现 `ToolHandlerConcurrencyHint` |
| `ToolExecutionPlannerTest.kt` | 补路径冲突用例 |

## 数据流

```
模型下发 tool_calls
  → AgentOrchestrator Phase A 解析校验
  → ToolExecutionPlanner.plan(calls, parsedArgs, handlerFor, pathResolver)
      → 单工具层：classify(name, args, handler) 判定 PARALLEL_SAFE / SERIAL_BARRIER
      → 整批层：路径冲突检测，冲突的写工具拆开
  → 生成执行步（并行步 / 串行步）
  → 依赖工具步执行后触发重新决策
```

## 错误处理

- `handlerFor` 找不到 handler → 返回 null，`classify` 走静态白名单兜底（现有行为）
- `pathResolver` 解析失败（返回 null）→ 不检测该工具路径，视为无冲突（保守，不误拆）
- 路径冲突检测只影响并行合并，不影响执行正确性（拆开只是多一轮不会错）

## 测试

- `ToolExecutionPlannerTest` 补：
  - 两个 `file_write` 不同路径 → 合并并行
  - 两个 `file_write` 相同路径 → 拆开
  - handler 覆盖生效（`MemoryLoadToolHandler` 的 `memory_load` 走 handler 判定）
- 现有 `AgentOrchestratorTest` 保持全绿

## 不做的事（YAGNI）

- 不做层次 3（模型显式声明并行）——协议级改动，风险大，暂缓
- 不改 `AgentToolConcurrencyPolicy` 的静态白名单本身（保留作为兜底）
- 不做 `file_read` 等纯读工具的路径检测（它们本就 PARALLEL_SAFE，无冲突风险）
