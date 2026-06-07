# 项目上下文分析报告 (DayZero)

本文档旨在为后续 AI 助手提供当前 Android 项目的详尽上下文，以便进行后续开发、重构和功能接入规划。

---

## 1. 项目基本信息

- **项目名称**: My Application (代码中应用名称显示为 "DayZero")
- **Android 包名**: `com.example` (由 `app/src/main/java/com/example` 确认)
- **当前技术栈**: Kotlin + Jetpack Compose + Material 3 + ViewModel + Navigation Compose
- **Kotlin 版本**: `2.2.10`
- **Gradle 版本**: `8.x` (基于 `gradle-wrapper.properties` 推断，未直接读取文件但项目结构符合)
- **Android Gradle Plugin (AGP) 版本**: `9.2.1`
- **Compose / Material 相关依赖**: 
    - Compose BOM: `2024.09.00`
    - Material 3: 已集成
    - 常用图标扩展: 已集成 (`androidx.compose.material:material-icons-extended`)
- **SDK 版本**:
    - `minSdk`: 24
    - `targetSdk`: 36
    - `compileSdk`: 36
- **架构模式**: 单 Activity 架构 (`MainActivity`)
- **Jetpack Compose**: 是
- **Material 3**: 是
- **ViewModel**: 是 (`DayZeroViewModel`)
- **Navigation**: 是 (`AppNavigation.kt`)
- **本地数据库**: **未确认/未使用** (代码中已引入 Room 依赖，但当前未发现 Entity 或 DAO 实现，数据均在内存中)
- **真实网络请求**: **未确认/未使用** (已引入 Retrofit/OkHttp 依赖，但当前未发现接口定义)
- **真实 AI API 调用**: **未确认/未使用** (已引入 Firebase AI 依赖，但当前未发现实际调用代码)

---

## 2. 项目目录结构

主要目录结构如下：

- **`app/src/main`**: 项目核心目录。
- **`app/src/main/java/com/example`**:
    - **`MainActivity.kt`**: 入口 Activity，负责加载 `MyApplicationTheme` 和 `MainApp`。
    - **`DayZeroViewModel.kt`**: 核心 ViewModel，负责全局状态管理、Mock 数据加载及业务逻辑（如删除食物、确认草稿）。
    - **`ui/`**: UI 相关代码。
        - **`AppNavigation.kt`**: 导航配置，定义了 `Screen` 枚举、底部导航栏及 `NavHost`。
        - **`screens/`**: 页面级 Composable。
            - `CalendarScreen.kt`: 日历主页。
            - `AiRecordScreen.kt`: AI 对话及记录草稿确认页。
            - `TrendsScreen.kt`: 数据趋势分析页。
        - **`theme/`**: 视觉系统配置。
            - `Color.kt`, `Theme.kt`, `Type.kt`: 定义品牌色、M3 主题及字体。
- **`app/src/main/res`**: Android 资源目录，目前包含默认图标和 XML 配置。
- **`app/build.gradle.kts`**: 模块级 Gradle 配置，包含 SDK 版本及依赖列表。
- **`gradle/libs.versions.toml`**: 依赖版本集中管理文件。
- **`AndroidManifest.xml`**: 应用声明文件，定义了包名和主 Activity。

---

## 3. 核心页面分析

### 3.1 日历页 (Calendar)
- **文件路径**: `app/src/main/java/com/example/ui/screens/CalendarScreen.kt`
- **主要 Composable**: `CalendarScreen`
- **职责**: 显示每日打卡情况，查看特定日期的摄入详情。
- **显示数据**: 
    - 月度日历视图（带打卡标记）。
    - 连续记录天数（Streak）。
    - 选中日期的总热量、体重（可选）、餐次列表、AI 总结。
- **交互**: 点击日期切换详情，点击“用 AI 记录”跳转至 AI 页，展开/折叠餐次详情。
- **数据来源**: `DayZeroViewModel.uiState` 中的 `records`（过滤状态为 `Confirmed` 的记录）。
- **当前状态**: 使用 Mock 数据。

### 3.2 AI 记录页 (AI Record)
- **文件路径**: `app/src/main/java/com/example/ui/screens/AiRecordScreen.kt`
- **主要 Composable**: `AiRecordScreen`
- **职责**: 通过对话形式记录饮食，展示并编辑 AI 生成的记录草稿，确认后转为正式记录。
- **显示数据**:
    - 聊天消息列表（Mock）。
    - **AI 草稿确认窗口 (DraftCard)**：包含日期、预估总热量、体重输入框、餐次及食物详情。
- **交互**: 输入文本模拟发送，删除食物行，修改体重，点击“确认录入”。
- **数据来源**: `DayZeroViewModel.uiState` 中的 `records`（寻找状态为 `Draft` 的记录）。
- **当前状态**: 使用 Mock 数据。

### 3.3 趋势页 (Trends)
- **文件路径**: `app/src/main/java/com/example/ui/screens/TrendsScreen.kt`
- **主要 Composable**: `TrendsScreen`
- **职责**: 以图表形式展示热量和体重的变化趋势。
- **显示数据**:
    - 热量趋势折线图。
    - 体重趋势折线图。
    - AI 洞察建议。
- **交互**: 切换时间维度（7天/30天），点击图表点查看详情。
- **数据来源**: `DayZeroViewModel.uiState` 中的 `records`（状态为 `Confirmed` 的数据）。
- **当前状态**: 使用 Mock 数据，自定义绘制 Canvas 图表。

---

## 4. UI 组件结构

- **`DraftCard`**:
    - **路径**: `AiRecordScreen.kt`
    - **功能**: 展示 AI 生成的草稿详情，支持编辑。
    - **参数**: `DailyRecord`, `DayZeroViewModel`。
    - **逻辑**: 包含简单的删除逻辑调用和状态绑定（体重输入）。
    - **建议**: 后续建议独立成单独文件。
- **`ExpandableMealItem`**:
    - **路径**: `CalendarScreen.kt`
    - **功能**: 可折叠的餐次卡片，显示食物清单。
    - **参数**: `MealEntry`。
- **`SimpleLineChart`**:
    - **路径**: `TrendsScreen.kt`
    - **功能**: 通用自定义折线图。
    - **参数**: `List<ChartDataPoint>`, `lineColor`。
    - **复用性**: 高。
- **通用组件**: 当前项目中通用 Button、Card 等直接使用了 M3 原生组件并配合 `ui.theme` 中定义的颜色。

---

## 5. 当前数据模型

所有模型当前均定义在 `DayZeroViewModel.kt` 中：

- **`RecordStatus` (Enum)**: `Draft` (草稿), `Confirmed` (已确认)。
- **`MealType` (Enum)**: `Breakfast`, `Lunch`, `Dinner`, `Snack`。
- **`FoodEntry` (Data Class)**:
    - 字段: `id`, `name`, `quantity`, `estimatedCalories`, `confidence`。
    - 含义: 单个食物项的 ID、名称、数量描述、估算热量、置信度。
- **`MealEntry` (Data Class)**:
    - 字段: `mealType`, `hasPhoto`, `foods` (List)。
    - 含义: 餐次类型、是否有照片、食物列表。
- **`DailyRecord` (Data Class)**:
    - 字段: `id`, `date`, `status`, `meals`, `weightKg`, `aiSummary`。
    - 含义: 每日记录 ID、日期、状态（草稿/正式）、餐次列表、体重、AI 总结。
- **`AppState` (Data Class)**: 维护 `currentDate`, `records` 列表, `isAnalyzing` 等 UI 状态。

**评价**: 模型结构清晰，符合产品逻辑，适合长期使用，但建议后续移至专门的 `model` 包。

---

## 6. 当前状态管理方式

- **ViewModel**: 使用单一 `DayZeroViewModel` 管理全局状态。
- **状态位置**:
    - 全局数据（records, currentDate）存放在 ViewModel 的 `_uiState` (MutableStateFlow) 中。
    - 临时输入状态（如输入框文本、页面内展开状态）存放在 Composable 内部的 `remember { mutableStateOf(...) }` 中。
- **selectedDate 管理**: 当前在 `CalendarScreen` 内部管理，未提升至 ViewModel。
- **数据流转**:
    1. 点击“确认录入”调用 `viewModel.confirmDraft`。
    2. ViewModel 更新 `records` 中对应记录的状态为 `Confirmed`。
    3. UI 观察到 `uiState` 变化，日历页自动显示新打卡标记，趋势页自动重绘。
- **风险**: 状态虽然集中，但业务逻辑开始堆积在 ViewModel 中，随着功能增加需按 Feature 拆分或使用 UseCase。

---

## 7. 当前数据流分析

**核心流程实现情况**:
1. 用户输入饮食: **Mock 实现** (界面有输入框，但无真实 AI 对话逻辑)。
2. AI 生成记录草稿: **Mock 实现** (初始加载即包含一个 `Draft` 状态的记录)。
3. 用户编辑草稿: **部分实现** (支持删除食物项、填写体重)。
4. 用户点击确认录入: **已实现** (点击后修改状态)。
5. 生成正式记录: **已实现** (状态变更为 `Confirmed`)。
6. 日历出现标记: **已实现** (日历组件绑定了 `Confirmed` 记录)。
7. 趋势图更新: **已实现** (趋势图组件绑定了 `Confirmed` 记录)。

**合规性检查**: 符合“AI 只生成草稿，用户确认后才保存”的产品规则。代码中 `RecordStatus` 明确区分了草稿和正式记录。

---

## 8. 当前 mock 数据

- **文件路径**: `app/src/main/java/com/example/DayZeroViewModel.kt` 中的 `loadMockData()` 方法。
- **结构**: 包含 2026年6月1日至6月6日的已确认记录，以及一个 6月7日的草稿记录。
- **覆盖范围**: 模拟了已确认记录、AI 草稿、以及足够生成趋势图的数据。
- **后续任务**: 需要替换为 Room 本地持久化存储。

---

## 9. 主题和视觉系统

- **核心颜色 (`Color.kt`)**:
    - `BrandGreen` (#88A68B): 品牌主色，用于按钮、选中状态、重要文本。
    - `WarmBackground` (#FAF9F6): 全局背景色。
    - `LightGreen` (#E8F5E9): 次要背景、辅助标记。
    - `TextPrimary` (#3A3A35): 主要文本色。
- **视觉风格**: 简约、温暖、类似 Notion/日系清新风格。
- **Material 3**: 已启用并使用了 `Scaffold`, `NavigationBar`, `TopAppBar` 等组件。
- **间距与圆角**: 广泛使用大圆角（24.dp - 32.dp）和 16.dp 的标准间距。

---

## 10. 依赖和配置

- **主要依赖**:
    - `androidx.navigation:navigation-compose`: 已包含。
    - `androidx.lifecycle:lifecycle-viewmodel-compose`: 已包含。
    - `androidx.room:room-runtime/ktx`: **已声明依赖，但未使用**。
    - `com.squareup.retrofit2:retrofit`: **已声明依赖，但未使用**。
    - `io.coil-kt:coil-compose`: 已声明。
    - `com.google.firebase:firebase-ai`: 已声明。
- **配置文件**:
    - `settings.gradle.kts`: 标准多项目配置（虽然只有一个 app 模块）。
    - `app/build.gradle.kts`: 启用了 Compose 和 KSP。

---

## 11. 当前架构评价

### 可以保留的部分
- **数据模型设计**: `DailyRecord` 的层次结构（Record -> Meal -> Food）非常贴合饮食记录业务。
- **视觉风格与主题**: 品牌色彩定义和组件样式非常统一，体验良好。
- **状态驱动 UI**: 核心流转逻辑已经跑通，UI 对状态变化的响应很及时。

### 建议重构的部分
- **文件拆分**: `DayZeroViewModel.kt` 承载了太多数据模型定义，应将 Model 移出。
- **状态提升**: `selectedDate` 等影响多页面的状态应考虑从页面内部提升至 ViewModel。
- **Mock 解耦**: Mock 数据逻辑目前硬编码在 ViewModel 中，应提取到 Repository 层。

### 风险较高的部分
- **业务代码集中**: 所有的业务逻辑都在单个 ViewModel 中。
- **UI 组件重用**: `DraftCard` 等复杂组件写在 `AiRecordScreen.kt` 内部，不利于单元测试和复用。

### 后续开发前必须先处理的部分
- **建立 Room 持久化层**: 目前数据刷新即消失。
- **包结构规范化**: 当前代码都在 `com.example` 根目录下。

---

## 12. 后续正式开发建议

**推荐路线**:
1. **第一阶段：整理与规范**: 按照功能拆分包结构，将模型提取到独立文件，将复杂子组件提取到 `ui.components`。
2. **第二阶段：本地持久化 (Room)**: 实现 `DailyRecordDao`，将 ViewModel 中的 Mock 数据迁移到数据库初始化中，实现 CRUD。
3. **第三阶段：真实 AI 集成**: 接入 Firebase AI 或自定义 API，将用户输入的文本发送给 AI 并解析成 `DailyRecord` (Status=Draft)。
4. **第四阶段：图片与相机**: 实现图片选择器，并将图片 URI 挂载到 `MealEntry`。

---

## 13. 建议的正式包结构

当前项目与目标结构的差距：**目前所有代码混在根包下**。

**推荐调整建议**:
- `com.example.data`: 存放 Room 相关实现、Repository。
- `com.example.domain.model`: 存放 `DailyRecord`, `MealEntry` 等。
- `com.example.ui.feature.calendar`: 日历相关 Screen 和 Component。
- `com.example.ui.feature.ailog`: AI 记录相关 Screen 和 Component。
- `com.example.ui.feature.trends`: 趋势相关 Screen 和 Component。

---

## 14. 给后续 AI 助手的简短总结

**项目摘要**:
本项目名为 **DayZero**，是一个专注于“减脂每日记录”的 Android 应用。目前已完成原型开发（MVP 第一阶段），实现了**核心交互逻辑**：用户通过模拟 AI 交互生成饮食草稿 -> 编辑草稿 -> 确认录入 -> 数据自动同步至日历视图和趋势图表。

**核心技术栈**: Kotlin 2.2.10, Jetpack Compose, Material 3, ViewModel, Navigation。

**当前状态**: UI 表现力强，品牌视觉系统已建立；业务逻辑已跑通，但数据目前完全依赖内存中的 Mock。项目已集成 Room、Retrofit 等库的依赖，但尚未开始实际编码实现。

**主要风险**: 代码耦合度较高（主要逻辑集中在 ViewModel），缺乏持久化层。

**下一步建议**: 优先建立 Data 层，利用已有的 Room 依赖实现本地存储，确保用户记录能够持久化；随后将硬编码的 Mock 数据替换为真实的 Repository 注入。
