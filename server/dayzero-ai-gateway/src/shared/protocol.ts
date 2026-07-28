export type ParsedKimiResponse = {
  reply: string;
  actions: Record<string, unknown>[];
  compactJsonUsed: boolean;
  rawContent: string;
};

export function parseKimiContent(content: string): ParsedKimiResponse {
  const parsed = JSON.parse(content) as Record<string, unknown>;
  const compactJsonUsed = typeof parsed.r === "string" ||
    Array.isArray(parsed.a);
  const rawReply = typeof parsed.r === "string" ? parsed.r : parsed.reply;
  const reply = typeof rawReply === "string" ? rawReply.trim() : "";
  const actions = Array.isArray(parsed.a)
    ? parsed.a as Record<string, unknown>[]
    : Array.isArray(parsed.actions)
    ? parsed.actions as Record<string, unknown>[]
    : [];

  return { reply, actions, compactJsonUsed, rawContent: content };
}

export function validateActions(actions: unknown[]): void {
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

export function roundTiming(value: number): number {
  return Math.round(value * 100) / 100;
}
