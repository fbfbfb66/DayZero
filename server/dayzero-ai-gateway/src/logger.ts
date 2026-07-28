import type { GatewayConfig } from "./config.ts";

export type LogContext = Record<string, unknown>;

/**
 * Safe, finite whitelist of fields that may appear in logs.
 * No request/response bodies, prompts, PII, exceptions, paths, secrets, or tokens.
 */
const ALLOWED_LOG_FIELDS = new Set([
  "requestId",
  "userIdDigest",
  "routePath",
  "routeName",
  "method",
  "turnType",
  "attachmentCount",
  "attachmentBytes",
  "candidateCount",
  "winnerAttemptIndex",
  "cancelledAttemptCount",
  "status",
  "httpStatus",
  "errorCode",
  "retryable",
  "fallbackReason",
  "appEnv",
  "usedLegacyEnv",
  "traceId",
  "userTextLength",
  "replyLength",
  "actionsCount",
  "totalDecodedAttachmentBytes",
  "compactJsonUsed",
  "promptCacheKeyUsed",
  "allowedOriginCount",
  "selectedStreamHeaderTimeoutMs",
  "upstreamBudgetMs",
  "hedgedAttemptCount",
  "provisionalTextReplaced",
  "timeoutStage",
  "stage",
  // Timing numbers are safe; their values reveal nothing about content.
  "totalMs",
  "requestParseMs",
  "promptBuildMs",
  "kimiRequestMs",
  "kimiJsonParseMs",
  "protocolValidationMs",
  "kimiTimeToFirstTokenMs",
  "kimiStreamMs",
  "upstreamHeadersMs",
  "upstreamTotalMs",
  "lastReplyContentAvailableMs",
  "actionsReadyMs",
  "edgeFinalEmittedMs",
  "lastReplyToActionsReadyMs",
  "actionsReadyToEdgeFinalMs",
  "promptChars",
  "outputJsonChars",
]);

function isAllowedField(key: string): boolean {
  return ALLOWED_LOG_FIELDS.has(key);
}

function isSafeScalar(value: unknown): boolean {
  return value === null ||
    typeof value === "boolean" ||
    typeof value === "number" ||
    typeof value === "string" ||
    typeof value === "bigint";
}

/**
 * Keep only explicitly-allowed, scalar log fields.
 * Objects, arrays, exceptions, errors, causes, stacks, and unknown keys are dropped.
 * No stringify of arbitrary payloads happens here.
 */
export function sanitizeContext(context: LogContext): LogContext {
  const result: LogContext = {};
  for (const [key, value] of Object.entries(context)) {
    if (!isAllowedField(key)) {
      continue;
    }
    if (isSafeScalar(value)) {
      result[key] = value;
    }
    // Arrays and objects are intentionally dropped to prevent accidental leakage
    // of bodies, exceptions, or nested sensitive fields.
  }
  return result;
}

/**
 * Produces a stable, irreversible user identifier for logs.
 * The raw `sub` (a high-entropy Supabase UUID) is never logged.
 */
export async function digestUserId(
  rawSub: string,
  hexLength = 16,
): Promise<string> {
  const encoder = new TextEncoder();
  const buffer = await crypto.subtle.digest(
    "SHA-256",
    encoder.encode(rawSub),
  );
  const hex = Array.from(new Uint8Array(buffer))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
  return `u_${hex.slice(0, Math.min(hexLength, hex.length))}`;
}

export class StructuredLogger {
  constructor(
    private readonly service: string,
    private readonly level: "debug" | "info" | "warn" | "error",
  ) {}

  private shouldEmit(level: "debug" | "info" | "warn" | "error"): boolean {
    const levels = ["debug", "info", "warn", "error"];
    return levels.indexOf(level) >= levels.indexOf(this.level);
  }

  private log(
    level: "debug" | "info" | "warn" | "error",
    message: string,
    context: LogContext,
  ) {
    if (!this.shouldEmit(level)) return;

    const entry = {
      timestamp: new Date().toISOString(),
      service: this.service,
      level,
      message,
      ...sanitizeContext(context),
    };

    const serialized = JSON.stringify(entry);

    switch (level) {
      case "debug":
        console.debug(serialized);
        break;
      case "info":
        console.info(serialized);
        break;
      case "warn":
        console.warn(serialized);
        break;
      case "error":
        console.error(serialized);
        break;
    }
  }

  debug(message: string, context: LogContext = {}) {
    this.log("debug", message, context);
  }

  info(message: string, context: LogContext = {}) {
    this.log("info", message, context);
  }

  warn(message: string, context: LogContext = {}) {
    this.log("warn", message, context);
  }

  error(message: string, context: LogContext = {}) {
    this.log("error", message, context);
  }
}

export function createLogger(config: GatewayConfig): StructuredLogger {
  return new StructuredLogger("dayzero-ai-gateway", config.logLevel);
}
