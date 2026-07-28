import type { StructuredLogger } from "./logger.ts";

export type UpstreamErrorCode =
  | "UPSTREAM_RATE_LIMITED"
  | "UPSTREAM_UNAVAILABLE"
  | "UPSTREAM_TIMEOUT"
  | "UPSTREAM_TOTAL_TIMEOUT"
  | "UPSTREAM_PROTOCOL_ERROR"
  | "INVALID_UPSTREAM_RESPONSE"
  | "INTERNAL_ERROR";

export type ClientErrorBody = {
  error: string;
  errorCode: UpstreamErrorCode;
  retryable: boolean;
};

const UPSTREAM_SAFE_MESSAGES: Record<UpstreamErrorCode, string> = {
  UPSTREAM_RATE_LIMITED: "AI service is rate limited. Please retry.",
  UPSTREAM_UNAVAILABLE: "AI service is temporarily unavailable. Please retry.",
  UPSTREAM_TIMEOUT: "AI request timed out. Please retry.",
  UPSTREAM_TOTAL_TIMEOUT: "Upstream request timed out. Please retry.",
  UPSTREAM_PROTOCOL_ERROR: "AI service protocol error. Please retry.",
  INVALID_UPSTREAM_RESPONSE: "AI response could not be validated.",
  INTERNAL_ERROR: "Internal Server Error",
};

export function createJsonResponse(
  body: Record<string, unknown>,
  status: number,
  headers: Record<string, string>,
): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      ...headers,
      "Content-Type": "application/json",
    },
  });
}

export function createErrorResponse(
  status: number,
  message: string,
  code?: string,
  headers: Record<string, string> = {},
): Response {
  const body: Record<string, unknown> = { error: message };
  if (code) body.errorCode = code;
  return createJsonResponse(body, status, headers);
}

export function createUpstreamErrorResponse(
  code: UpstreamErrorCode,
  status: number,
  retryable: boolean,
  headers: Record<string, string>,
): Response {
  const body: ClientErrorBody = {
    error: UPSTREAM_SAFE_MESSAGES[code],
    errorCode: code,
    retryable,
  };
  return createJsonResponse(body, status, headers);
}

export function mapKimiStatusToErrorCode(status: number): UpstreamErrorCode {
  if (status === 429) return "UPSTREAM_RATE_LIMITED";
  if (status >= 500 && status <= 504) return "UPSTREAM_UNAVAILABLE";
  if (status >= 400 && status < 500) return "UPSTREAM_PROTOCOL_ERROR";
  return "UPSTREAM_UNAVAILABLE";
}

export function isRetryableUpstreamStatus(status: number): boolean {
  return status === 429 || status >= 500;
}

export function handleUnexpectedError(
  _error: unknown,
  requestId: string,
  logger: StructuredLogger,
  headers: Record<string, string>,
): Response {
  logger.error("internal error", { requestId, errorCode: "INTERNAL_ERROR" });
  return createUpstreamErrorResponse(
    "INTERNAL_ERROR",
    500,
    false,
    headers,
  );
}
