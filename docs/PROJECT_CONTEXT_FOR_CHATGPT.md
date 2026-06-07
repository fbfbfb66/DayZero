# 项目上下文分析报告 (DayZero)

本文档旨在为后续 AI 助手提供当前 Android 项目的详尽上下文，以便进行后续开发、重构和功能接入规划。

---

## 1. 项目基本信息

- **项目名称**: My Application (代码中应用名称显示为 "DayZero")
- **Android 包名**: `com.example`
- **当前技术栈**: Kotlin + Jetpack Compose + Material 3 + Room + Repository + Retrofit + ViewModel + Navigation Compose
- **Kotlin 版本**: `2.2.10`
- **AGP 版本**: `9.2.1`
- **本地数据库**: **已接入 (Room)**。实现记录、草稿、体重、食物项、**聊天消息**的本地持久化。
- **持久化状态**: 记录、草稿、体重、食物项、**对话记录**均已实现本地持久化，App 重启后数据保持不变。
- **AI 架构**: **已接入真实 AI (Supabase Edge Functions)**。具备完整的 AI Draft 架构。
    - **后端代理**: 使用 Supabase Edge Function (`generate-checkin-draft`) 作为 Kimi API 的代理。
    - **今日总结**: 使用专门的 Edge Function (`generate-daily-summary`) 基于全天记录生成个性化建议。
    - **协议演进**: 已建立 **AI 助手协议层 (Assistant Protocol Layer)**，支持多意图识别与复合卡片返回。
    - **安全性**: Kimi API Key 仅保存在 Supabase Secret 中，Android 端通过 Supabase Publishable Key 进行身份验证。
- **运行数据来源**: 
    - 记录观察: `RoomRecordRepository` -> Room Database (Flow 自动更新)。
    - 草稿生成: `RemoteAiDraftRepository` (默认启用) 或 `FakeAiDraftRepository` (调试开关)。
    - 智能助手: `FakeAiAssistantRepository` (Phase A 协议验证)。
- **交互特性**: **支持多次分餐录入与自动合并**。同一天只保留一条 Confirmed 记录，新草稿确认时会智能合并。所有系统交互（如冲突处理、餐次追问）均已集成在 AI 聊天窗口内作为 **ChoiceCard** 出现。
- **数据一致性**: 
    - 已修复确认后不同步问题。
    - 新增 **DraftOutputSanitizer** 防护逻辑，防止上下文（已有记录）污染新生成的草稿，确保午餐录入不再误触发早餐冲突。
- **图片功能**: **尚未接入** (UI 占位图标，`MealEntry` 仅有 `hasPhoto: Boolean` 标记)。
- **UI 布局与动画**: 
    - 已修复 AI 记录页底部输入栏遮挡内容问题。
    - **UI 动效升级**: 全面引入 `animateItem()` 列表动画、Spring 弹性卡片伸缩动画、以及 AI 思考态的“三点跳动”视觉反馈。
    - **智能滚动**: 实现了基于用户位置和消息来源的智能自动滚动逻辑，提升对话流的丝滑感。
- **云同步 / 登录**: **尚未接入**。

---

## 2. 项目目录结构

最新分层结构如下：

- **`com.example.domain`**: 领域层 (业务逻辑与模型)
    - **`model/`**: `DailyRecord.kt`, `MealEntry.kt`, `FoodEntry.kt`, `AppState.kt` 等。
    - **`model/ai/`**: `CheckinDraft.kt`, `DraftMeal.kt`, `DraftFood.kt`, `AiDraftRequest.kt`, `AiChatMessage.kt`, `ChatRole.kt`。
    - **`mapper/`**: `CheckinDraftMapper.kt` (AI 草稿转持久化模型)。
    - **`repository/`**: `RecordRepository.kt`, `AiDraftRepository.kt` (接口定义)。
    - **`summary/`**: `DailySummaryBuilder.kt` (本地饮食总结生成逻辑)。
- **`com.example.data`**: 数据层 (实现细节)
    - **`local/`**: Room 实现 (dao, database, entity, mapper, **ChatMessage**)。
    - **`remote/`**: 远程实现 (Supabase)。
    - **`repository/`**: `RoomRecordRepository.kt`, `RemoteAiDraftRepository.kt`, `FakeAiDraftRepository.kt`, `MockRecordRepository.kt`。
- **`com.example.ui`**: UI 层
    - **`components/feedback/`**: `SuccessConfirmOverlay.kt`。
    - **`screens/`**: `CalendarScreen.kt`, `AiRecordScreen.kt`, `TrendsScreen.kt`。

---

## 3. 核心功能状态

| 功能 | 当前状态 | 说明 |
| :--- | :--- | :--- |
| **日历页** | 已完成 | 展示 Confirmed 记录详情与打卡标记。 |
| **AI记录页** | 已完成 | 支持真实对话流、生成/编辑草稿、多次分餐合并、冲突处理、**平滑动画与智能滚动**。 |
| **今日总结生成** | 已完成 | **由 AI 实时生成**。基于全天 Confirmed 数据，提供个性化、温暖的饮食建议，支持本地模板 Fallback。 |
| **Room 持久化** | 已完成 | 所有记录及**完整对话历史**跨 App 重启保持不变。 |
| **交互优化** | 已完成 | 移除了底部多余背景框，输入栏紧贴底部，视觉更扁平现代。 |
| **图片选择/拍照** | 尚未实现 | 仅有 UI 图标。 |

---

## 4. 数据流

### 4.1 观察流 (Observation)
`Room Database` -> `DailyRecordDao` -> `RoomRecordRepository` -> `RecordRepository` -> `DayZeroViewModel` -> `uiState` -> `UI Screens`

### 4.2 录入合并流 (Merging)
1. `用户点击确认录入` -> `ViewModel.confirmDraftWithMerge()`。
2. 查找当天是否有 `Confirmed` 记录。
3. 若无，将 `Draft` 状态修改为 `Confirmed` 并 `upsert` (ID 保持不变)。
4. 若有，判断 `mealType` 是否冲突：
    - 无冲突：直接合并到已有记录并 `upsert`。
    - 有冲突：弹出 `ChoiceCard` 询问（覆盖/仅添加未冲突/取消）。
5. `completeConfirmation()` 被调用：
    - 向用户发送确认成功消息。
    - 异步调用 `AiSummaryRepository.generateDailySummary()` 获取基于全天记录的 AI 建议。
    - 成功后更新 `Room` 并显示 AI 建议气泡；失败则 fallback 使用 `DailySummaryBuilder`。

---

## 5. 核心修复与升级记录

### 5.1 数据同步修复 (Phase 6.5)
- **现象**: 确认录入成功但日历无标记。
- **根因**: 在 `confirmDraftWithMerge` 逻辑中，当没有已有记录时，代码先 `upsert` 了 Confirmed 记录，紧接着又调用了 `deleteRecordById(draftId)`。由于转正后的记录沿用了草稿的 ID，导致刚存入的正式记录被立刻删除。
- **解决方案**: 移除了该场景下的冗余 `delete` 调用。

### 5.3 UI 体验与动效优化 (Phase 8)
- **丝滑滚动**: 引入智能滚动逻辑，防止 AI 回复时强行中断用户翻看历史的行为。
- **物理动效**: 卡片伸缩采用 `Spring` 弹性模型，消除生硬的布局切换感。
- **AI 拟人化**: 增加 `TypingIndicator`（跳动的三点动画），提供更真实的对话反馈感。
- **布局精简**: 移除了 AI 记录页多余的底层 Surface，实现了真正的沉浸式背景。

### 5.4 AI 助手协议化 (Phase 9 - 正在进行)
- **协议定义**: 新增 `AiAssistantTurn` 交互单元，支持文本回复 + 多卡片（Draft, Choice, Summary, Weight, Edit, Delete）。
- **意图驱动**: 定义 `AiIntent` 枚举，涵盖饮食记录、体重、建议、鼓励、修改、删除等 10 种意图。
- **架构准备**: 新增 `AiAssistantRepository` 接口，为后续切换到“全意图 AI 助手”做准备。目前不影响现有的稳定录入流程。

---

## 6. 开发者调试指南
- **Logcat Tag**: `DayZeroDataFlow`。
- **核心逻辑**: 观察 `observeRecords` 与 `confirmDraftWithMerge` 的协同。
- **AI 调试**: 通过 `RemoteAiDraftRepository` 检查 Supabase 响应。
- **协议验证**: 查看 `FakeAiAssistantRepository` 模拟的不同意图返回。

---

## 5. 当前数据模型

- **`DailyRecord`**: 持久化核心模型。
- **`AiChatMessage`**: 聊天记录模型（已通过 Room 持久化，支持跨重启保留）。
- **`ConflictState`**: 用于管理冲突对话框的显示状态。

---

## 6. Repository 状态

- **`RecordRepository`**: 已扩展支持 `getRecordByDateAndStatus` 和 `deleteRecordById`。
- **`RemoteAiDraftRepository`**: 默认使用。

---

## 7. UI 反馈系统

- **聊天 UI**: 实现了真正的消息流（用户气泡+助手气泡）。
- **成功提示**: `SuccessConfirmOverlay`。
- **冲突处理**: 居中 `AlertDialog`，提供三种处理策略。

---

## 8. 当前 Mock / Fake 状态

- **聊天记录**: 已实现 Room 持久化，App 重启后保留历史记录。所有交互式选项（ChoiceCard）的状态也已同步持久化。
- **图片功能**: 仍为占位。
- **总结逻辑**: 目前在 Android 端本地生成，不再完全依赖 Kimi 返回的单次总结。

---

## 9. 架构评价

### 优点
- **业务闭环完备**: 解决了同一天多条记录的冲突问题。
- **用户体验连贯**: 聊天流+草稿卡片+冲突引导。
- **逻辑下沉**: 复杂的合并逻辑和总结生成逻辑均有独立组件负责。

---

## 10. 后续正式开发建议

**下一步最推荐任务**:
1. **多媒体升级**: 将 `MealEntry` 的 `hasPhoto` 升级为 `photoUri: String?`。
2. **图片存储**: 实现拍照/选图，并将 URI 持久化。
3. **UI 细节**: 优化聊天气泡的动画效果和长列表滚动体验。

---

## 文档更新规则

为了保证后续 AI 助手和开发者始终理解项目真实状态，每次完成一个开发阶段、重构阶段或关键 bug 修复后，都必须更新本文档。

（后续规则同前...）
