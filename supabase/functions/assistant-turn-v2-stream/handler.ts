import "jsr:@supabase/functions-js@2/edge-runtime.d.ts";
import {
  ensureInteractionContinuationAction,
  normalizeActions,
} from "./normalization.ts";
import {
  buildVisionAwareUserMessage,
  parseAndValidateAttachments,
  VISION_PROMPT_ADDENDUM,
  VisionValidationError,
} from "../_shared/assistant_vision.ts";
import { selectStreamHeaderTimeoutMs } from "../_shared/assistant_upstream_timeout.ts";

const MOONSHOT_API_URL = "https://api.moonshot.cn/v1/chat/completions";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

export async function handler(req: Request): Promise<Response> {
  const functionStartedAt = performance.now();

  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  if (req.method !== "POST") {
    return jsonResponse({ error: "Method Not Allowed" }, 405);
  }

  const moonshotApiKey = Deno.env.get("MOONSHOT_API_KEY");
  if (!moonshotApiKey) {
    return jsonResponse({
      error: "Internal Server Error (Missing MOONSHOT_API_KEY)",
    }, 500);
  }

  const body = await req.json();
  const requestParsedAt = performance.now();
  const traceId = typeof body.traceId === "string" ? body.traceId : null;
  const userText = typeof body.userText === "string"
    ? body.userText.trim()
    : "";
  if (!userText) {
    const hasAttachments = Array.isArray(body.attachments) &&
      body.attachments.length > 0;
    if (hasAttachments) {
      return jsonResponse({
        error: "Vision requests require non-empty text",
        errorCode: "EMPTY_VISION_TEXT",
      }, 400);
    }
    return jsonResponse({ error: "Missing userText" }, 400);
  }

  const stream = new ReadableStream({
    async start(controller) {
      const encoder = new TextEncoder();
      const sendEvent = (event: string, data: unknown) => {
        controller.enqueue(
          encoder.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`),
        );
      };

      try {
        sendEvent("status", { state: "started" });
        const promptBuildStartedAt = performance.now();
        const promptVersion =
          "stream_compact_v7_deterministic_multi_meal_photo_assignment";
        const promptCacheKey = normalizePromptCacheKey(body.promptCacheKey);
        const recentContext = buildRecentContext(body.recentMessages);
        const turnType = typeof body.turnType === "string"
          ? body.turnType.trim()
          : "user_message";
        const interactionResult = body.interactionResult;

        const systemPrompt = buildSystemPrompt();
        const userContent = buildUserContent({
          date: body.date ?? "",
          recentContext,
          turnType,
          userText,
          interactionResult,
          todayRecord: body.todayRecord,
        });

        let userMessage;
        let validatedAttachments;
        try {
          validatedAttachments = parseAndValidateAttachments(
            body.attachments,
            turnType,
            userText,
          );
          userMessage = buildVisionAwareUserMessage(
            body.attachments,
            turnType,
            userText,
            userContent,
          );
        } catch (error) {
          if (error instanceof VisionValidationError) {
            sendEvent("error", {
              message: error.message,
              code: error.code,
            });
            return;
          }
          throw error;
        }
        const promptBuiltAt = performance.now();

        const attachmentCount = validatedAttachments.length;
        const totalDecodedAttachmentBytes = validatedAttachments.reduce(
          (total, attachment) => total + attachment.decodedByteSize,
          0,
        );
        const selectedStreamHeaderTimeoutMs = selectStreamHeaderTimeoutMs(
          attachmentCount,
          totalDecodedAttachmentBytes,
        );
        const continuationHasMedia = hasContinuationMediaIds(interactionResult);
        const hedgedAttemptCount = attachmentCount > 0 || continuationHasMedia ? 3 : 1;
        const upstreamBudgetMs = attachmentCount > 0
          ? 35_000
          : continuationHasMedia
          ? 20_000
          : 15_000;
        const kimiRequestStartedAt = performance.now();
        const race = await raceMoonshotCandidates({
          apiKey: moonshotApiKey,
          systemPrompt,
          userMessage,
          promptCacheKey,
          attemptCount: hedgedAttemptCount,
          budgetMs: upstreamBudgetMs,
          onPrimaryDelta: (text) => sendEvent("reply_delta", { text }),
        });
        const fullContent = race.content;
        const firstTokenAt = race.firstTokenAt;
        const upstreamHeadersAt = race.firstHeadersAt;
        const kimiStreamEndedAt = race.winnerCompletedAt;
        const kimiJsonParseStartedAt = performance.now();
        const parsed = JSON.parse(fullContent);
        const kimiJsonParsedAt = performance.now();
        const protocolValidationStartedAt = performance.now();

        const compactJsonUsed = typeof parsed.r === "string" ||
          Array.isArray(parsed.a);
        const reply =
          (typeof parsed.r === "string" ? parsed.r : parsed.reply)?.trim?.() ??
            "";
        const actions = Array.isArray(parsed.a)
          ? parsed.a
          : Array.isArray(parsed.actions)
          ? parsed.actions
          : [];

        if (!reply) throw new Error("Kimi returned blank reply");
        let fallbackOriginalText = userText;
        if (
          body.interactionResult &&
          typeof body.interactionResult === "object" &&
          typeof body.interactionResult.originalText === "string" &&
          body.interactionResult.originalText.trim().length > 0
        ) {
          fallbackOriginalText = body.interactionResult.originalText.trim();
        }
        const continuationContext = body.interactionResult &&
            typeof body.interactionResult === "object"
          ? body.interactionResult.continuationContext
          : null;
        const actionType = body.interactionResult &&
            typeof body.interactionResult === "object" &&
            typeof body.interactionResult.actionType === "string"
          ? body.interactionResult.actionType
          : "";
        const selectedOptionId = body.interactionResult &&
            typeof body.interactionResult === "object" &&
            typeof body.interactionResult.selectedOptionId === "string"
          ? body.interactionResult.selectedOptionId
          : "";
        if (turnType === "interaction_result") {
          ensureInteractionContinuationAction(
            actions,
            actionType,
            selectedOptionId,
            continuationContext,
          );
        }
        const photoAssignmentDebug = normalizeActions(
          actions,
          body.date ?? "",
          fallbackOriginalText,
          body.todayRecord,
          {
            inheritedContinuationContext: continuationContext,
            mediaIds: extractMediaIds(body.attachments),
            selectedMealType: actionType === "ask_missing_info_card"
              ? selectedOptionId
              : null,
          },
        );
        validateActions(actions);
        const protocolValidatedAt = performance.now();

        // These timestamps deliberately contain only phase timings.  They never include reply,
        // action, media, or request payload content and make the card-latency handoff measurable.
        const edgeFinalPreparedAt = performance.now();

        const debugTiming = {
          traceId,
          promptVersion,
          totalMs: round(performance.now() - functionStartedAt),
          requestParseMs: round(requestParsedAt - functionStartedAt),
          promptBuildMs: round(promptBuiltAt - promptBuildStartedAt),
          kimiTimeToFirstTokenMs: firstTokenAt == null
            ? null
            : round(firstTokenAt - kimiRequestStartedAt),
          kimiStreamMs: round(kimiStreamEndedAt - kimiRequestStartedAt),
          kimiJsonParseMs: round(kimiJsonParsedAt - kimiJsonParseStartedAt),
          protocolValidationMs: round(
            protocolValidatedAt - protocolValidationStartedAt,
          ),
          promptChars: systemPrompt.length + userContent.length,
          outputJsonChars: fullContent.length,
          compactJsonUsed,
          promptCacheKeyUsed: Boolean(promptCacheKey),
          totalDecodedAttachmentBytes,
          selectedStreamHeaderTimeoutMs,
          upstreamBudgetMs,
          hedgedAttemptCount,
          winnerAttemptIndex: race.winnerAttemptIndex,
          cancelledAttemptCount: race.cancelledAttemptCount,
          provisionalTextReplaced: race.provisionalTextReplaced,
          upstreamHeadersMs: round(upstreamHeadersAt - kimiRequestStartedAt),
          upstreamTotalMs: round(kimiStreamEndedAt - kimiRequestStartedAt),
          lastReplyContentAvailableMs: round(
            kimiStreamEndedAt - functionStartedAt,
          ),
          actionsReadyMs: round(protocolValidatedAt - functionStartedAt),
          edgeFinalEmittedMs: round(edgeFinalPreparedAt - functionStartedAt),
          lastReplyToActionsReadyMs: round(
            protocolValidatedAt - kimiStreamEndedAt,
          ),
          actionsReadyToEdgeFinalMs: round(
            edgeFinalPreparedAt - protocolValidatedAt,
          ),
          timeoutStage: "NONE",
          ...photoAssignmentDebug,
        };

        const normalized = {
          reply,
          actions,
          debugTiming,
        };

        sendEvent("final", normalized);
        sendEvent("debug_timing", debugTiming);
        sendEvent("done", {});
      } catch (error) {
        sendEvent("error", {
          code: error instanceof CandidateRaceError
            ? error.code
            : "PROTOCOL_INVALID_FINAL",
          retryable: error instanceof CandidateRaceError,
          message: error instanceof CandidateRaceError
            ? "AI response is temporarily unavailable. Please retry."
            : "AI response could not be validated.",
        });
      } finally {
        controller.close();
      }
    },
  });

  return new Response(stream, {
    headers: {
      ...corsHeaders,
      "Content-Type": "text/event-stream; charset=utf-8",
      "Cache-Control": "no-cache",
    },
  });
}

function buildSystemPrompt(): string {
  return `你是 DayZero 的 AI 饮食助手。
DayZero 是一个帮助用户轻松记录饮食、理解热量、稳定减脂的应用。你的风格应该像一个温柔、专业、低压力的朋友，而不是冷冰冰的记录机器。

你的回复原则：
- 每次都要自然回应用户，不要只输出工具。
- 语气温柔、简洁、有陪伴感。
- 不制造身材焦虑，不批评用户，不鼓励极端节食。
- 用户表达吃多了、嘴馋、焦虑或自责时，先接住情绪，再给轻量建议。
- 用户只是聊天、咨询、分享生活时，也要正常自然回复。
- 工具调用只是额外能力，不是默认行为。只有当工具能明显帮助当前对话继续时，才调用工具。

输出格式要求：
你必须只返回一个 JSON 对象，不要输出任何 Markdown 标记或 JSON 之外的任何文本。
请使用 compact 格式，且将 r 放在第一位，格式如下：
{"r": "给用户看的自然语言回复", "a": []}
注：亦可使用旧格式 {"reply": "...", "actions": []}。

允许调用的卡片工具（放入 a 数组中）：
1. ask_record_intent_card
   用途：用户提到自己吃了/喝了什么但没有明确说要记录时，询问用户是否要把这次饮食录入今天。
   核心规则：输出时只需包含 "type" (或 "t") 即可，如 {"t": "ask_record_intent_card"}。绝不能输出 payload (或 p) 等其他任何 UI 字段（如 title, message, options, originalText），系统会自动填充。
2. ask_missing_info_card
   用途：用户明确要求记录饮食，但没有指出餐次（早餐/午餐/晚餐/加餐），向用户询问餐次。
   重要：如果用户的原始饮食文本（例如“我吃了一个苹果”或“我今天还吃了两个橘子”）中没有包含明确的餐次词汇（如“早餐/午餐/晚餐/加餐/早上/中午/晚上/下午/上午/夜宵/零食”），你绝对不能擅自假设餐次（哪怕它是水果、零食、饮料也绝对不能默认为“加餐”），必须先调用 ask_missing_info_card。
   核心规则：输出时只需包含 "type" (或 "t") 即可，如 {"t": "ask_missing_info_card"}。绝不能输出 payload (或 p) 等其他任何 UI 字段（如 title, message, options, field, originalText），系统会自动填充。
3. show_confirm_card
   用途：展示用户准备录入的饮食草稿。
   payload 结构：{"confirmType": "food_record", "meals": [{"mealType": "lunch", "items": [{"name": "螺蛳粉", "amountText": "1份", "calories": 600, "carbohydratesG": 85, "proteinG": 15, "fatG": 22, "fiberG": 6}]}]}
   - 热量由你来进行粗略估算，且 calorieConfidence 设为 "estimated"。
   - 每个 item 的 carbohydratesG/proteinG/fatG/fiberG 均表示该 item 当前 amountText 对应份量的估算克数；无法可靠估算时填 null，未知不得用 0 代替；carbohydratesG 为包含 fiberG 的总碳水。
   - 如果用户没有提到体重，weightKg 返回 null。
   - 重要：不要重复生成已经录入在 AlreadyRecorded 中的食物。你的卡片（show_confirm_card）应该只包含当前对话中新提到、待确认录入的食物。
4. debug_show_choice_card
   用途：仅在用户明确表示想测试工具或卡片时使用。

对话流及状态路由规则（重要）：
当你接收到的输入包含 TurnType: interaction_result 时，说明用户刚刚完成了一个工具卡片的操作：
1. 对于 ask_record_intent_card 的点击回应（SelectedOptionId 为用户的选择）：
   - 如果用户选择 "record" (帮我记录)：
     - 如果原始饮食文本或 Recent 聊天历史中已经包含明确餐次（比如提到“早餐”、“中午”、“晚餐”或“晚上”等），请立即返回 reply 和 show_confirm_card 卡片。
     - 如果缺少餐次（比如只说了“吃了一份苹果”），请返回 reply 并调用 ask_missing_info_card 卡片。
     - 严格要求：若原始饮食文本中缺少餐次（即没有任何早餐/午餐/晚餐/加餐/中午/晚上等词汇），绝对不能擅自判定为“加餐”并直接生成 show_confirm_card，必须调用 ask_missing_info_card 卡片！
     - 提示：若 OriginalText 缺失或为空，可查看 Recent 聊天历史获取刚才用户提到的饮食（如“螺蛳粉”）和餐次（如“中午”）。
   - 如果用户选择 "chat_only" (只是聊聊) 或 "not_now" (先不用)：
     - 自然跟用户闲聊或确认，不需要进行任何记录，且 a 设为 []。
2. 对于 ask_missing_info_card 的点击回应：
   - 此时餐次已补齐（对应 SelectedOptionId 比如 breakfast/lunch/dinner/snack）。结合之前的饮食内容，返回 reply 并调用 show_confirm_card 卡片。
3. 对于 show_confirm_card 的点击回应：
   - 用户确认记录（confirm）或取消（cancel）后，请自然友好地给予回应，表示已经确认记录或已取消，且 a 设为 []。

VISION CONTINUATION CONTRACT (this overrides the earlier no-payload rule only for image-origin food cards):
- When the current user message contains image content and you recognized food but must return ask_record_intent_card or ask_missing_info_card, include a compact continuation object as action.c.
- action.c must be JSON only: {"schemaVersion":1,"originalText":"...","mealType":null,"weightKg":null,"recognizedFoods":[{"name":"...","amountText":"...","calories":123,"calorieConfidence":"estimated","carbohydratesG":null,"proteinG":null,"fatG":null,"fiberG":null}]}.
- Include every recognized food and the best available portion, calories, and nutrition estimate. Never include image bytes, Base64, data URLs, remote URLs, or file paths.
- For TurnType interaction_result, ContinuationContext is authoritative food context from the prior card. Never request the image again.
- If CardAction is ask_missing_info_card and Selected is breakfast/lunch/dinner/snack, return show_confirm_card using ContinuationContext and that meal type. Do not repeat the meal question and do not return an empty action list.
- If CardAction is ask_record_intent_card and Selected is record, use ContinuationContext: return show_confirm_card when mealType is known, otherwise ask_missing_info_card while preserving the same continuation object.
- Current image inputs include an ImageAttachmentReferences table in the user message. The table lists stable aliases attachment_1, attachment_2, ... in exactly the same order as the following image_url blocks.
- For image-origin show_confirm_card meals, raw sourceMediaIds should contain only those attachment_N aliases (or the equivalent 1-based index) assigned to the matching meal. The server will convert them to real sourceMediaIds. Never output Base64, URLs, file paths, or invented IDs.
- Each image may belong to at most one meal. Keep sourceMediaIds order stable. Do not duplicate an attachment across meals.
- Multi-image meal text is authoritative when explicit: if the user says image/photo 1 is breakfast, image/photo 2 is lunch, image/photo 3 is dinner, or equivalently mentions 一日三餐/早餐/午餐/晚餐 with clear mapping, return one show_confirm_card with separate meals, assign attachment_1/2/3 to breakfast/lunch/dinner respectively, and do not ask which meal to record.
- When multiple meals are present and you cannot safely map each photo to a meal, omit sourceMediaIds rather than guessing; the client will keep all origin photos available for manual assignment.
${VISION_PROMPT_ADDENDUM}`;
}

function formatTodayRecord(todayRecord: unknown): string {
  if (!todayRecord || typeof todayRecord !== "object") return "None";
  const record = todayRecord as Record<string, unknown>;
  const meals = record.meals;
  if (!Array.isArray(meals) || meals.length === 0) return "None";

  return meals
    .map((meal) => {
      if (!meal || typeof meal !== "object") return "";
      const m = meal as Record<string, unknown>;
      const type = String(m.mealType ?? "");
      const foods = Array.isArray(m.foods)
        ? m.foods
          .map((f) => {
            if (!f || typeof f !== "object") return "";
            const food = f as Record<string, unknown>;
            return `${String(food.name ?? "")}(${
              String(food.quantity ?? "1份")
            }, ${Number(food.estimatedCalories ?? 0)}kcal)`;
          })
          .filter(Boolean)
          .join(", ")
        : "";
      return foods ? `- ${type}: ${foods}` : "";
    })
    .filter(Boolean)
    .join("\n") || "None";
}

function buildUserContent(input: {
  date: string;
  recentContext: string;
  turnType: string;
  userText: string;
  interactionResult: unknown;
  todayRecord: unknown;
}): string {
  let content = `Date:${input.date}
Recent:
${input.recentContext || "None"}
AlreadyRecorded:
${formatTodayRecord(input.todayRecord)}
TurnType:${input.turnType}
`;

  if (
    input.turnType === "interaction_result" && input.interactionResult &&
    typeof input.interactionResult === "object"
  ) {
    const result = input.interactionResult as Record<string, unknown>;
    content += `CardAction:${String(result.actionType ?? "")}
InteractionId:${String(result.interactionId ?? "")}
Selected:${String(result.selectedOptionId ?? "")}/${
      String(result.selectedOptionLabel ?? "")
    }
Field:${String(result.field ?? "")}
OriginalText:${String(result.originalText ?? "")}
ConfirmType:${String(result.confirmType ?? "")}
PayloadSummary:${JSON.stringify(result.payloadSummary ?? null)}
ContinuationContext:${JSON.stringify(result.continuationContext ?? null)}
`;
  } else {
    content += `User:${input.userText}
`;
  }

  return content;
}

function extractMediaIds(attachments: unknown): string[] {
  if (!Array.isArray(attachments)) return [];
  return attachments.flatMap((attachment) => {
    if (!attachment || typeof attachment !== "object") return [];
    const mediaId = (attachment as Record<string, unknown>).mediaId;
    return typeof mediaId === "string" && mediaId.trim().length > 0
      ? [mediaId.trim()]
      : [];
  });
}

function buildRecentContext(messages: unknown): string {
  if (!Array.isArray(messages)) return "";
  return messages
    .slice(-6)
    .map((message) => {
      const item = message && typeof message === "object"
        ? message as Record<string, unknown>
        : {};
      const role = typeof item.role === "string" ? item.role : "Unknown";
      const text = typeof item.text === "string"
        ? item.text.trim().slice(0, 160)
        : "";
      return text ? `${role}:${text}` : "";
    })
    .filter(Boolean)
    .join("\n");
}

function normalizePromptCacheKey(value: unknown): string | undefined {
  if (typeof value !== "string") return undefined;
  const normalized = value.trim().replace(/[^a-zA-Z0-9_.:-]/g, "_").slice(
    0,
    120,
  );
  return normalized.length > 0 ? normalized : undefined;
}

function extractStringFieldPrefix(
  json: string,
  keys: string[],
): { value: string; complete: boolean } {
  for (const key of keys) {
    const keyIndex = json.indexOf(`"${key}"`);
    if (keyIndex < 0) continue;
    const colonIndex = json.indexOf(":", keyIndex + key.length + 2);
    if (colonIndex < 0) continue;
    let valueStart = colonIndex + 1;
    while (valueStart < json.length && /\s/.test(json[valueStart])) {
      valueStart++;
    }
    if (json[valueStart] !== '"') continue;
    return readJsonStringPrefix(json, valueStart + 1);
  }
  return { value: "", complete: false };
}

function readJsonStringPrefix(
  source: string,
  start: number,
): { value: string; complete: boolean } {
  let value = "";
  for (let i = start; i < source.length; i++) {
    const char = source[i];
    if (char === '"') return { value, complete: true };
    if (char !== "\\") {
      value += char;
      continue;
    }

    const escaped = source[++i];
    if (escaped == null) break;
    if (escaped === '"' || escaped === "\\" || escaped === "/") {
      value += escaped;
    } else if (escaped === "b") value += "\b";
    else if (escaped === "f") value += "\f";
    else if (escaped === "n") value += "\n";
    else if (escaped === "r") value += "\r";
    else if (escaped === "t") value += "\t";
    else if (escaped === "u") {
      const hex = source.slice(i + 1, i + 5);
      if (!/^[0-9a-fA-F]{4}$/.test(hex)) break;
      value += String.fromCharCode(parseInt(hex, 16));
      i += 4;
    }
  }
  return { value, complete: false };
}

function validateActions(actions: unknown[]) {
  const allowed = new Set([
    "debug_show_choice_card",
    "ask_record_intent_card",
    "ask_missing_info_card",
    "show_confirm_card",
  ]);

  for (const action of actions) {
    if (!action || typeof action !== "object") {
      throw new Error("Invalid action");
    }
    const type = (action as Record<string, unknown>).type;
    if (typeof type !== "string" || !allowed.has(type)) {
      throw new Error(`Unsupported action type: ${String(type)}`);
    }
  }
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      ...corsHeaders,
      "Content-Type": "application/json",
    },
  });
}

type CandidateRace = {
  content: string;
  winnerAttemptIndex: number;
  winnerCompletedAt: number;
  firstHeadersAt: number;
  firstTokenAt: number | null;
  cancelledAttemptCount: number;
  provisionalTextReplaced: boolean;
};

type CandidateResult = {
  index: number;
  content: string;
  completedAt: number;
  headersAt: number;
  firstTokenAt: number | null;
};

class CandidateRaceError extends Error {
  constructor(readonly code: "UPSTREAM_ALL_ATTEMPTS_FAILED" | "UPSTREAM_BUDGET_EXHAUSTED") {
    super(code);
  }
}

/**
 * A vision turn has one client-to-Edge request but up to three independent Kimi
 * candidates. Candidate zero is the only visible stream. A candidate cannot win
 * until it contains a minimally valid final JSON response; all losers are aborted.
 */
async function raceMoonshotCandidates(input: {
  apiKey: string;
  systemPrompt: string;
  userMessage: unknown;
  promptCacheKey?: string;
  attemptCount: number;
  budgetMs: number;
  onPrimaryDelta: (text: string) => void;
}): Promise<CandidateRace> {
  const controllers = Array.from(
    { length: input.attemptCount },
    () => new AbortController(),
  );
  const startedAt = performance.now();
  let budgetExhausted = false;
  const budget = setTimeout(() => {
    budgetExhausted = true;
    controllers.forEach((controller) => controller.abort());
  }, input.budgetMs);
  let primaryEmittedText = false;
  try {
    const pending = new Map<number, Promise<{ index: number; result?: CandidateResult; error?: unknown }>>();
    for (let index = 0; index < input.attemptCount; index++) {
      const promise = (index === 0
        ? readStreamingCandidate(index, controllers[index], input, () => {
          primaryEmittedText = true;
        })
        : readJsonCandidate(index, controllers[index], input)
      ).then((result) => ({ index, result }), (error) => ({ index, error }));
      pending.set(index, promise);
    }
    while (pending.size > 0) {
      const settled = await Promise.race([...pending.values()]);
      pending.delete(settled.index);
      if (settled.result && isValidCandidateContent(settled.result.content)) {
        let cancelledAttemptCount = 0;
        controllers.forEach((controller, index) => {
          if (index !== settled.index && !controller.signal.aborted) {
            cancelledAttemptCount++;
            controller.abort();
          }
        });
        return {
          content: settled.result.content,
          winnerAttemptIndex: settled.index,
          winnerCompletedAt: settled.result.completedAt,
          firstHeadersAt: settled.result.headersAt,
          firstTokenAt: settled.result.firstTokenAt,
          cancelledAttemptCount,
          provisionalTextReplaced: primaryEmittedText && settled.index !== 0,
        };
      }
    }
    throw new CandidateRaceError(
      budgetExhausted ? "UPSTREAM_BUDGET_EXHAUSTED" : "UPSTREAM_ALL_ATTEMPTS_FAILED",
    );
  } finally {
    clearTimeout(budget);
  }
}

function candidateRequest(input: {
  apiKey: string;
  systemPrompt: string;
  userMessage: unknown;
  promptCacheKey?: string;
  stream: boolean;
  signal: AbortSignal;
}): Promise<Response> {
  return fetch(MOONSHOT_API_URL, {
    method: "POST",
    signal: input.signal,
    headers: {
      "Content-Type": "application/json",
      "Authorization": `Bearer ${input.apiKey}`,
    },
    body: JSON.stringify({
      model: "kimi-k2.6",
      messages: [{ role: "system", content: input.systemPrompt }, input.userMessage],
      response_format: { type: "json_object" },
      stream: input.stream,
      prompt_cache_key: input.promptCacheKey,
      max_tokens: 1500,
      temperature: 0.6,
      thinking: { type: "disabled" },
    }),
  });
}

async function readStreamingCandidate(
  index: number,
  controller: AbortController,
  input: Parameters<typeof raceMoonshotCandidates>[0],
  markPrimaryText: () => void,
): Promise<CandidateResult> {
  const response = await candidateRequest({ ...input, stream: true, signal: controller.signal });
  const headersAt = performance.now();
  if (!response.ok || !response.body) throw new Error(`Kimi HTTP ${response.status}`);
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let content = "";
  let emittedLength = 0;
  let firstTokenAt: number | null = null;
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split(/\r?\n/);
    buffer = lines.pop() ?? "";
    for (const line of lines) {
      if (!line.startsWith("data:")) continue;
      const data = line.slice(5).trim();
      if (!data || data === "[DONE]") continue;
      const delta = JSON.parse(data)?.choices?.[0]?.delta?.content;
      if (typeof delta !== "string" || delta.length === 0) continue;
      if (firstTokenAt == null) firstTokenAt = performance.now();
      content += delta;
      const prefix = extractStringFieldPrefix(content, ["r", "reply"]);
      if (prefix.value.length > emittedLength) {
        input.onPrimaryDelta(prefix.value.slice(emittedLength));
        markPrimaryText();
        emittedLength = prefix.value.length;
      }
    }
  }
  return { index, content, headersAt, firstTokenAt, completedAt: performance.now() };
}

async function readJsonCandidate(
  index: number,
  controller: AbortController,
  input: Parameters<typeof raceMoonshotCandidates>[0],
): Promise<CandidateResult> {
  const response = await candidateRequest({ ...input, stream: false, signal: controller.signal });
  const headersAt = performance.now();
  if (!response.ok) throw new Error(`Kimi HTTP ${response.status}`);
  const json = await response.json();
  const content = json?.choices?.[0]?.message?.content;
  if (typeof content !== "string") throw new Error("Kimi returned empty content");
  return { index, content, headersAt, firstTokenAt: null, completedAt: performance.now() };
}

export function isValidCandidateContent(content: string): boolean {
  try {
    const parsed = JSON.parse(content);
    const reply = typeof parsed?.r === "string" ? parsed.r : parsed?.reply;
    const actions = Array.isArray(parsed?.a) ? parsed.a : parsed?.actions ?? [];
    if (typeof reply !== "string" || reply.trim().length === 0 || !Array.isArray(actions)) return false;
    // Raw compact actions use t/p and are normalized only after a candidate wins.
    // Reject structurally unsafe actions here without applying the final validator
    // before normalization (which would incorrectly discard valid Card candidates).
    return actions.every((action: unknown) => {
      if (!action || typeof action !== "object" || Array.isArray(action)) return false;
      const raw = action as Record<string, unknown>;
      const type = raw.type ?? raw.t;
      return typeof type === "string" && type.trim().length > 0;
    });
  } catch {
    return false;
  }
}

function hasContinuationMediaIds(interactionResult: unknown): boolean {
  if (!interactionResult || typeof interactionResult !== "object") return false;
  const context = (interactionResult as Record<string, unknown>).continuationContext;
  if (!context || typeof context !== "object") return false;
  const mediaIds = (context as Record<string, unknown>).mediaIds;
  return Array.isArray(mediaIds) && mediaIds.length > 0;
}

function round(value: number): number {
  return Math.round(value * 100) / 100;
}
