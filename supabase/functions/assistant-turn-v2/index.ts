import "jsr:@supabase/functions-js/edge-runtime.d.ts";

const MOONSHOT_API_URL = "https://api.moonshot.cn/v1/chat/completions";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

Deno.serve(async (req) => {
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
      `[AssistantTurnV2] start userTextLength=${userText.length} recentMessages=${recentContext ? "yes" : "no"}`,
    );

    const promptVersion = "record_intent_v1";

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

当你收到 TurnType = interaction_result 时，说明用户刚刚完成了一个工具卡片操作。
你需要自然承接用户刚才的选择，生成一条 reply。
如果这次不需要继续调用工具，actions 返回 []。

对于 ask_record_intent_card 的 interaction_result：
- 如果用户点击了“帮我记录”，本阶段不需要生成 DraftCard，你只需回复“好，我知道你想记录，下一步会继续帮你整理”或类似自然承接的话。
- 如果用户点击了“只是聊聊”或“先不用”，你只需自然回复，不用进行任何记录。

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

硬性要求：
- reply 必须存在，且不能为空。
- actions 必须存在，且必须是数组。
- 当前只允许 debug_show_choice_card 和 ask_record_intent_card。
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
    } else {
      userContent += `
Latest user input:
${userText}
`;
    }

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
        max_tokens: 500,
        temperature: 0.6,
        thinking: {
          type: "disabled",
        },
      }),
    });

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

    const kimiResult = await response.json();
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
        if (action.type !== "debug_show_choice_card" && action.type !== "ask_record_intent_card") {
          console.error(`[AssistantTurnV2] Protocol error: Unknown action type ${action.type}`);
          return jsonResponse({ error: "Protocol Error: Unknown action type", detail: `Unsupported action type: ${action.type}` }, 400);
        }

        if (action.type === "ask_record_intent_card") {
          const typedAction = action as any;
          const payload = typedAction.payload;
          
          if (!typedAction.interactionId) {
            return jsonResponse({ error: "Protocol Error: Missing interactionId", detail: "ask_record_intent_card missing interactionId" }, 400);
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
      }
    }

    console.log(`[AssistantTurnV2] success replyLength=${reply.length} actionsCount=${actions.length}`);
    return jsonResponse({ reply, actions });
  } catch (error) {
    const detail = error instanceof Error ? error.message : String(error);
    console.error("[AssistantTurnV2] internal error", detail);
    return jsonResponse({ error: "Internal Server Error", detail }, 500);
  }
});

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}
