import type { KimiUserMessage } from "./assistant_vision.ts";
import { buildKimiRequestBody, fetchKimi, type KimiConfig } from "./kimi_client.ts";

export type CandidateRace = {
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

export class CandidateRaceError extends Error {
  constructor(
    readonly code: "UPSTREAM_ALL_ATTEMPTS_FAILED" | "UPSTREAM_BUDGET_EXHAUSTED",
  ) {
    super(code);
  }
}

/**
 * A vision turn has one client-to-Edge request but up to three independent Kimi
 * candidates. Candidate zero is the only visible stream. A candidate cannot win
 * until it contains a minimally valid final JSON response; all losers are aborted.
 */
export async function raceMoonshotCandidates(input: {
  config: KimiConfig;
  systemPrompt: string;
  userMessage: KimiUserMessage;
  promptCacheKey?: string;
  attemptCount: number;
  budgetMs: number;
  onPrimaryDelta: (text: string) => void;
}): Promise<CandidateRace> {
  const controllers = Array.from(
    { length: input.attemptCount },
    () => new AbortController(),
  );
  let budgetExhausted = false;
  const budget = setTimeout(() => {
    budgetExhausted = true;
    controllers.forEach((controller) => controller.abort());
  }, input.budgetMs);
  let primaryEmittedText = false;
  try {
    const pending = new Map<
      number,
      Promise<{ index: number; result?: CandidateResult; error?: unknown }>
    >();
    for (let index = 0; index < input.attemptCount; index++) {
      const promise = (index === 0
        ? readStreamingCandidate(index, controllers[index], input, () => {
          primaryEmittedText = true;
        })
        : readJsonCandidate(index, controllers[index], input)).then(
          (result) => ({ index, result }),
          (error) => ({ index, error }),
        );
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
  config: KimiConfig;
  systemPrompt: string;
  userMessage: KimiUserMessage;
  promptCacheKey?: string;
  stream: boolean;
  signal: AbortSignal;
}): Promise<Response> {
  const body = buildKimiRequestBody(
    input.systemPrompt,
    input.userMessage,
    input.promptCacheKey,
    input.stream,
    input.config,
  );
  return fetchKimi(input.config, body, input.signal);
}

async function readStreamingCandidate(
  index: number,
  controller: AbortController,
  input: Parameters<typeof raceMoonshotCandidates>[0],
  markPrimaryText: () => void,
): Promise<CandidateResult> {
  const response = await candidateRequest({
    ...input,
    stream: true,
    signal: controller.signal,
  });
  const headersAt = performance.now();
  if (!response.ok || !response.body) {
    throw new Error(`Kimi HTTP ${response.status}`);
  }
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
  return {
    index,
    content,
    headersAt,
    firstTokenAt,
    completedAt: performance.now(),
  };
}

async function readJsonCandidate(
  index: number,
  controller: AbortController,
  input: Parameters<typeof raceMoonshotCandidates>[0],
): Promise<CandidateResult> {
  const response = await candidateRequest({
    ...input,
    stream: false,
    signal: controller.signal,
  });
  const headersAt = performance.now();
  if (!response.ok) throw new Error(`Kimi HTTP ${response.status}`);
  const json = await response.json();
  const content = json?.choices?.[0]?.message?.content;
  if (typeof content !== "string") throw new Error("Kimi returned empty content");
  return {
    index,
    content,
    headersAt,
    firstTokenAt: null,
    completedAt: performance.now(),
  };
}

export function isValidCandidateContent(content: string): boolean {
  try {
    const parsed = JSON.parse(content);
    const reply = typeof parsed?.r === "string" ? parsed.r : parsed?.reply;
    const actions = Array.isArray(parsed?.a) ? parsed.a : parsed?.actions ?? [];
    if (
      typeof reply !== "string" || reply.trim().length === 0 ||
      !Array.isArray(actions)
    ) {
      return false;
    }
    return actions.every((action: unknown) => {
      if (!action || typeof action !== "object" || Array.isArray(action)) {
        return false;
      }
      const raw = action as Record<string, unknown>;
      const type = raw.type ?? raw.t;
      return typeof type === "string" && type.trim().length > 0;
    });
  } catch {
    return false;
  }
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
