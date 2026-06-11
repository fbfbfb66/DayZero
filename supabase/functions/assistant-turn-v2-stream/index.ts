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

  const stream = new ReadableStream({
    async start(controller) {
      const encoder = new TextEncoder();
      const sendEvent = (event: string, data: unknown) => {
        controller.enqueue(encoder.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`));
      };

      try {
        sendEvent("status", { state: "started" });
        const promptBuildStartedAt = performance.now();
        const promptVersion = "stream_compact_v1";
        const promptCacheKey = normalizePromptCacheKey(body.promptCacheKey);
        const recentContext = buildRecentContext(body.recentMessages);
        const turnType = typeof body.turnType === "string" ? body.turnType.trim() : "user_message";
        const interactionResult = body.interactionResult;

        const systemPrompt = buildSystemPrompt();
        const userContent = buildUserContent({
          date: body.date ?? "",
          recentContext,
          turnType,
          userText,
          interactionResult,
        });
        const promptBuiltAt = performance.now();

        const kimiRequestStartedAt = performance.now();
        const abortController = new AbortController();
        const timeoutId = setTimeout(() => abortController.abort(), 35_000);
        const kimiResponse = await fetch(MOONSHOT_API_URL, {
          method: "POST",
          signal: abortController.signal,
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
            stream: true,
            prompt_cache_key: promptCacheKey,
            max_tokens: 1500,
            temperature: 0.6,
            thinking: { type: "disabled" },
          }),
        }).finally(() => clearTimeout(timeoutId));

        if (!kimiResponse.ok || !kimiResponse.body) {
          const errorData = await kimiResponse.json().catch(() => ({ message: "Unknown error" }));
          sendEvent("error", {
            message: errorData.error?.message || errorData.message || `Kimi HTTP ${kimiResponse.status}`,
          });
          controller.close();
          return;
        }

        const reader = kimiResponse.body.getReader();
        const decoder = new TextDecoder();
        let sseBuffer = "";
        let fullContent = "";
        let emittedReplyLength = 0;
        let firstTokenAt: number | null = null;

        while (true) {
          const { value, done } = await reader.read();
          if (done) break;

          sseBuffer += decoder.decode(value, { stream: true });
          const lines = sseBuffer.split(/\r?\n/);
          sseBuffer = lines.pop() ?? "";

          for (const line of lines) {
            if (!line.startsWith("data:")) continue;
            const data = line.slice(5).trim();
            if (!data || data === "[DONE]") continue;

            const parsedChunk = JSON.parse(data);
            const delta = parsedChunk?.choices?.[0]?.delta?.content;
            if (typeof delta !== "string" || delta.length === 0) continue;

            if (firstTokenAt == null) firstTokenAt = performance.now();
            fullContent += delta;

            const replyPrefix = extractStringFieldPrefix(fullContent, ["r", "reply"]);
            if (replyPrefix.value.length > emittedReplyLength) {
              const newText = replyPrefix.value.slice(emittedReplyLength);
              emittedReplyLength = replyPrefix.value.length;
              sendEvent("reply_delta", { text: newText });
            }
          }
        }

        const kimiStreamEndedAt = performance.now();
        const kimiJsonParseStartedAt = performance.now();
        const parsed = JSON.parse(fullContent);
        const kimiJsonParsedAt = performance.now();
        const protocolValidationStartedAt = performance.now();

        const compactJsonUsed = typeof parsed.r === "string" || Array.isArray(parsed.a);
        const reply = (typeof parsed.r === "string" ? parsed.r : parsed.reply)?.trim?.() ?? "";
        const actions = Array.isArray(parsed.a)
          ? parsed.a
          : Array.isArray(parsed.actions)
            ? parsed.actions
            : [];

        if (!reply) throw new Error("Kimi returned blank reply");
        validateActions(actions);
        const protocolValidatedAt = performance.now();

        const debugTiming = {
          traceId,
          promptVersion,
          totalMs: round(performance.now() - functionStartedAt),
          requestParseMs: round(requestParsedAt - functionStartedAt),
          promptBuildMs: round(promptBuiltAt - promptBuildStartedAt),
          kimiTimeToFirstTokenMs: firstTokenAt == null ? null : round(firstTokenAt - kimiRequestStartedAt),
          kimiStreamMs: round(kimiStreamEndedAt - kimiRequestStartedAt),
          kimiJsonParseMs: round(kimiJsonParsedAt - kimiJsonParseStartedAt),
          protocolValidationMs: round(protocolValidatedAt - protocolValidationStartedAt),
          promptChars: systemPrompt.length + userContent.length,
          outputJsonChars: fullContent.length,
          compactJsonUsed,
          promptCacheKeyUsed: Boolean(promptCacheKey),
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
          message: error instanceof Error ? error.message : "assistant-turn-v2-stream failed",
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
});

function buildSystemPrompt(): string {
  return `You are DayZero's low-pressure food logging assistant. Reply in the user's language with a natural, warm, concise tone. Do not create body anxiety or encourage extreme dieting.
Your job has only two parts: 1) write the user-visible reply; 2) decide whether a card tool is needed. Do not expose intent labels or rule explanations.
Return only one JSON object. Prefer compact format and put r first: {"r":"user-visible reply","a":[]}
Legacy format {"reply":"...","actions":[]} is also accepted. Never output text outside JSON.
Allowed card tools:
1 ask_record_intent_card: use when the user appears to share food/drink already consumed but did not clearly ask to record it. options must include record/chat_only/not_now.
2 ask_missing_info_card: use when the user wants to record food but mealType is missing. options must include breakfast/lunch/dinner/snack.
3 show_confirm_card: use when mealType and food items are enough to show a food_record draft. Do not write data before confirmation. Estimate calories if needed and set calorieConfidence="estimated". Split multiple meals into meals[]. If weight is not mentioned, weightKg=null.
4 debug_show_choice_card: only when the user explicitly asks to test tools/cards.
For interaction_result, naturally continue from the user's card choice. Return the next needed card, or a=[] if no card is needed.
Inside a[], keep the existing full action field names. Do not compact nested action payloads: use type/id or interactionId/payload.`;
}

function buildUserContent(input: {
  date: string;
  recentContext: string;
  turnType: string;
  userText: string;
  interactionResult: unknown;
}): string {
  let content = `Date:${input.date}
Recent:
${input.recentContext || "None"}
TurnType:${input.turnType}
`;

  if (input.turnType === "interaction_result" && input.interactionResult && typeof input.interactionResult === "object") {
    const result = input.interactionResult as Record<string, unknown>;
    content += `CardAction:${String(result.actionType ?? "")}
InteractionId:${String(result.interactionId ?? "")}
Selected:${String(result.selectedOptionId ?? "")}/${String(result.selectedOptionLabel ?? "")}
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
      const item = message && typeof message === "object" ? message as Record<string, unknown> : {};
      const role = typeof item.role === "string" ? item.role : "Unknown";
      const text = typeof item.text === "string" ? item.text.trim().slice(0, 160) : "";
      return text ? `${role}:${text}` : "";
    })
    .filter(Boolean)
    .join("\n");
}

function normalizePromptCacheKey(value: unknown): string | undefined {
  if (typeof value !== "string") return undefined;
  const normalized = value.trim().replace(/[^a-zA-Z0-9_.:-]/g, "_").slice(0, 120);
  return normalized.length > 0 ? normalized : undefined;
}

function extractStringFieldPrefix(json: string, keys: string[]): { value: string; complete: boolean } {
  for (const key of keys) {
    const keyIndex = json.indexOf(`"${key}"`);
    if (keyIndex < 0) continue;
    const colonIndex = json.indexOf(":", keyIndex + key.length + 2);
    if (colonIndex < 0) continue;
    let valueStart = colonIndex + 1;
    while (valueStart < json.length && /\s/.test(json[valueStart])) valueStart++;
    if (json[valueStart] !== '"') continue;
    return readJsonStringPrefix(json, valueStart + 1);
  }
  return { value: "", complete: false };
}

function readJsonStringPrefix(source: string, start: number): { value: string; complete: boolean } {
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
    if (escaped === '"' || escaped === "\\" || escaped === "/") value += escaped;
    else if (escaped === "b") value += "\b";
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
    if (!action || typeof action !== "object") throw new Error("Invalid action");
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
