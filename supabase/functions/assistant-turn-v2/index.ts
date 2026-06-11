import "jsr:@supabase/functions-js/edge-runtime.d.ts";

const MOONSHOT_API_URL = "https://api.moonshot.cn/v1/chat/completions";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
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
      return jsonResponse({ error: "Internal Server Error (Missing MOONSHOT_API_KEY)" }, 500);
    }

    const body = await req.json();
    const requestParsedAt = performance.now();
    const traceId = typeof body.traceId === "string" ? body.traceId : null;
    const userText = typeof body.userText === "string" ? body.userText.trim() : "";
    if (!userText) {
      return jsonResponse({ error: "Missing userText" }, 400);
    }

    const recentContext = Array.isArray(body.recentMessages)
      ? body.recentMessages
          .slice(-10)
          .map((message: { role?: unknown; text?: unknown }) => {
            const role = typeof message.role === "string" ? message.role : "Unknown";
            const text = typeof message.text === "string" ? message.text : "";
            return `${role}: ${text}`;
          })
          .filter((line: string) => line.trim().length > 0)
          .join("\n")
      : "";

    console.log(
      `[AssistantTurnV2] start traceId=${traceId ?? "none"} userTextLength=${userText.length} recentMessages=${recentContext ? "yes" : "no"}`,
    );

    const promptBuildStartedAt = performance.now();
    const promptVersion = "draft_card_v1_timing";

    const systemPrompt = `你是 DayZero 的 AI 饮食助手。

DayZero 是一个帮助用户轻松记录饮食、理解热量、稳定减脂的应用。你的风格应该像一个温柔、专业、低压力的朋友，而不是冷冰冰的记录机器。

你的回复原则：
- 每次都要自然回应用户，不要只输出工具。
- 语气温柔、简洁、有陪伴感。
- 不制造身材焦虑，不批评用户，不鼓励极端节食。
- 用户表达吃多了、嘴馋、焦虑或自责时，先接住情绪，再给轻量建议。
- 用户只是聊天、咨询、分享生活时，也要正常自然回复。
- 工具调用只是额外能力，不是默认行为。
- 只有当工具能明显帮助当前对话继续时，才调用工具。

你不需要输出旧式意图分类字段，例如 primaryIntent、speechAct、consumptionStatus。
你只需要做两件事：
1. 生成给用户看的 reply。
2. 判断是否需要调用当前可用工具。

当前可用工具：

1. debug_show_choice_card
用途：用于测试 DayZero 的工具调用链路。
适合调用的情况：用户明确表示想测试工具、明确要求打开测试卡片等。
不适合调用的情况：任何非测试场景。

2. ask_record_intent_card
用途：
当用户提到自己已经吃了或喝了某些东西，但没有明确要求记录时，询问用户是否要把这次饮食录入今天。

适合调用的情况：
- 用户像是在分享自己已经吃了/喝了什么。
- 用户没有明确要求记录、打卡、录入。
- 例子：
  - “今天吃了火锅”
  - “中午吃了一碗肠粉”
  - “刚喝了杯奶茶”
  - “晚上吃了炸鸡”

不适合调用的情况：
- 用户只是问能不能吃、热量高不高。
- 用户只是说想吃但还没吃。
- 用户明确要求记录。
- 用户说体重。
- 用户要求总结。
- 用户普通闲聊或表达情绪但没有具体已吃内容。

3. ask_missing_info_card
用途：
当用户已经表示要记录饮食，但缺少餐次时，询问这次饮食算在哪一餐。

适合调用的情况：
- 用户点击 ask_record_intent_card 的“帮我记录”。
- 原始饮食文本里没有明确餐次。
- 用户明确说“帮我记录刚吃的苹果”，但没说早餐/午餐/晚餐/加餐。

不适合调用的情况：
- 用户只是分享饮食，还没表示要记录，这时用 ask_record_intent_card。
- 用户只是问热量或能不能吃。
- 用户只是想吃但还没吃。
- 用户已经给出明确餐次，例如“中午吃了肠粉”。
- 用户说体重、总结、闲聊。

4. show_confirm_card
用途：
展示需要用户确认的饮食记录草稿。用户确认前不能写库。

适合调用的情况：
- 用户明确要求记录饮食，并且文本里已有餐次和食物。
- 用户从 ask_record_intent_card 选择“帮我记录”，且原始文本已有餐次和食物。
- 用户从 ask_missing_info_card 选择餐次后，现在餐次和食物都已齐全。

不适合调用的情况：
- 用户只是分享饮食但没有说要记录，这时用 ask_record_intent_card。
- 用户已经要记录但缺少餐次，这时用 ask_missing_info_card。
- 用户只是问热量、能不能吃、想吃但没吃。
- 食物内容过于模糊到无法形成草稿。

使用 show_confirm_card 时的草稿构建要求：
- food_record 草稿应尽量按餐次组织为 meals[]。
- 如果用户一次说了多餐，例如“早餐吃了...，中午吃了...”，必须拆成多个 meal。
- 如果用户没有提到体重，weightKg 返回 null。
- 如果用户提到体重，可以填入 weightKg。
- calories 都是估算值时，calorieConfidence 使用 "estimated"。
- 热量可以先由你做粗略预估，但必须标记 calorieConfidence = "estimated"。

当你收到 TurnType = interaction_result 时，说明用户刚刚完成了一个工具卡片操作。
你需要自然承接用户刚才的选择，生成一条 reply。
如果这次不需要继续调用工具，actions 返回 []。

对于 ask_record_intent_card 的 interaction_result：
- 如果用户点击了“帮我记录”：
  - 且原始文本已经包含明确餐次（例如“中午吃了一碗肠粉”），则返回 reply + show_confirm_card。
  - 且原始文本缺少餐次，则返回 reply + ask_missing_info_card。
- 如果用户点击了“只是聊聊”或“先不用”，你只需自然回复，不用进行任何记录，actions=[]。

对于 ask_missing_info_card 的 interaction_result：
- 结合用户选择的餐次和之前缺失的信息，现在信息齐全了。
- 自然承接用户选择，返回 reply + show_confirm_card。

对于 show_confirm_card 的 interaction_result：
- 如果用户点击了 confirm：自然回复，表示已经收到确认。actions=[]。
- 如果用户点击了 cancel：自然回复，表示不会记录这次。actions=[]。

输出格式要求：
你必须只返回一个 JSON 对象，不要输出 JSON 之外的任何内容。

格式必须是：

{
  "reply": "给用户看的自然语言回复",
  "actions": []
}

如果需要调用 ask_record_intent_card 工具，格式是：

{
  "reply": "给用户看的自然语言回复",
  "actions": [
    {
      "type": "ask_record_intent_card",
      "interactionId": "record_intent_xxx",
      "payload": {
        "title": "要记录这次饮食吗？",
        "message": "我看到你提到了刚吃/喝的内容，要不要把它录入今天？",
        "originalText": "用户原始输入",
        "options": [
          { "id": "record", "label": "帮我记录" },
          { "id": "chat_only", "label": "只是聊聊" },
          { "id": "not_now", "label": "先不用" }
        ]
      }
    }
  ]
}

如果需要调用 ask_missing_info_card 工具，格式是：

{
  "reply": "给用户看的自然语言回复",
  "actions": [
    {
      "type": "ask_missing_info_card",
      "interactionId": "missing_info_xxx",
      "payload": {
        "title": "补充一下餐次",
        "message": "这次饮食算在哪一餐呀？",
        "field": "mealType",
        "originalText": "用户原始输入",
        "options": [
          { "id": "breakfast", "label": "早餐" },
          { "id": "lunch", "label": "午餐" },
          { "id": "dinner", "label": "晚餐" },
          { "id": "snack", "label": "加餐" }
        ]
      }
    }
  ]
}

如果需要调用 show_confirm_card 工具，格式是：

{
  "reply": "给用户看的自然语言回复",
  "actions": [
    {
      "type": "show_confirm_card",
      "id": "confirm_xxx",
      "payload": {
        "confirmType": "food_record",
        "title": "今日记录草稿",
        "message": "我先帮你估算了一版，你可以修改后再确认。",
        "date": "2026-06-11",
        "weightKg": null,
        "totalCalories": 600,
        "meals": [
          {
            "mealType": "dinner",
            "mealLabel": "晚餐",
            "subtotalCalories": 600,
            "items": [
              {
                "id": "item_xxx",
                "name": "螺蛳粉",
                "amountText": "1份",
                "calories": 600,
                "calorieConfidence": "estimated"
              }
            ]
          }
        ],
        "buttons": [
          { "id": "confirm", "label": "确认记录" },
          { "id": "cancel", "label": "先不记录" }
        ]
      }
    }
  ]
}

硬性要求：
- reply 必须存在，且不能为空。
- actions 必须存在，且必须是数组。
- 当前只允许 debug_show_choice_card、ask_record_intent_card、ask_missing_info_card 和 show_confirm_card。
- 如果不需要工具，actions 必须是 []。
- 不要输出 markdown。
- 不要输出解释。
- 不要输出 JSON 之外的文字。`;

    const turnType = typeof body.turnType === "string" ? body.turnType.trim() : "user_message";
    const interactionResult = body.interactionResult;

    let userContent = `Current date: ${body.date ?? ""}

Recent chat:
${recentContext || "None"}

Latest Turn Details:
TurnType: ${turnType}
`;

    if (turnType === "interaction_result" && interactionResult) {
      userContent += `
User completed card action.
ActionType: ${interactionResult.actionType}
InteractionId: ${interactionResult.interactionId}
SelectedOptionId: ${interactionResult.selectedOptionId}
SelectedOptionLabel: ${interactionResult.selectedOptionLabel}
`;
      if (interactionResult.payloadSummary) {
        userContent += `PayloadSummary: ${JSON.stringify(interactionResult.payloadSummary)}
`;
      }
    } else {
      userContent += `
Latest user input:
${userText}
`;
    }
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
        max_tokens: 1500,
        temperature: 0.6,
        thinking: {
          type: "disabled",
        },
      }),
    });
    const kimiResponseReceivedAt = performance.now();

    if (!response.ok) {
      const errorData = await response.json().catch(() => ({ message: "Unknown error" }));
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
    
    console.log(`[AssistantTurnV2] AssistantPromptVersion = ${promptVersion}`);
    console.log(`[AssistantTurnV2] Kimi raw response: ${content}`);

    if (typeof content !== "string" || content.trim().length === 0) {
      return jsonResponse({ error: "Kimi returned empty content" }, 502);
    }

    let parsed: { reply?: unknown; actions?: unknown };
    try {
      parsed = JSON.parse(content);
    } catch (_error) {
      return jsonResponse({ error: "Failed to parse Kimi JSON" }, 502);
    }

    const protocolValidationStartedAt = performance.now();
    const reply = typeof parsed.reply === "string" ? parsed.reply.trim() : "";
    
    console.log(`[AssistantTurnV2] parsed reply: ${reply}`);

    if (!reply) {
      return jsonResponse({ error: "Kimi returned blank reply" }, 502);
    }

    const actions = Array.isArray(parsed.actions) ? parsed.actions : [];
    
    console.log(`[AssistantTurnV2] actions count: ${actions.length}`);

    for (const action of actions) {
      if (action && typeof action === "object" && "type" in action) {
        console.log(`[AssistantTurnV2] action type: ${action.type}`);
        if (action.type !== "debug_show_choice_card" && action.type !== "ask_record_intent_card" && action.type !== "ask_missing_info_card" && action.type !== "show_confirm_card") {
          console.error(`[AssistantTurnV2] Protocol error: Unknown action type ${action.type}`);
          return jsonResponse({ error: "Protocol Error: Unknown action type", detail: `Unsupported action type: ${action.type}` }, 400);
        }

        if (action.type === "ask_record_intent_card") {
          const typedAction = action as any;
          const payload = typedAction.payload;
          
          if (!typedAction.id && !typedAction.interactionId) {
            return jsonResponse({ error: "Protocol Error: Missing id", detail: "ask_record_intent_card missing id" }, 400);
          }
          if (!payload) {
            return jsonResponse({ error: "Protocol Error: Missing payload", detail: "ask_record_intent_card missing payload" }, 400);
          }
          if (!payload.title || !payload.message || !payload.originalText || !payload.options || !Array.isArray(payload.options)) {
            return jsonResponse({ error: "Protocol Error: Invalid payload", detail: "ask_record_intent_card payload missing required fields" }, 400);
          }
          
          const optionIds = payload.options.map((o: any) => o.id);
          if (!optionIds.includes("record") || !optionIds.includes("chat_only") || !optionIds.includes("not_now")) {
            return jsonResponse({ error: "Protocol Error: Invalid options", detail: "ask_record_intent_card options must contain record, chat_only, not_now" }, 400);
          }
        }

        if (action.type === "ask_missing_info_card") {
          const typedAction = action as any;
          const payload = typedAction.payload;
          
          if (!typedAction.id && !typedAction.interactionId) {
            return jsonResponse({ error: "Protocol Error: Missing id", detail: "ask_missing_info_card missing id" }, 400);
          }
          if (!payload) {
            return jsonResponse({ error: "Protocol Error: Missing payload", detail: "ask_missing_info_card missing payload" }, 400);
          }
          if (!payload.title || !payload.message || !payload.field || !payload.originalText || !payload.options || !Array.isArray(payload.options)) {
            return jsonResponse({ error: "Protocol Error: Invalid payload", detail: "ask_missing_info_card payload missing required fields" }, 400);
          }
          
          if (payload.field === "mealType") {
            const optionIds = payload.options.map((o: any) => o.id);
            if (!optionIds.includes("breakfast") || !optionIds.includes("lunch") || !optionIds.includes("dinner") || !optionIds.includes("snack")) {
              return jsonResponse({ error: "Protocol Error: Invalid options", detail: "ask_missing_info_card mealType options missing required keys" }, 400);
            }
          }
        }

        if (action.type === "show_confirm_card") {
          const typedAction = action as any;
          const payload = typedAction.payload;

          if (!typedAction.id && !typedAction.interactionId) {
            return jsonResponse({ error: "Protocol Error: Missing id", detail: "show_confirm_card missing id" }, 400);
          }
          if (!payload) {
            return jsonResponse({ error: "Protocol Error: Missing payload", detail: "show_confirm_card missing payload" }, 400);
          }
          if (payload.confirmType !== "food_record") {
            return jsonResponse({ error: "Protocol Error: Invalid confirmType", detail: "show_confirm_card confirmType must be food_record" }, 400);
          }
          if (!payload.title || !payload.message || !payload.buttons || !Array.isArray(payload.buttons)) {
            return jsonResponse({ error: "Protocol Error: Invalid payload", detail: "show_confirm_card payload missing required fields" }, 400);
          }

          const hasMeals = Array.isArray(payload.meals);
          const hasLegacyItems = Array.isArray(payload.items) && !!payload.mealType;
          if (!hasMeals && !hasLegacyItems) {
            return jsonResponse({ error: "Protocol Error: Invalid payload", detail: "show_confirm_card payload must contain either meals array or legacy mealType+items" }, 400);
          }
        }
      }
    }

    const protocolValidatedAt = performance.now();

    console.log(`[AssistantTurnV2] success replyLength=${reply.length} actionsCount=${actions.length}`);
    return jsonResponse({
      reply,
      actions,
      debugTiming: {
        traceId,
        totalMs: roundMs(performance.now() - functionStartedAt),
        requestParseMs: roundMs(requestParsedAt - functionStartedAt),
        promptBuildMs: roundMs(promptBuiltAt - promptBuildStartedAt),
        kimiRequestMs: roundMs(kimiResponseReceivedAt - kimiRequestStartedAt),
        kimiJsonParseMs: roundMs(kimiJsonParsedAt - kimiJsonParseStartedAt),
        protocolValidationMs: roundMs(protocolValidatedAt - protocolValidationStartedAt),
      },
    });
  } catch (error) {
    const detail = error instanceof Error ? error.message : String(error);
    console.error("[AssistantTurnV2] internal error", detail);
    return jsonResponse({ error: "Internal Server Error", detail }, 500);
  }
});

function roundMs(value: number): number {
  return Math.round(value * 100) / 100;
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}
