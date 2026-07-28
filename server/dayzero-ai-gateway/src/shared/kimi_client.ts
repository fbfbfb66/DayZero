import type { KimiUserMessage } from "./assistant_vision.ts";

export type KimiConfig = {
  apiUrl: string;
  apiKey: string;
  model: string;
  maxTokens?: number;
  temperature?: number;
};

export function buildKimiRequestBody(
  systemPrompt: string,
  userMessage: KimiUserMessage,
  promptCacheKey: string | undefined,
  stream: boolean,
  config: KimiConfig,
): Record<string, unknown> {
  const body: Record<string, unknown> = {
    model: config.model,
    messages: [
      { role: "system", content: systemPrompt },
      userMessage,
    ],
    response_format: { type: "json_object" },
    max_tokens: config.maxTokens ?? 1500,
    temperature: config.temperature ?? 0.6,
    thinking: { type: "disabled" },
  };

  if (promptCacheKey) {
    body.prompt_cache_key = promptCacheKey;
  }

  if (stream) {
    body.stream = true;
  }

  return body;
}

export function fetchKimi(
  config: KimiConfig,
  body: Record<string, unknown>,
  signal: AbortSignal,
): Promise<Response> {
  return fetch(config.apiUrl, {
    method: "POST",
    signal,
    headers: {
      "Content-Type": "application/json",
      "Authorization": `Bearer ${config.apiKey}`,
    },
    body: JSON.stringify(body),
  });
}

export type KimiResult = { choices?: Array<{ message?: { content?: unknown } }> };
