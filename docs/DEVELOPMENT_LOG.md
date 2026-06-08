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
- **AI 抽象层**：创建了 `AiDraftRepository` 接口。
- **模拟 AI 实现**：实现了 `FakeAiDraftRepository` 进行本地测试。

## 阶段 5：真实 AI 后端接入 (Supabase)
- **云函数部署**：在 Supabase 部署了 `generate-checkin-draft` Edge Function，对接 Kimi API。
- **远程数据层**：
    - 新增 `AiDraftApiService` (Retrofit)。
    - 新增 DTO 系列与 `AiDraftRemoteMapper`。
    - 引入 `NetworkModule` 配置 OkHttp 拦截器自动注入 Supabase 密钥。
- **仓库切换**：实现 `RemoteAiDraftRepository` 并设置为 `DayZeroViewModel` 的默认数据源。
- **安全性保证**：Kimi API Key 彻底从移动端移除，仅保存在云端 Secret。
- **错误处理**：增加了针对网络和 AI 解析失败的 `UiEvent.Error` 反馈逻辑。
- **权限修复**：在 Manifest 中补全了必要的 `INTERNET` 权限，解决了发送消息时的闪退问题。
- **闭环达成**：用户现在可以使用真实自然语言进行饮食记录分析。

## 阶段 6：交互闭环与增量录入优化
- **聊天 UI 实装**：实现了真正的消息流，支持用户消息、分析中状态、以及 AI 引导语的展示。
- **多次分餐合并**：重构了确认录入逻辑，同一天只保留一条正式记录，支持早餐、午餐、晚餐的多次增量合并。
- **冲突处理机制**：增加了冲突处理对话框，用户可选择覆盖、仅添加非冲突项或取消。
- **本地总结构建**：引入 `DailySummaryBuilder`，在合并后根据全天已摄入的食物自动生成温柔、低压力的每日总结。
- **数据一致性**：确认后的 Draft 会被自动清理，保持 Room 数据库整洁。
- **UI 布局优化**：修复了 AI 记录页底部输入栏遮挡卡片（如 DraftCard/ChoiceCard）的问题。通过合理利用 `Scaffold` 的 `bottomBar` 和 `imePadding`，使交互体验更加流畅。

## 阶段 4D-1：DraftCard 多餐能力补齐与协议修复
- **DraftCard 能力升级**：将单餐结构升级为 `meals[]` 多餐结构，支持用户一次性记录多餐饮食（如早餐和午餐）。
- **体重记录完善**：恢复了旧版 DraftCard 中的体重填写入口，允许体重为空。
- **UI 和交互优化**：实现了 DraftCard 中每个 Item 的编辑和删除功能的本地状态管理；完善了 `show_confirm_card` 的确认回调机制，确保写入用户最终确认的数据。
- **协议错误修复**：修复了网络请求 DTO 中的序列化问题，兼容了 Edge Function 新加入的 `id` 和 `meals` 字段，移除了对纯卡片反馈（空文本）的崩溃报错逻辑，保障了 `assistant-turn-v2` 单入口全链路通畅。

## 当前仍未完成的部分
- **多媒体存储**：`MealEntry` 尚未支持真实图片存储，目前仍通过 `hasPhoto` 标记。
- **云同步**：暂无登录及多端同步。

## 下一阶段计划
- **真实 AI 后端接入**：接入 LLM API 自动解析用户输入的饮食文本。
- **CheckinDraft 返回结构稳定化**：规范化 AI 返回的 JSON 格式以匹配 `DailyRecord`。
- **图片选择 / 拍照**：集成系统相册与相机。
- **模型升级**：将 `MealEntry` 从 `hasPhoto: Boolean` 升级为 `photoUri: String?`，支持真实图片展示。
