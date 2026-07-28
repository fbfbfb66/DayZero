import type { GatewayConfig } from "../config.ts";
import { getCorsHeaders } from "../cors.ts";
import { createErrorResponse, createJsonResponse } from "../errors.ts";

type ReadinessCheck =
  | { ok: true }
  | { ok: false; code: string; message: string };

function isNonEmptyString(value: unknown): value is string {
  return typeof value === "string" && value.trim().length > 0;
}

function looksLikeUrl(value: string): boolean {
  try {
    new URL(value);
    return true;
  } catch {
    return false;
  }
}

function checkReadiness(config: GatewayConfig): ReadinessCheck {
  if (!Number.isFinite(config.port) || config.port <= 0) {
    return {
      ok: false,
      code: "INVALID_PORT",
      message: "Server port is not configured",
    };
  }

  if (!isNonEmptyString(config.kimiApiUrl) || !looksLikeUrl(config.kimiApiUrl)) {
    return {
      ok: false,
      code: "INVALID_KIMI_API_URL",
      message: "Kimi API URL is missing or malformed",
    };
  }

  if (!isNonEmptyString(config.kimiApiKey)) {
    return {
      ok: false,
      code: "MISSING_KIMI_API_KEY",
      message: "Kimi API key is missing",
    };
  }

  if (!isNonEmptyString(config.kimiModel)) {
    return {
      ok: false,
      code: "MISSING_KIMI_MODEL",
      message: "Kimi model is missing",
    };
  }

  if (!Number.isFinite(config.kimiMaxTokens) || config.kimiMaxTokens <= 0) {
    return {
      ok: false,
      code: "INVALID_KIMI_MAX_TOKENS",
      message: "Kimi max tokens is invalid",
    };
  }

  if (!Number.isFinite(config.kimiTemperature)) {
    return {
      ok: false,
      code: "INVALID_KIMI_TEMPERATURE",
      message: "Kimi temperature is invalid",
    };
  }

  if (config.enableAuth) {
    if (!isNonEmptyString(config.supabaseJwksUrl) || !looksLikeUrl(config.supabaseJwksUrl)) {
      return {
        ok: false,
        code: "INVALID_SUPABASE_JWKS_URL",
        message: "Supabase JWKS URL is missing or malformed",
      };
    }

    if (!isNonEmptyString(config.supabaseIssuer) || !looksLikeUrl(config.supabaseIssuer)) {
      return {
        ok: false,
        code: "INVALID_SUPABASE_ISSUER",
        message: "Supabase issuer is missing or malformed",
      };
    }

    if (!isNonEmptyString(config.supabaseAudience)) {
      return {
        ok: false,
        code: "MISSING_SUPABASE_AUDIENCE",
        message: "Supabase audience is missing",
      };
    }
  }

  return { ok: true };
}

export function handleReady(
  req: Request,
  config: GatewayConfig,
  requestId?: string,
): Response {
  const headers = {
    ...getCorsHeaders(config, req.headers.get("Origin")),
    ...(requestId ? { "X-Request-Id": requestId } : {}),
  };

  const readiness = checkReadiness(config);
  if (!readiness.ok) {
    return createErrorResponse(503, readiness.message, readiness.code, headers);
  }

  return createJsonResponse({ status: "ready", usedLegacyEnv: config.usedLegacyEnv }, 200, headers);
}
