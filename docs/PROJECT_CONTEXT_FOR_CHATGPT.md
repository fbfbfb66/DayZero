# 项目上下文分析报告 (DayZero)

本文档旨在为后续 AI 助手提供当前 Android 项目的详尽上下文，以便进行后续开发、重构和功能接入规划。

---

## 1. 项目基本信息

- **项目名称**: My Application (代码中应用名称显示为 "DayZero")
- **Android 包名**: `com.example`
- **当前技术栈**: Kotlin + Jetpack Compose + Material 3 + Room + Repository + Retrofit + ViewModel + Navigation Compose
- **本地数据库**: **已接入 (Room)**。实现记录、草稿、体重、食物项的本地持久化。
- **AI 架构**: **已接入真实 AI (Supabase Edge Functions)**。具备完整的 AI Draft 架构。
    - **后端代理**: 使用 Supabase Edge Function (`generate-checkin-draft`) 作为 Kimi API 的代理。
    - **安全性**: Kimi API Key 仅保存在 Supabase Secret 中，Android 端通过 Supabase Publishable Key 进行身份验证。
- **运行时数据来源**: 
    - 记录观察: `RoomRecordRepository` -> Room Database。
    - 草稿生成: `RemoteAiDraftRepository` (默认启用) 或 `FakeAiDraftRepository` (调试开关)。
- **图片功能**: **尚未接入** (UI 占位图标，`MealEntry` 仅有 `hasPhoto: Boolean` 标记)。
- **云同步 / 登录**: **尚未接入**。

---

## 2. 项目目录结构

最新分层结构如下：

- **`com.example.domain`**: 领域层 (业务逻辑与模型)
    - **`model/`**: `DailyRecord.kt`, `MealEntry.kt`, `FoodEntry.kt`, `AppState.kt` 等。
    - **`model/ai/`**: `CheckinDraft.kt`, `DraftMeal.kt`, `DraftFood.kt`, `AiDraftRequest.kt` (AI 专用模型)。
    - **`mapper/`**: `CheckinDraftMapper.kt` (AI 草稿转持久化模型)。
    - **`repository/`**: `RecordRepository.kt`, `AiDraftRepository.kt` (接口定义)。
- **`com.example.data`**: 数据层 (实现细节)
    - **`local/`**: Room 实现 (dao, database, entity, mapper)。
    - **`remote/`**: 远程实现 (Supabase)。
        - `api/AiDraftApiService.kt` (Retrofit 接口)。
        - `dto/` (数据传输对象)。
        - `mapper/AiDraftRemoteMapper.kt` (DTO 转模型)。
        - `NetworkModule.kt`, `SupabaseConfig.kt` (网络配置)。
    - **`repository/`**: `RoomRecordRepository.kt`, `RemoteAiDraftRepository.kt`, `FakeAiDraftRepository.kt`, `MockRecordRepository.kt`。
    - **`mock/`**: `MockRecords.kt` (仅用于首次启动数据初始化)。
- **`com.example.ui`**: UI 层
    - **`components/feedback/`**: `SuccessConfirmOverlay.kt` (居中动画提示)。
    - **`screens/`**: `CalendarScreen.kt`, `AiRecordScreen.kt`, `TrendsScreen.kt`。
    - **`theme/`**: 视觉系统定义。

---

## 3. 核心功能状态

| 功能 | 当前状态 | 说明 |
| :--- | :--- | :--- |
| **日历页** | 已完成 | 展示 Confirmed 记录详情与打卡标记。 |
| **AI记录页** | 已完成 | 对话式输入，生成结构化草稿，支持编辑与确认。 |
| **趋势页** | 已完成 | Canvas 绘制热量和体重的变化曲线 (Confirmed 数据)。 |
| **Room 持久化** | 已完成 | 所有数据跨 App 重启保持不变。 |
| **真实 AI 解析** | 已完成 | 通过 Supabase 代理调用 Kimi API。 |
| **Fake AI 模拟** | 已完成 | 支持离线关键词匹配模拟。 |
| **成功提示系统** | 已完成 | `UiEvent` (SharedFlow) + 居中动画，修复重复弹出。 |
| **图片选择/拍照** | 尚未实现 | 仅有 UI 图标，底层不支持 photoUri。 |
| **多餐次识别** | 部分完成 | AI 可识别早餐/午餐等并分类，但 UI 展示仍可优化。 |

---

## 4. 数据流

### 4.1 观察流 (Observation)
`Room Database` -> `DailyRecordDao` -> `RoomRecordRepository` -> `RecordRepository` -> `DayZeroViewModel` -> `uiState` -> `UI Screens`

### 4.2 生成流 (Generation)
1. `用户输入文本` -> `AiRecordScreen` -> `DayZeroViewModel.generateDraftFromText()`。
2. `RemoteAiDraftRepository` (或 `Fake`) -> 调用网络/本地逻辑生成 `CheckinDraft`。
3. `CheckinDraftMapper` 转换为 `DailyRecord(status = Draft)`。
4. `RoomRecordRepository.upsertRecord()` 存入数据库。
5. UI 自动观察到新草稿。

### 4.3 确认流 (Confirmation)
1. `用户点击确认录入` -> `DayZeroViewModel.confirmDraft()`。
2. `RoomRecordRepository.updateRecordStatus(id, Confirmed)`。
3. `DayZeroViewModel` 发射 `UiEvent.RecordConfirmed`。
4. `MainApp` 展示 `SuccessConfirmOverlay` 动画。

---

## 5. 当前数据模型

- **`DailyRecord`**: 持久化核心模型。`status` 区分 `Draft` 和 `Confirmed`。
- **`MealEntry`**: **注意**: 仍然使用 `hasPhoto: Boolean`，尚未升级为 `photoUri: String?`。
- **`CheckinDraft`**: AI 专用中间模型，映射 DTO 输出。

---

## 6. Repository 状态

- **`RecordRepository`**: 主接口。
    - `RoomRecordRepository`: **活跃使用**。处理 Room 读写与首次启动数据 Seeding。
- **`AiDraftRepository`**: AI 接口。
    - `RemoteAiDraftRepository`: **默认启用**。通过 Retrofit 调用 Supabase Edge Function。
    - `FakeAiDraftRepository`: **备用**。通过本地简单规则模拟解析。

---

## 7. UI 反馈系统

- **成功提示**: 已实现 `SuccessConfirmOverlay`。采用 `SharedFlow` 确保事件一次性消费，不会因导航切换重复弹出。
- **错误提示**: 已实现 `UiEvent.Error`。当 AI 解析失败或网络断开时，通过顶层 `SnackbarHost` 展示文字提示。

---

## 8. 当前 Mock / Fake 状态

- **`MockRecords.kt`**: 仅在数据库为空时运行一次，不再是运行时主数据源。
- **图片功能**: AI 记录页和日历页详情中的图片均为 Mock 占位或 `hasPhoto` 布尔标记。
- **API 切换**: `DayZeroViewModel` 的 `Factory` 中可通过 `USE_REMOTE_AI` 常量手动切回本地模拟。

---

## 9. 架构评价

### 优点
- **数据持久化完备**: Room 与 Repository 结合良好。
- **AI 抽象清晰**: 领域层模型与远程 DTO 分离，后端可随时替换。
- **交互逻辑闭环**: 从输入到生成草稿再到持久化确认流程已经跑通。

### 风险与建议
- **JSON 存储限制**: `DailyRecordEntity` 使用 JSON 存储食物项，不支持跨日期搜索特定食物。
- **DI 缺失**: 手动 Factory 开始变得臃肿，建议引入 Hilt。
- **图片功能断档**: 目前结构完全不支持真实图片路径存储。

---

## 10. 后续正式开发建议

**下一步最推荐任务**:
1. **多媒体升级**: 将 `MealEntry` 的 `hasPhoto` 升级为 `photoUri: String?`。
2. **图片存储**: 集成相册/相机，将图片存入本地或 Supabase Storage，并将路径记录进 Room。
3. **UI 优化**: 将 `AiRecordScreen` 等大文件中的子组件 (如 `DraftCard`) 拆分到 `ui/components`。

---

## 11. 给后续 AI 助手的简短总结

**项目摘要**:
本项目 **DayZero** 是一款基于 Kotlin + Compose 开发的减脂每日记录应用。目前项目已完成**全链路的功能闭环**。

**当前状态**: 
采用分层架构 (Clean Architecture)，数据层已接入 **Room 数据库**实现记录、草稿和体重的永久保存。AI 功能已通过 **Supabase Edge Functions** 代理对接了真实的 **Kimi API**，实现了由自然语言到结构化饮食草稿的真实解析。UI 系统具备统一的清新绿色风格，并包含完善的动画反馈与单次触发的事件系统。

**关键数据流**: 
用户输入 -> 远程 AI 解析 -> 生成本地草稿 (Draft) -> 用户编辑确认 -> 存为正式记录 (Confirmed) -> 驱动日历与趋势图。

**核心风险**: 
当前图片功能仅为布尔值占位，尚未实现真实的 URI 存储与展示。下一步应聚焦于图片能力的补齐。

---

## 文档更新规则

为了保证后续 AI 助手和开发者始终理解项目真实状态，每次完成一个开发阶段、重构阶段或关键 bug 修复后，都必须更新本文档。

### 1. 什么时候必须更新本文档

以下情况必须更新：
- 新增或删除核心功能
- 完成一个开发阶段
- 完成一次架构重构
- 修改数据流
- 修改 Repository / ViewModel / Room / AI 接入方式
- 新增或修改 domain model
- 新增或修改数据库表 / Entity / DAO / Mapper
- 新增或替换数据来源，例如 Mock → Room，Fake AI → Remote AI
- 修改核心 UI 交互，例如确认录入、成功提示、草稿编辑
- 修复会影响用户流程的 bug
- 新增外部服务，例如 Gemini API、Supabase、Firebase、图片存储
- 当前文档描述和代码实际情况不一致时

### 2. 更新文档时必须遵守的原则

- 只写当前代码已经真实存在的内容。
- 不要把计划中的功能写成已完成。
- 如果功能只是 Mock、Fake、Demo 或本地模拟，必须明确标注。
- 如果依赖已经声明但未实际使用，必须明确标注。
- 如果无法从代码确认，必须写“未确认”，不能猜测。
- 保持文件路径、类名、数据流准确。
- 保持“当前状态”和“后续计划”分开。
- 每次更新后，都要检查文档底部摘要是否仍然准确。

### 3. 每次更新时至少检查这些章节

每次更新本文档时，至少检查并更新：
1. 项目基本信息
2. 项目目录结构
3. 核心功能状态
4. 当前数据流
5. 当前数据模型
6. Repository 状态
7. Mock / Fake 功能状态
8. 当前架构评价
9. 后续正式开发建议
10. 给后续 AI 助手的简短总结

### 4. 推荐更新格式

如果完成了一个阶段，请在相关章节写清楚：
- 阶段名称
- 新增文件
- 修改文件
- 新增的数据流
- 哪些旧逻辑被替换
- 哪些功能仍然是 Mock / Fake
- 是否影响 UI
- 是否影响持久化
- 是否影响后续开发计划

### 5. 文档和 Git 的配合规则

每次完成阶段后，推荐至少提交一次 Git commit。如果本次修改包含代码和文档，推荐合并或分两次提交，确保 commit message 准确说明修改内容。
