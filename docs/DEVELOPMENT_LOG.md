# DayZero 开发日志

## 阶段 0：Google AI Studio Android Build MVP
- 生成 Kotlin + Jetpack Compose Android 原型项目。
- 完成日历 (Calendar)、AI记录 (AI Record)、趋势 (Trends) 三个核心页面。
- 实现“用户对话 -> AI 生成草稿 -> 用户编辑 -> 确认录入”的 Mock 交互闭环。
- 视觉风格确认为简约、温暖的 M3 风格。

## 阶段 1：模型与 Mock 数据提取
- **架构清理**：将 `DailyRecord`、`MealEntry`、`FoodEntry` 等领域模型从 `DayZeroViewModel` 中提取到独立的 `com.example.domain.model` 包下。
- **数据解耦**：将 `createMockRecords` 提取到 `com.example.data.mock`，使 ViewModel 逻辑更加纯粹。
- **验证**：UI 和业务行为保持 100% 一致。

## 阶段 2：Repository 抽象
- **引入仓库模式**：创建 `RecordRepository` 接口，定义了观察记录和更新记录的契约。
- **实现 Mock 仓库**：创建 `MockRecordRepository`，通过 `Flow` 模拟响应式数据更新。
- **ViewModel 重构**：`DayZeroViewModel` 通过 Repository 观察和更新 records，不再直接操作数据列表。
- **目标**：成功为后续无缝切换 Room 持久化做好了架构铺垫。

## 阶段 3：Room 本地持久化
- **本地存储实现**：
    - 新增 `DailyRecordEntity`：支持 `mealsJson` 复杂字段存储。
    - 新增 `DailyRecordDao`：实现高效的 Flow 查询和 Upsert 操作。
    - 新增 `DayZeroDatabase`：Room 数据库单例。
    - 新增 `DailyRecordMapper`：使用 **Moshi** 处理领域模型与数据库实体之间的 JSON 转换。
    - 新增 `RoomRecordRepository`：正式实现数据持久化到本地。
- **依赖注入升级**：创建 `DayZeroViewModel.Factory`，在 `AppNavigation` 中手动注入数据库依赖。
- **数据初始化**：实现 **Database Seeding** 策略。首次启动且数据库为空时，自动将 Mock 数据插入 Room，确保初次安装体验。
- **持久化闭环**：确认录入状态、删除食物、修改体重、趋势图更新等行为现在均可**跨 App 重启持久化**。
- **UI 优化**：引入 `UiEvent` 系统，将“确认成功”提示从黑色 Snackbar 替换为居中、主题一致的成功动画组件 (`SuccessConfirmOverlay`)。

## 阶段 4：AI Draft 架构建立
- **AI 抽象层**：创建了 `AiDraftRepository` 接口，定义了从文本生成草稿的标准契约。
- **模拟 AI 实现**：实现了 `FakeAiDraftRepository`，基于关键词匹配模拟 AI 分析饮食并生成结构化草稿。
- **数据转换**：引入 `CheckinDraftMapper`，实现了 AI 草稿到 Room 持久化模型 `DailyRecord` 的无缝转换。
- **流程跑通**：用户在 AI 记录页输入文本，点击发送即可在本地生成并持久化一个新的 Draft，随后可继续编辑并确认录入。
- **UI 集成**：发送按钮集成了 `isAnalyzing` 状态，显示加载动画并防止重复提交。

## 当前仍未完成的部分
- **真实 AI**：底层已具备切换能力，但目前仍是本地 Fake 逻辑。
- **图片功能**：`MealEntry` 中仅有 `hasPhoto` 标记，尚无真实图片存储。
- **云同步**：暂无登录及多端同步。

## 下一阶段计划
- **真实 AI 后端接入**：接入 LLM API 自动解析用户输入的饮食文本。
- **CheckinDraft 返回结构稳定化**：规范化 AI 返回的 JSON 格式以匹配 `DailyRecord`。
- **图片选择 / 拍照**：集成系统相册与相机。
- **模型升级**：将 `MealEntry` 从 `hasPhoto: Boolean` 升级为 `photoUri: String?`，支持真实图片展示。
