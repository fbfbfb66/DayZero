# AI Record Flow Audit

## 1. 核心链路 (Phase 21: 多维语义与即时响应)

### 1.1 发送与响应时序 (DayZeroLatency)
- **T0**: 用户点击发送。
- **T1**: 立即在 UI 渲染用户消息气泡（乐观更新）。
- **T2**: 立即设置 `isAnalyzing = true`，TypingIndicator 出现（要求 T2-T0 < 100ms）。
- **T3**: `HybridIntentRouter.classify` 开始。
- **T4**: 意图识别结束（本地或远程），识别出 `primaryIntent`, `isFollowUp` 等。
- **T5**: 若为对话意图，调用 `AiCompanionReplyRepository.generateReply`（携带 `recentMessages`）。
- **T6**: 远程回复内容返回。
- **T7**: 助手消息插入 Room 并更新 UI。
- **T8**: `isAnalyzing = false`，TypingIndicator 消失。

### 1.2 意图分流与职责
- **HybridIntentRouter**: 消息主入口。负责本地高置信分流（体重、清空指令）与云端分类（模糊饮食、咨询、追问、情绪）。
- **Follow-up 识别**: 识别“意思是、所以、那我可以、是不是”等追问特征。若为 `isFollowUp`，强制 Companion 回复层承接上下文（最近 6-10 条记录）。
- **Companion Reply**: 处理 `CravingSupport`, `FoodInfoQuery`, `Follow-up`。**不生成卡片、不写 Room 业务记录**。

---

## 2. 状态机与写库边界

### 2.1 会话状态 (AiRecordConversationState)
- `Idle`: 空闲。
- `AnalyzingFood`: 正在解析食物。
- `WaitingMealTypeSelection`: 缺少餐次，等待用户点击 ChoiceCard。
- `ShowingDraft`: 显示 DraftCard，等待用户确认转正。
- `ConfirmingWeight`: 显示 WeightCard，等待用户确认写入。
- `ConfirmingDelete / ConfirmingEdit`: 显示确认卡片（目前以提示和界面展示为主）。

### 2.2 写库边界 (Room Persistence)
- **Confirmed 写入**: 仅在用户明确点击 `DraftCard` 确认、`WeightCard` 确认后发生。
- **修改/删除真实写库**: **目前为界面预留/提示阶段，真实写库逻辑待稳定接入**。

---

## 3. 风险审计：卡片过期交互

**当前最大风险**: 遗留在聊天历史中的旧卡片（ChoiceCard / WeightCard 等）在用户发起新会话后仍然可点击。如果点击历史卡片，可能导致当前的会话状态（ConversationState）被旧逻辑错误篡改。

**解决方案 (待实现)**: 引入 `activeInteractionId`。
- 每个交互卡片 payload 包含一个 `interactionId`。
- ViewModel 维护一个全局 `currentInteractionId`。
- 只有当 `callback.interactionId == currentInteractionId` 时，才执行逻辑。
- 此项任务应作为下一阶段的首要任务。
