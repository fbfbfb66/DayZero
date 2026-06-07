# 项目上下文分析报告 (DayZero)

本文档旨在为后续 AI 助手提供当前 Android 项目的详尽上下文，以便进行后续开发、重构和功能接入规划。

---

## 1. 项目基本信息

- **项目名称**: My Application (代码中应用名称显示为 "DayZero")
- **Android 包名**: `com.example`
- **当前技术栈**: Kotlin + Jetpack Compose + Material 3 + Room + Repository + ViewModel + Navigation Compose
- **Kotlin 版本**: `2.2.10`
- **AGP 版本**: `9.2.1`
- **本地数据库**: **已接入 (Room)**。包含完整的 Entity、DAO、Database 实现。
- **持久化状态**: 记录已实现本地持久化，App 重启后数据保持不变。
- **数据来源**: 运行时数据源为 Room 数据库；首次启动时会从 Mock 数据进行初始化（Seeding）。
- **真实网络请求**: **未确认/未使用**。
- **真实 AI API 调用**: **未确认/未使用**。

---

## 2. 项目目录结构

项目已完成分层重构，最新结构如下：

- **`com.example.domain.model`**: 领域模型包。
    - `DailyRecord.kt`, `MealEntry.kt`, `FoodEntry.kt`: 核心业务模型。
    - `RecordStatus.kt`, `MealType.kt`: 枚举定义。
    - `AppState.kt`: UI 状态封装。
- **`com.example.domain.repository`**: 仓库接口包。
    - `RecordRepository.kt`: 定义数据操作契约（Flow 观察、异步更新）。
- **`com.example.data.local`**: 本地存储实现包。
    - **`dao/`**: `DailyRecordDao.kt` (Room DAO)。
    - **`database/`**: `DayZeroDatabase.kt` (Room Database 单例)。
    - **`entity/`**: `DailyRecordEntity.kt` (Room Entity，含 `mealsJson`)。
    - **`mapper/`**: `DailyRecordMapper.kt` (实体与模型转换，使用 Moshi 处理 JSON)。
- **`com.example.data.repository`**: 仓库实现包。
    - `RoomRecordRepository.kt`: **当前主仓库**，负责 Room 交互及首次启动数据初始化逻辑。
    - `MockRecordRepository.kt`: 旧仓库实现，目前保留作为备用参考。
- **`com.example.data.mock`**:
    - `MockRecords.kt`: 提供 `createMockRecords()` 用于首次运行的数据预填充。
- **`com.example`**:
    - `DayZeroViewModel.kt`: 核心 ViewModel。包含 `Factory` 用于注入 `RoomRecordRepository`。
    - `MainActivity.kt`: 入口 Activity。
- **`com.example.ui`**:
    - `AppNavigation.kt`: 导航配置。`MainApp` 使用 `DayZeroViewModel.Factory` 创建 ViewModel。
    - `screens/`: `CalendarScreen`, `AiRecordScreen`, `TrendsScreen`。

---

## 3. 核心页面分析

- **数据流更新**: 所有页面（日历、AI 记录、趋势）的数据来源统一为 `DayZeroViewModel.uiState`。该状态通过监听 `RoomRecordRepository` 的 Flow 实现自动响应数据库变更。
- **页面职责**:
    - **日历页**: 读取数据库中 `status = Confirmed` 的记录进行打卡标记和详情展示。
    - **AI 记录页**: 展示数据库中 `status = Draft` 的记录。用户确认后，通过 Repository 更新数据库状态。
    - **趋势页**: 聚合数据库中所有已确认记录，绘制热量和体重曲线。

---

## 4. UI 组件结构

- **视觉/交互状态**: 第三阶段**未改变** UI 组件结构。`CalendarScreen`、`AiRecordScreen`、`TrendsScreen` 的视觉效果保持不变。
- **组件耦合**: 子组件（如 `DraftCard`）仍暂留在 Screen 文件中，后续建议拆分。

---

## 5. 当前数据模型

模型已迁移至 `com.example.domain.model` 包：

- **`DailyRecord`**: 包含 `id`, `date` (LocalDate), `status`, `meals`, `weightKg`, `aiSummary`。
- **`MealEntry`**: 包含 `mealType`, `hasPhoto`, `foods` (List)。**注意**: 图片目前仍仅通过 `hasPhoto: Boolean` 标记。
- **`FoodEntry`**: 包含 `id`, `name`, `quantity`, `estimatedCalories`, `confidence`。
- **`RecordStatus`**: `Draft` (草稿), `Confirmed` (已确认)。

---

## 6. 当前状态管理方式

- **ViewModel 职责**:
    - 不再直接持有数据源。
    - 通过 `RecordRepository` (接口) 观察记录流。
    - 使用 `viewModelScope` 发起异步更新操作。
    - **UI 事件管理**: 引入 `UiEvent` (SharedFlow) 处理一次性事件（如记录成功提示），避免状态重复触发。
- **依赖注入**: 使用 `DayZeroViewModel.Factory` 在 `AppNavigation` 中手动注入 `RoomRecordRepository`。
- **初始化逻辑**: `RoomRecordRepository` 在首次 `observeRecords` 时，若数据库为空则插入 Mock 示例数据。

---

## 7. 当前数据流

**完整数据流向**:
`Room Database` -> `DailyRecordDao` -> `RoomRecordRepository` -> `RecordRepository` (接口) -> `DayZeroViewModel` -> `uiState` (Flow) -> `UI Screens`

**操作流向**:
1. 用户在 UI 点击（如“确认录入”）。
2. ViewModel 调用 `repository.updateRecordStatus(id, Confirmed)`。
3. ViewModel 发射 `UiEvent.RecordConfirmed` 一次性事件。
4. 顶层 UI (`MainApp`) 监听到事件，显示 `SuccessConfirmOverlay` 动画。
5. Room 数据库更新，触发 `observeAllRecords` 的 Flow 发射新数据。
6. ViewModel 监听到新数据，更新 `uiState`。
7. UI 自动重绘（日历标记出现，草稿消失）。

---

## 8. 当前 mock 数据

- **状态**: 运行时不再依赖硬编码 Mock 数据。
- **逻辑**: `MockRecords.kt` 仅用于数据库首次启动时的“种子数据（Seeding）”填充。
- **Repository**: `MockRecordRepository` 已不再被 UI 使用，仅保留代码。

---

## 9. 主题和视觉系统

- **状态**: 第三阶段未修改主题和视觉系统。

---

## 10. 依赖和配置

- **Room**: 已正式启用，使用 KSP 处理注解。
- **JSON 序列化**: 使用 **Moshi** (`com.squareup.moshi`) 实现 `mealsJson` (List<MealEntry>) 的持久化。
- **其他**: `Retrofit` 和 `Firebase AI` 依赖已存在但尚未实际使用。

---

## 11. 当前架构评价

### 已经完成的改进
- **持久化能力**: 引入了 Room，解决了“刷新即消失”的问题。
- **架构分层**: 实现了 Model-Repository-ViewModel-UI 的清晰分层。
- **依赖抽象**: ViewModel 面向 Repository 接口编程，方便后续扩展或测试。

### 建议重构的部分
- **包结构优化**: UI 页面目前仍在 `ui/screens` 下，建议迁移到 `feature/xxx` 包结构。
- **组件拆分**: `AiRecordScreen` 内部的 `DraftCard` 逻辑较重，建议独立。
- **DI 框架**: 随着仓库和 DAO 增多，后续可能需要 Hilt 或 Koin 简化 Factory 代码。

### 风险较高的部分
- **JSON 存储**: 目前餐次详情以 JSON 字符串存储，不支持按食物项进行复杂的 SQL 查询。如果后续有“搜索特定食物”的需求，需拆分表结构。

---

## 12. 后续正式开发建议

**当前进度**:
1. 第一阶段：模型和 Mock 数据提取 (已完成)
2. 第二阶段：Repository 接口抽象 (已完成)
3. 第三阶段：Room 本地持久化 (已完成)

**后续路线**:
4. **第四阶段：真实 AI 集成**: 接入后端 API 或 Firebase AI，将对话文本解析为 `DailyRecord` (Draft 状态) 并存入 Room。
5. **第五阶段：多媒体功能**: 引入图片选择/拍照，将 `hasPhoto` 升级为 `photoUri`，支持照片展示。
6. **第六阶段：UI 组件化升级**: 将 screens 拆分为 features 包，细化组件重用。

---

## 13. 建议的正式包结构

当前项目已接近目标结构：
- `domain/model`: **已完成**
- `domain/repository`: **已完成**
- `data/local`: **已完成**
- `feature/ailog`, `feature/calendar`: **待完成** (目前在 `ui/screens`)

---

## 14. 给后续 AI 助手的简短总结

**项目摘要**:
本项目名为 **DayZero**，是一个基于 Kotlin + Compose 的减脂记录工具。目前项目已从原型阶段进入**标准开发阶段**。

**技术现状**: 采用 MVVM + Repository 架构。数据持久化层已通过 **Room** 实现，支持餐次、食物、热量及体重的本地保存。App 具备自动初始化示例数据的功能（Seeding）。UI 层基于 Material 3 提供了精美的日历视图、对话式记录界面和 Canvas 自定义折线图趋势分析。

**核心能力**: 能够持久化管理“草稿(Draft)”与“正式(Confirmed)”两种状态的记录。用户录入流程闭环（输入->草稿生成->编辑->确认->持久化存储->多维展示）已在本地逻辑上跑通。

**待开发重点**:
1. **AI 真实接入**: 目前对话为 Mock，需要接入大模型 API 自动解析饮食。
2. **多媒体支持**: 需要实现照片挂载功能。
3. **架构细化**: 随着 Feature 增多，建议按功能模块拆分包结构。
