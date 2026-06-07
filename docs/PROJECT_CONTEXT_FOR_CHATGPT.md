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
    - **安全性**: Kimi API Key 仅保存在 Supabase Secret 中，Android 端通过 Supabase Publishable Key 进行身份验证。
- **运行时数据来源**: 
    - 记录观察: `RoomRecordRepository` -> Room Database。
    - 草稿生成: `RemoteAiDraftRepository` (默认启用) 或 `FakeAiDraftRepository` (调试开关)。
- **交互特性**: **支持多次分餐录入与自动合并**。同一天只保留一条 Confirmed 记录，新草稿确认时会智能合并。
- **图片功能**: **尚未接入** (UI 占位图标，`MealEntry` 仅有 `hasPhoto: Boolean` 标记)。
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
| **AI记录页** | 已完成 | **支持真实对话流**、生成/编辑草稿、**多次分餐合并**、冲突处理。 |
| **趋势页** | 已完成 | Canvas 绘制热量和体重的变化曲线 (Confirmed 数据)。 |
| **Room 持久化** | 已完成 | 所有记录跨 App 重启保持不变。 |
| **真实 AI 解析** | 已完成 | 通过 Supabase 代理调用 Kimi API。 |
| **分餐合并逻辑** | 已完成 | 支持同一天多次录入并智能处理餐次冲突。 |
| **本地总结生成** | 已完成 | 合并后基于全天已确认食物自动生成温柔总结。 |
| **聊天记录持久化** | 已完成 | 对话流已存入 Room，重启 App 不消失。 |
| **交互式对话** | 已完成 | 冲突处理等操作已集成进聊天流。 |
| **图片选择/拍照** | 尚未实现 | 仅有 UI 图标。 |

---

## 4. 数据流

### 4.1 观察流 (Observation)
`Room Database` -> `DailyRecordDao` -> `RoomRecordRepository` -> `RecordRepository` -> `DayZeroViewModel` -> `uiState` -> `UI Screens`

### 4.2 录入合并流 (Merging)
1. `用户点击确认录入` -> `ViewModel.confirmDraftWithMerge()`。
2. 查找当天是否有 `Confirmed` 记录。
3. 若无，直接转为 `Confirmed`。
4. 若有，判断 `mealType` 是否冲突：
    - 无冲突：直接合并。
    - 有冲突：弹出 `AlertDialog` 询问（覆盖/仅添加未冲突/取消）。
5. `DailySummaryBuilder` 重新生成全天总结。
6. 更新 `Room` 并清理 `Draft`。

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

- **聊天记录**: 已实现 Room 持久化，App 重启后保留历史记录。
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
