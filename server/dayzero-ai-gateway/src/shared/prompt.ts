import { VISION_PROMPT_ADDENDUM } from "./assistant_vision.ts";

export const FALLBACK_PROMPT_VERSION = "compact_v8_deterministic_multi_meal_photo_assignment";
export const STREAMING_PROMPT_VERSION =
  "stream_compact_v7_deterministic_multi_meal_photo_assignment";

export function buildSystemPrompt(): string {
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

export function formatTodayRecord(todayRecord: unknown): string {
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
            return `${String(food.name ?? "")}(${String(food.quantity ?? "1份")}, ${
              Number(food.estimatedCalories ?? 0)
            }kcal)`;
          })
          .filter(Boolean)
          .join(", ")
        : "";
      return foods ? `- ${type}: ${foods}` : "";
    })
    .filter(Boolean)
    .join("\n") || "None";
}

export function buildPromptInputs(request: {
  date: string;
  recentMessages: unknown;
  turnType: string;
  userText: string;
  interactionResult: unknown;
  todayRecord: unknown;
}) {
  const recentContext = buildRecentContext(request.recentMessages);
  return {
    date: request.date,
    recentContext,
    turnType: request.turnType,
    userText: request.userText,
    interactionResult: request.interactionResult,
    todayRecord: request.todayRecord,
  };
}

export function buildUserContent(input: {
  date: string;
  recentContext: string;
  turnType: string;
  userText: string;
  interactionResult: unknown;
  todayRecord: unknown;
}): string {
  let content = `Date:${input.date}\nRecent:\n${input.recentContext || "None"}\nAlreadyRecorded:\n${
    formatTodayRecord(input.todayRecord)
  }\nTurnType:${input.turnType}\n`;

  if (
    input.turnType === "interaction_result" && input.interactionResult &&
    typeof input.interactionResult === "object"
  ) {
    const result = input.interactionResult as Record<string, unknown>;
    content += `CardAction:${String(result.actionType ?? "")}\nInteractionId:${
      String(result.interactionId ?? "")
    }\nSelected:${String(result.selectedOptionId ?? "")}/${
      String(result.selectedOptionLabel ?? "")
    }\nField:${String(result.field ?? "")}\nOriginalText:${
      String(result.originalText ?? "")
    }\nConfirmType:${String(result.confirmType ?? "")}\nPayloadSummary:${
      JSON.stringify(result.payloadSummary ?? null)
    }\nContinuationContext:${JSON.stringify(result.continuationContext ?? null)}\n`;
  } else {
    content += `User:${input.userText}\n`;
  }

  return content;
}

export function buildRecentContext(messages: unknown): string {
  if (!Array.isArray(messages)) return "";
  return messages
    .slice(-6)
    .map((message) => {
      const item = message && typeof message === "object" ? message as Record<string, unknown> : {};
      const role = typeof item.role === "string" ? item.role : "Unknown";
      const text = typeof item.text === "string" ? item.text.trim().slice(0, 160) : "";
      return text ? `${role}:${text}` : "";
    })
    .filter(Boolean)
    .join("\n");
}

export function normalizePromptCacheKey(value: unknown): string | undefined {
  if (typeof value !== "string") return undefined;
  const normalized = value.trim().replace(/[^a-zA-Z0-9_.:-]/g, "_").slice(
    0,
    120,
  );
  return normalized.length > 0 ? normalized : undefined;
}

export function extractMediaIds(attachments: unknown): string[] {
  if (!Array.isArray(attachments)) return [];
  return attachments.flatMap((attachment) => {
    if (!attachment || typeof attachment !== "object") return [];
    const mediaId = (attachment as Record<string, unknown>).mediaId;
    return typeof mediaId === "string" && mediaId.trim().length > 0 ? [mediaId.trim()] : [];
  });
}
