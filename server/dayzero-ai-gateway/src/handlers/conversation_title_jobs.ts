import type { GatewayConfig } from "../config.ts";
import { readLimitedJsonBody } from "../body_reader.ts";
import { getCorsHeaders } from "../cors.ts";
import { createJsonResponse } from "../errors.ts";
import type { StructuredLogger } from "../logger.ts";

type TitleJobRequest = {
  requestId: string;
  conversationId: string;
  firstUserMessageId: string;
  firstUserText: string;
};

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
export const TITLE_INPUT_MAX_CHARS = 2_000;

export function parseTitleJobRequest(value: unknown):
  | { ok: true; value: TitleJobRequest }
  | { ok: false; status: number; errorCode: string } {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return { ok: false, status: 400, errorCode: "BODY_INVALID" };
  }
  const body = value as Record<string, unknown>;
  const requestId = typeof body.requestId === "string" ? body.requestId.trim() : "";
  const conversationId = typeof body.conversationId === "string"
    ? body.conversationId.trim()
    : "";
  const firstUserMessageId = typeof body.firstUserMessageId === "string"
    ? body.firstUserMessageId.trim()
    : "";
  const firstUserText = typeof body.firstUserText === "string"
    ? body.firstUserText.trim()
    : "";

  if (requestId.length < 8 || requestId.length > 128) {
    return { ok: false, status: 400, errorCode: "REQUEST_ID_INVALID" };
  }
  if (!UUID_PATTERN.test(conversationId) || !UUID_PATTERN.test(firstUserMessageId)) {
    return { ok: false, status: 400, errorCode: "ID_INVALID" };
  }
  if (!firstUserText) {
    return { ok: false, status: 400, errorCode: "TEXT_EMPTY" };
  }
  if (firstUserText.length > TITLE_INPUT_MAX_CHARS) {
    return { ok: false, status: 413, errorCode: "TEXT_TOO_LONG" };
  }
  return {
    ok: true,
    value: { requestId, conversationId, firstUserMessageId, firstUserText },
  };
}

function rpcErrorStatus(errorCode: string): number {
  switch (errorCode) {
    case "CONVERSATION_NOT_READY":
    case "MESSAGE_NOT_READY":
      return 409;
    case "AUTH_REQUIRED":
      return 401;
    case "TEXT_TOO_LONG":
      return 413;
    case "MESSAGE_TEXT_MISMATCH":
    case "NOT_FIRST_USER_MESSAGE":
      return 422;
    default:
      return 400;
  }
}

export async function handleConversationTitleJob(
  req: Request,
  config: GatewayConfig,
  logger: StructuredLogger,
  requestId: string,
  userIdDigest: string,
): Promise<Response> {
  const headers = {
    ...getCorsHeaders(config, req.headers.get("Origin")),
    "X-Request-Id": requestId,
  };
  const bodyResult = await readLimitedJsonBody(req, config, headers);
  if (!bodyResult.ok) return bodyResult.response;
  const parsed = parseTitleJobRequest(bodyResult.body);
  if (!parsed.ok) {
    return createJsonResponse(
      { error: "Invalid title job request", errorCode: parsed.errorCode, retryable: false },
      parsed.status,
      headers,
    );
  }

  const supabaseUrl = config.supabaseUrl?.replace(/\/+$/, "");
  const publishableKey = config.supabasePublishableKey;
  const authorization = req.headers.get("Authorization");
  if (!supabaseUrl || !publishableKey || !authorization) {
    return createJsonResponse(
      { error: "Title queue unavailable", errorCode: "TITLE_QUEUE_UNAVAILABLE", retryable: true },
      503,
      headers,
    );
  }

  logger.info("conversation title job submit", {
    requestId,
    userIdDigest,
    routeName: "conversation-title-jobs",
    status: "submitting",
  });

  let response: Response;
  try {
    response = await fetch(`${supabaseUrl}/rest/v1/rpc/enqueue_ai_conversation_title_job`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "apikey": publishableKey,
        "Authorization": authorization,
      },
      body: JSON.stringify({
        p_request_id: parsed.value.requestId,
        p_conversation_id: parsed.value.conversationId,
        p_first_user_message_id: parsed.value.firstUserMessageId,
        p_first_user_text: parsed.value.firstUserText,
      }),
      signal: AbortSignal.timeout(8_000),
    });
  } catch {
    return createJsonResponse(
      { error: "Title queue unavailable", errorCode: "TITLE_QUEUE_UNAVAILABLE", retryable: true },
      503,
      headers,
    );
  }

  if (!response.ok) {
    const retryable = response.status === 408 || response.status === 429 || response.status >= 500;
    return createJsonResponse(
      {
        error: "Title queue rejected request",
        errorCode: `TITLE_QUEUE_HTTP_${response.status}`,
        retryable,
      },
      retryable ? 503 : response.status,
      headers,
    );
  }

  const result = await response.json() as Record<string, unknown>;
  if (result.accepted !== true) {
    const errorCode = String(result.errorCode ?? "TITLE_QUEUE_REJECTED");
    return createJsonResponse(
      { error: "Title job rejected", errorCode, retryable: rpcErrorStatus(errorCode) === 409 },
      rpcErrorStatus(errorCode),
      headers,
    );
  }

  logger.info("conversation title job accepted", {
    requestId,
    userIdDigest,
    routeName: "conversation-title-jobs",
    status: "accepted",
  });
  return createJsonResponse(
    { jobId: result.jobId, status: "accepted" },
    202,
    headers,
  );
}
