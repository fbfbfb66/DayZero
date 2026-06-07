# 项目上下文分析报告 (DayZero)

本文档旨在为后续 AI 助手提供当前 Android 项目的详尽上下文，以便进行后续开发、重构和功能接入规划。

---

## 1. 项目基本信息

- **项目名称**: My Application (代码中应用名称显示为 "DayZero")
- **Android 包名**: `com.example`
- **当前技术栈**: Kotlin + Jetpack Compose + Material 3 + Room + Repository + Retrofit + ViewModel + Navigation Compose
- **Kotlin 版本**: `2.2.10`
- **AGP 版本**: `9.2.1`
- **本地数据库**: **已接入 (Room)**。包含完整的 Entity、DAO、Database 实现。
- **持久化状态**: 记录、草稿、体重、食物项均已实现本地持久化，App 重启后数据保持不变。
- **AI 架构**: **已接入真实 AI (Supabase Edge Functions)**。Android 端调用部署在 Supabase 的云函数，云函数代理调用 Kimi API 进行饮食分析。
- **安全性**: Kimi API Key 仅保存在 Supabase Secret 中，Android 端不持有任何 AI 密钥。
- **数据来源**: 运行时数据源为 Room；AI 解析由 `RemoteAiDraftRepository` 提供（支持切换回 `FakeAiDraftRepository`）。
- **图片功能**: **尚未接入** (仅有 `hasPhoto` 布尔标记，无真实图片存储与显示)。

---

## 2. 项目目录结构

最新目录结构如下：

- **`com.example.domain.model`**: 核心领域模型包。
- **`com.example.domain.model.ai`**: AI 专用模型包。
- **`com.example.domain.mapper`**:
    - `CheckinDraftMapper.kt`: AI 草稿转持久化模型。
- **`com.example.domain.repository`**: 仓库接口包。
    - `RecordRepository.kt`: 持久化。
    - `AiDraftRepository.kt`: AI 生成。
- **`com.example.data.local`**: Room 实现。
- **`com.example.data.remote`**: 远程数据层 (Supabase)。
    - **`api/`**: `AiDraftApiService.kt` (Retrofit 定义)。
    - **`dto/`**: `AiDraftRequestDto.kt`, `AiDraftResponseDto.kt` 等。
    - **`mapper/`**: `AiDraftRemoteMapper.kt` (DTO 与领域模型转换)。
    - **`NetworkModule.kt`**: Retrofit/OkHttp 配置及 Interceptor。
    - **`SupabaseConfig.kt`**: Supabase URL 与 Publishable Key 配置。
- **`com.example.data.repository`**: 仓库实现。
    - `RoomRecordRepository.kt`: 主持久化仓库。
    - `RemoteAiDraftRepository.kt`: **真实 AI 实现**，调用 Supabase。
    - `FakeAiDraftRepository.kt`: 备用 Fake AI 实现。
- **`com.example.ui`**:
    - `components/feedback/`: `SuccessConfirmOverlay.kt`。
    - `screens/`: `CalendarScreen`, `AiRecordScreen`, `TrendsScreen`。

---

## 3. 核心功能状态

| 功能 | 当前状态 | 说明 |
| :--- | :--- | :--- |
| 日历页 | 已完成 | 支持打卡标记、记录详情查看。 |
| AI记录页 | 已完成 | 支持真实 AI 饮食分析、生成/编辑草稿、确认录入。 |
| 趋势页 | 已完成 | 支持热量和体重的 Canvas 图表展示。 |
| Room 本地持久化 | 已完成 | 所有记录跨 App 重启持久保存。 |
| 真实 AI API | 已完成 | 通过 Supabase Edge Function 接入 Kimi。 |
| 成功动画提示 | 已完成 | 居中的清新绿色动画。 |
| 防重复弹出 | 已完成 | 使用 `UiEvent` (SharedFlow)。 |
| 图片选择 / 拍照 | 尚未实现 | 下一阶段重点。 |

---

## 4. 当前真实数据流

### 4.1 持久化数据流 (Observation)
`Room Database` -> `DailyRecordDao` -> `RoomRecordRepository` -> `RecordRepository` -> `DayZeroViewModel` -> `uiState` -> `UI Screens`

### 4.2 真实 AI 生成流 (Input)
`用户输入文本` -> `AiRecordScreen` -> `ViewModel` -> `RemoteAiDraftRepository` -> `Retrofit` -> **`Supabase Edge Function`** -> `Kimi API` -> `CheckinDraft` -> `Mapper` -> `Room (Draft)`

---

## 5. 当前数据模型

- **`DailyRecord`**: 持久化模型。
- **`CheckinDraft`**: AI 专用模型。
- **DTOs**: `AiDraftResponseDto` 等严格对应 Supabase 返回的 JSON。

---

## 6. Repository 状态

- **`RecordRepository`**: 已支持 `upsertRecord`。
- **`RemoteAiDraftRepository`**: **默认使用**。
- **`FakeAiDraftRepository`**: 保留作为调试 fallback。

---

## 7. UI 反馈系统

- **成功提示**: `SuccessConfirmOverlay`。
- **错误提示**: `UiEvent.Error` 通过顶层 `SnackbarHost` 展示。

---

## 8. 当前 Mock / Fake 功能

- **图片功能**: `AiRecordScreen` 中的按钮仍为占位。
- **Mock Seeding**: `MockRecords.kt` 仍用于首次启动数据库填充。

---

## 9. 后续计划

**当前已完成**: 模型、仓库、Room、成功动画、真实 AI 接入 (Supabase)。

**下一步建议**:
1. **多媒体支持**: 接入相册与相机，将 `hasPhoto` 升级为 `photoUri`，实现图片持久化。
2. **UI 整理**: 随着代码量增加，建议将 `ui/screens` 下的复杂组件拆分到独立包。
3. **DI 框架**: 引入 Hilt 替代目前手动 Factory。

---

## 10. 给后续 AI 助手的简短总结

**项目摘要**:
本项目名为 **DayZero**，是一个 Kotlin + Compose 的减脂记录 App。目前已完成**全链路真实功能闭环**。

**技术现状**: 采用 MVVM + Repository 架构。数据持久化通过 **Room** 实现。饮食分析已接入**真实 Kimi AI**，方案为：Android -> Supabase Edge Function (Node.js/Deno) -> Kimi API。这种架构确保了 API Key 的安全且降低了客户端复杂度。UI 具备完善的动画反馈和异常处理（如 AI 分析失败时的温柔提示）。

**核心能力**: 用户输入一段饮食描述，App 通过远程 AI 解析出结构化的餐次、食物及热量，并保存为本地草稿。用户编辑确认后，数据永久保存并驱动日历及趋势图展示。

**待开发重点**:
1. **图片识别/存储**: 实现饮食拍照及照片本地展示。
2. **架构演进**: 考虑 DI 接入和功能模块化。

---

## 文档更新规则

（此处保留原有的文档更新规则内容，为了简短已略...）
