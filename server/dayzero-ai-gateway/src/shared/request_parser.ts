import { VisionValidationError } from "./assistant_vision.ts";
import { buildRecentContext, normalizePromptCacheKey } from "./prompt.ts";

export type ParsedRequest = {
  traceId: string | null;
  date: string;
  userText: string;
  turnType: string;
  interactionResult: unknown;
  todayRecord: unknown;
  recentMessages: unknown;
  promptCacheKey: string | undefined;
  attachments: unknown;
  rawBody: Record<string, unknown>;
};

export type RequestValidationResult =
  | { ok: true; request: ParsedRequest }
  | { ok: false; status: number; body: Record<string, unknown> };

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isString(value: unknown): value is string {
  return typeof value === "string";
}

export function parseRequestBody(body: Record<string, unknown>): RequestValidationResult {
  if (!isPlainObject(body)) {
    return {
      ok: false,
      status: 400,
      body: {
        error: "Request body must be a JSON object",
        errorCode: "INVALID_BODY_TYPE",
      },
    };
  }

  const traceId = isString(body.traceId) ? body.traceId : null;

  if (body.userText !== undefined && !isString(body.userText)) {
    return {
      ok: false,
      status: 400,
      body: {
        error: "userText must be a string",
        errorCode: "INVALID_USER_TEXT",
      },
    };
  }
  const userText = isString(body.userText) ? body.userText.trim() : "";

  if (!userText) {
    const hasAttachments = Array.isArray(body.attachments) &&
      body.attachments.length > 0;
    if (hasAttachments) {
      return {
        ok: false,
        status: 400,
        body: {
          error: "Vision requests require non-empty text",
          errorCode: "EMPTY_VISION_TEXT",
        },
      };
    }
    return {
      ok: false,
      status: 400,
      body: { error: "Missing userText" },
    };
  }

  const turnType = isString(body.turnType) ? body.turnType.trim() : "user_message";
  if (turnType !== "user_message" && turnType !== "interaction_result") {
    return {
      ok: false,
      status: 400,
      body: {
        error: "turnType must be user_message or interaction_result",
        errorCode: "INVALID_TURN_TYPE",
      },
    };
  }

  if (body.date !== undefined && !isString(body.date)) {
    return {
      ok: false,
      status: 400,
      body: {
        error: "date must be a string",
        errorCode: "INVALID_DATE",
      },
    };
  }

  if (body.recentMessages !== undefined && !Array.isArray(body.recentMessages)) {
    return {
      ok: false,
      status: 400,
      body: {
        error: "recentMessages must be an array",
        errorCode: "INVALID_RECENT_MESSAGES",
      },
    };
  }

  if (
    body.todayRecord !== undefined &&
    body.todayRecord !== null &&
    !isPlainObject(body.todayRecord)
  ) {
    return {
      ok: false,
      status: 400,
      body: {
        error: "todayRecord must be an object",
        errorCode: "INVALID_TODAY_RECORD",
      },
    };
  }

  if (
    body.pendingDraft !== undefined &&
    body.pendingDraft !== null &&
    !isPlainObject(body.pendingDraft)
  ) {
    return {
      ok: false,
      status: 400,
      body: {
        error: "pendingDraft must be an object",
        errorCode: "INVALID_PENDING_DRAFT",
      },
    };
  }

  if (turnType === "interaction_result") {
    if (!isPlainObject(body.interactionResult)) {
      return {
        ok: false,
        status: 400,
        body: {
          error: "interactionResult is required for interaction_result turnType",
          errorCode: "MISSING_INTERACTION_RESULT",
        },
      };
    }
  }

  return {
    ok: true,
    request: {
      traceId,
      date: isString(body.date) ? body.date : "",
      userText,
      turnType,
      interactionResult: body.interactionResult,
      todayRecord: body.todayRecord,
      recentMessages: body.recentMessages,
      promptCacheKey: normalizePromptCacheKey(body.promptCacheKey),
      attachments: body.attachments,
      rawBody: body,
    },
  };
}

export function buildPromptInputs(request: ParsedRequest) {
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

export function mapVisionError(error: unknown): { status: number; body: Record<string, unknown> } {
  if (error instanceof VisionValidationError) {
    const status = error.code === "ATTACHMENT_TOO_LARGE" ||
        error.code === "ATTACHMENTS_TOTAL_TOO_LARGE"
      ? 413
      : 400;
    return {
      status,
      body: { error: error.message, errorCode: error.code },
    };
  }
  return {
    status: 500,
    body: { error: "Internal Server Error" },
  };
}
