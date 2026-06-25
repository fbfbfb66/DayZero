import "jsr:@supabase/functions-js@2/edge-runtime.d.ts";
import { normalizeActions } from "./normalization.ts";

const MOONSHOT_API_URL = "https://api.moonshot.cn/v1/chat/completions";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

Deno.serve(async (req) => {
  const functionStartedAt = performance.now();

  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  if (req.method !== "POST") {
    return jsonResponse({ error: "Method Not Allowed" }, 405);
  }

  try {
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
      return jsonResponse({ error: "Missing userText" }, 400);
    }

    console.log(
      `[AssistantTurnV2] start traceId=${
        traceId ?? "none"
      } userTextLength=${userText.length}`,
    );

    const promptBuildStartedAt = performance.now();
    const promptVersion = "compact_v3_timing";
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
    const promptBuiltAt = performance.now();

    const kimiRequestStartedAt = performance.now();
    const response = await fetch(MOONSHOT_API_URL, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${moonshotApiKey}`,
      },
      body: JSON.stringify({
        model: "kimi-k2.6",
        messages: [
          { role: "system", content: systemPrompt },
          { role: "user", content: userContent },
        ],
        response_format: { type: "json_object" },
        prompt_cache_key: promptCacheKey,
        max_tokens: 1500,
        temperature: 0.6,
        thinking: { type: "disabled" },
      }),
    });
    const kimiResponseReceivedAt = performance.now();

    if (!response.ok) {
      const errorData = await response.json().catch(() => ({
        message: "Unknown error",
      }));
      return jsonResponse(
        {
          error: "Kimi API Request Failed",
          status: response.status,
          detail: errorData.error?.message || errorData.message,
        },
        502,
      );
    }

    const kimiJsonParseStartedAt = performance.now();
    const kimiResult = await response.json();
    const kimiJsonParsedAt = performance.now();
    const content = kimiResult?.choices?.[0]?.message?.content;

    console.log(`[AssistantTurnV2] Kimi raw response: ${content}`);

    if (typeof content !== "string" || content.trim().length === 0) {
      return jsonResponse({ error: "Kimi returned empty content" }, 502);
    }

    let parsed: Record<string, unknown>;
    try {
      parsed = JSON.parse(content) as Record<string, unknown>;
    } catch (_error) {
      return jsonResponse({ error: "Failed to parse Kimi JSON" }, 502);
    }

    const protocolValidationStartedAt = performance.now();
    const compactJsonUsed = typeof parsed.r === "string" ||
      Array.isArray(parsed.a);
    const rawReply = typeof parsed.r === "string" ? parsed.r : parsed.reply;
    const reply = typeof rawReply === "string" ? rawReply.trim() : "";
    const actions = Array.isArray(parsed.a)
      ? parsed.a
      : Array.isArray(parsed.actions)
      ? parsed.actions
      : [];

    if (!reply) {
      return jsonResponse({ error: "Kimi returned blank reply" }, 502);
    }

    let fallbackOriginalText = userText;
    if (
      body.interactionResult && typeof body.interactionResult === "object" &&
      typeof body.interactionResult.originalText === "string" &&
      body.interactionResult.originalText.trim().length > 0
    ) {
      fallbackOriginalText = body.interactionResult.originalText.trim();
    }
    normalizeActions(
      actions,
      body.date ?? "",
      fallbackOriginalText,
      body.todayRecord,
    );
    validateActions(actions);
    const protocolValidatedAt = performance.now();

    console.log(
      `[AssistantTurnV2] success replyLength=${reply.length} actionsCount=${actions.length}`,
    );
    return jsonResponse({
      reply,
      actions,
      debugTiming: {
        traceId,
        promptVersion,
        totalMs: round(performance.now() - functionStartedAt),
        requestParseMs: round(requestParsedAt - functionStartedAt),
        promptBuildMs: round(promptBuiltAt - promptBuildStartedAt),
        kimiRequestMs: round(kimiResponseReceivedAt - kimiRequestStartedAt),
        kimiJsonParseMs: round(kimiJsonParsedAt - kimiJsonParseStartedAt),
        protocolValidationMs: round(
          protocolValidatedAt - protocolValidationStartedAt,
        ),
        promptChars: systemPrompt.length + userContent.length,
        outputJsonChars: content.length,
        compactJsonUsed,
        promptCacheKeyUsed: Boolean(promptCacheKey),
      },
    });
  } catch (error) {
    const detail = error instanceof Error ? error.message : String(error);
    console.error("[AssistantTurnV2] internal error", detail);
    return jsonResponse({ error: "Internal Server Error", detail }, 500);
  }
});

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
你必须只返回一个 JSON 对象，不要输出 any Markdown 标记或 JSON 之外的任何文本。
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
   - 用户确认记录（confirm）或取消（cancel）后，请自然友好地给予回应，表示已经确认记录或已取消，且 a 设为 []。`;
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
`;
  } else {
    content += `User:${input.userText}
`;
  }

  return content;
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

// Helper functions moved to normalization.ts

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

function round(value: number): number {
  return Math.round(value * 100) / 100;
}
