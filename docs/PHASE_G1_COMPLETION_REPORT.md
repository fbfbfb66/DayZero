# Phase G1 完成报告

## 1. 最终状态

**PHASE_G1_COMPLETE**

## 2. 修改文件

### 已有文件修改

| 文件 | 修改内容 |
| --- | --- |
| `server/dayzero-ai-gateway/src/main.ts` | 导出 `handler`；新增 `/api/ai/assistant-turn-v2` 与 `/api/ai/assistant-turn-v2-stream`；保留旧路径别名；新增 `GET /ready`；统一为所有响应补齐 `X-Request-Id`；处理 404/405/OPTIONS。 |
| `server/dayzero-ai-gateway/src/request_id.ts` | 新增 `resolveRequestId`，安全复用入站 `X-Request-Id`，非法/缺失时生成新 ID。 |
| `server/dayzero-ai-gateway/src/body_reader.ts` | JSON body 不是 object 时返回 `400 INVALID_BODY_TYPE`。 |
| `server/dayzero-ai-gateway/src/shared/request_parser.ts` | 增加 turnType/date/recentMessages/todayRecord/pendingDraft/userText/interactionResult 类型校验。 |
| `docs/PROJECT_CONTEXT_FOR_CHATGPT.md` | 追加 Phase G1 记录。 |
| `docs/DEVELOPMENT_LOG.md` | 追加 Phase G1 中文记录。 |

### 新增文件

| 文件 | 说明 |
| --- | --- |
| `server/dayzero-ai-gateway/src/handlers/ready.ts` | `/ready` readiness handler，静态检查配置，不调用外部服务。 |
| `server/dayzero-ai-gateway/src/handlers/ready_test.ts` | Readiness 测试（8 个）。 |
| `server/dayzero-ai-gateway/src/main_test.ts` | 路由/方法/Request ID 测试（11 个）。 |
| `server/dayzero-ai-gateway/src/request_id_test.ts` | Request ID 解析测试（10 个）。 |
| `server/dayzero-ai-gateway/src/shared/request_parser_test.ts` | 请求入口校验测试（13 个）。 |
| `server/dayzero-ai-gateway/src/body_reader_test.ts` | 非法 body 类型测试（6 个）。 |

## 3. 路由结果

| 方法 | 路径 | Handler | 说明 |
| --- | --- | --- | --- |
| GET | `/health` | `handleHealth` | 进程存活 |
| GET | `/ready` | `handleReady` | 配置就绪 |
| POST | `/assistant-turn-v2` | `handleAssistantTurnV2` | 旧兼容别名 |
| POST | `/assistant-turn-v2-stream` | `handleAssistantTurnV2Stream` | 旧兼容别名 |
| POST | `/api/ai/assistant-turn-v2` | `handleAssistantTurnV2` | 正式路径 |
| POST | `/api/ai/assistant-turn-v2-stream` | `handleAssistantTurnV2Stream` | 正式路径 |

新旧路径调用同一 handler，无内部转发，无重复 Kimi 请求。

## 4. Readiness 行为

- `/health`：仅表示 Gateway 进程仍在运行、HTTP event loop 可响应；不调用 Kimi/Supabase/JWKS，不暴露配置。
- `/ready`：表示进程具备接受 AI 请求的基本条件；检查配置已加载、Kimi URL/Key/Model 有效、启用认证时 Supabase URL/Audience 存在且格式合法；不调用外部服务，不输出 Secret；返回 `200 {status:"ready"}` 或 `503 {error,errorCode}`。

## 5. Request ID 契约

- 入站：读取 `X-Request-Id`，仅当长度 8–128 且为安全 ASCII `[A-Za-z0-9\-_.:]` 时复用。
- 缺失或非法：生成新的安全随机 ID，不记录非法原始值。
-  outbound：所有响应（health、ready、fallback JSON、SSE、4xx/5xx）均携带 `X-Request-Id`。
- 日志：只记录解析后的 ID。
- Kimi 上游：Request ID 保留在 Gateway 内部 logger context 和 timing 中；未向 Moonshot 发送自定义 header。

## 6. 请求校验

新增稳定错误场景：

| 场景 | HTTP | errorCode |
| --- | ---: | --- |
| body 不是 object（null/array/string/number） | 400 | `INVALID_BODY_TYPE` |
| `userText` 不是 string | 400 | `INVALID_USER_TEXT` |
| `turnType` 非法 | 400 | `INVALID_TURN_TYPE` |
| `date` 不是 string | 400 | `INVALID_DATE` |
| `recentMessages` 不是 array | 400 | `INVALID_RECENT_MESSAGES` |
| `todayRecord` 不是 object | 400 | `INVALID_TODAY_RECORD` |
| `pendingDraft` 不是 object | 400 | `INVALID_PENDING_DRAFT` |
| `interaction_result` 缺少 `interactionResult` | 400 | `MISSING_INTERACTION_RESULT` |
| 非法 JSON | 400 | （原 `Invalid JSON body`） |
| 请求体过大 | 413 | `REQUEST_BODY_TOO_LARGE` |

合法 Android DTO 与 text-only/Vision/interaction_result 现有请求保持兼容；未知字段默认忽略。

## 7. 测试

| 命令 | 退出码 | 结果 |
| --- | ---: | --- |
| `deno fmt --check` | 0 | 37 个文件通过 |
| `deno lint` | 0 | 34 个文件通过 |
| `deno check src/main.ts` | 0 | 通过 |
| `deno task test` | 0 | 80 passed，0 failed |

测试总数从原 33 个增加到 80 个。

## 8. 冻结文件校验

| 文件 | 开始 SHA-256 | 结束 SHA-256 | 一致 |
| --- | --- | --- | --- |
| `src/shared/assistant_upstream_timeout.ts` | `9e5cd566924c2017c6bd11619eef70e27a5c04ef5583c07d84b9fd7372ca6f37` | `9e5cd566924c2017c6bd11619eef70e27a5c04ef5583c07d84b9fd7372ca6f37` | 是 |
| `src/shared/prompt.ts` | `f616f8b177f83fcffd94bc55483c526521233beb9e34fbede329934d2483f66a` | `f616f8b177f83fcffd94bc55483c526521233beb9e34fbede329934d2483f66a` | 是 |
| `src/shared/normalization.ts` | `bba2537fbb09f76dbedfae53d93fcb63fd16ba81fe8da2d8a63545d14920d369` | `bba2537fbb09f76dbedfae53d93fcb63fd16ba81fe8da2d8a63545d14920d369` | 是 |
| `src/shared/assistant_vision.ts` | `847974511740d89bc616f5f2153c2a16a8abac54d19160acf1388ca36f04c9bd` | `847974511740d89bc616f5f2153c2a16a8abac54d19160acf1388ca36f04c9bd` | 是 |
| `src/shared/explicit_photo_meal_assignment.ts` | `c28f3e2918c7489bb2ba5e781eb3307fed3ade039477689126657ea11009aad7` | `c28f3e2918c7489bb2ba5e781eb3307fed3ade039477689126657ea11009aad7` | 是 |

## 9. 范围声明

- 是否修改 Android：**否**
- 是否修改 Supabase：**否**
- 是否修改 timeout：**否**
- 是否修改 Prompt：**否**
- 是否修改 normalization：**否**
- 是否修改 Vision：**否**
- 是否部署：**否**
- 是否调用 Kimi：**否**
- 是否执行 Git 写操作：**否**

## 10. 已知限制与下一阶段

- 阿里云 ECS 仍缺少 SSH 连接信息；未验证现网 Nginx、路由、JWT、SSE buffering。
- 下一阶段建议：Gateway JWT/JWKS 与日志安全加固。
- 本轮未实施下一阶段内容。
