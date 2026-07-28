import { createErrorResponse } from "./errors.ts";
import type { GatewayConfig } from "./config.ts";

export type BodyReadResult =
  | { ok: true; body: Record<string, unknown> }
  | { ok: false; response: Response };

export async function readLimitedJsonBody(
  req: Request,
  config: GatewayConfig,
  corsHeaders: Record<string, string>,
): Promise<BodyReadResult> {
  const contentLength = req.headers.get("Content-Length");
  if (contentLength) {
    const length = Number.parseInt(contentLength, 10);
    if (!Number.isNaN(length) && length > config.requestBodyLimitBytes) {
      return {
        ok: false,
        response: createErrorResponse(
          413,
          "Request body too large",
          "REQUEST_BODY_TOO_LARGE",
          corsHeaders,
        ),
      };
    }
  }

  let bodyText: string;
  try {
    bodyText = await readTextWithLimit(req, config.requestBodyLimitBytes);
  } catch (_error) {
    return {
      ok: false,
      response: createErrorResponse(
        413,
        "Request body too large",
        "REQUEST_BODY_TOO_LARGE",
        corsHeaders,
      ),
    };
  }

  try {
    const parsed = JSON.parse(bodyText);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      return {
        ok: false,
        response: createErrorResponse(
          400,
          "Request body must be a JSON object",
          "INVALID_BODY_TYPE",
          corsHeaders,
        ),
      };
    }
    return { ok: true, body: parsed as Record<string, unknown> };
  } catch (_error) {
    return {
      ok: false,
      response: createErrorResponse(
        400,
        "Invalid JSON body",
        undefined,
        corsHeaders,
      ),
    };
  }
}

async function readTextWithLimit(req: Request, limitBytes: number): Promise<string> {
  const reader = req.body?.getReader();
  if (!reader) return "{}";

  const decoder = new TextDecoder();
  let total = 0;
  const chunks: Uint8Array[] = [];

  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    if (value) {
      total += value.length;
      if (total > limitBytes) {
        await reader.cancel();
        throw new Error("Body exceeds size limit");
      }
      chunks.push(value);
    }
  }

  if (chunks.length === 0) return "{}";
  const combined = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    combined.set(chunk, offset);
    offset += chunk.length;
  }
  return decoder.decode(combined);
}
