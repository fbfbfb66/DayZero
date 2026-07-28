export type AppEnv = "production" | "development" | "test";

export type GatewayConfig = {
  appEnv: AppEnv;
  port: number;
  kimiApiUrl: string;
  kimiApiKey: string;
  kimiModel: string;
  kimiMaxTokens: number;
  kimiTemperature: number;
  /**
   * Direct JWKS endpoint. Preferred in production.
   */
  supabaseJwksUrl: string;
  /**
   * Expected JWT issuer. Preferred in production.
   */
  supabaseIssuer: string;
  /**
   * Expected JWT audience. Preferred in production.
   */
  supabaseAudience: string;
  /**
   * Legacy Supabase project URL. Kept for migration compatibility; used to derive
   * JWKS URL and issuer when the direct variables are absent.
   */
  supabaseUrl: string | undefined;
  /**
   * Legacy audience name. Kept for migration compatibility.
   */
  supabaseJwtAudience: string | undefined;
  allowedOrigins: string[];
  requestBodyLimitBytes: number;
  logLevel: "debug" | "info" | "warn" | "error";
  enableAuth: boolean;
  /**
   * True when the running config fell back to a legacy environment variable name.
   * Used to emit a single, value-free migration log.
   */
  usedLegacyEnv: boolean;
};

function getEnv(name: string): string | undefined {
  try {
    return Deno.env.get(name);
  } catch {
    return undefined;
  }
}

function requireEnv(name: string): string {
  const value = getEnv(name);
  if (!value) {
    throw new Error(`Missing required environment variable: ${name}`);
  }
  return value;
}

function parseIntEnv(name: string, defaultValue: number): number {
  const raw = getEnv(name);
  if (!raw) return defaultValue;
  const value = Number.parseInt(raw, 10);
  if (Number.isNaN(value) || value <= 0) {
    throw new Error(`Invalid ${name}: must be a positive integer`);
  }
  return value;
}

function parseFloatEnv(name: string, defaultValue: number): number {
  const raw = getEnv(name);
  if (!raw) return defaultValue;
  const value = Number.parseFloat(raw);
  if (Number.isNaN(value)) {
    throw new Error(`Invalid ${name}: must be a number`);
  }
  return value;
}

function parseAppEnv(raw: string | undefined): AppEnv {
  switch (raw?.toLowerCase()) {
    case "development":
    case "dev":
      return "development";
    case "test":
      return "test";
    case "production":
    case "prod":
    default:
      return "production";
  }
}

function parseLogLevel(
  raw: string | undefined,
): "debug" | "info" | "warn" | "error" {
  switch (raw?.toLowerCase()) {
    case "debug":
      return "debug";
    case "warn":
    case "warning":
      return "warn";
    case "error":
      return "error";
    case "info":
    default:
      return "info";
  }
}

export function normalizeSupabaseUrl(supabaseUrl: string): string {
  return supabaseUrl.replace(/\/+$/, "");
}

export function getJwksUrl(supabaseUrl: string): string {
  return `${normalizeSupabaseUrl(supabaseUrl)}/auth/v1/.well-known/jwks.json`;
}

/**
 * Supabase GoTrue signs access tokens with `iss = <normalized SUPABASE_URL>/auth/v1`
 * (verified against the project OpenID configuration). The issuer check must include the
 * `/auth/v1` suffix — using the bare project URL rejects every real token.
 */
export function getExpectedIssuer(supabaseUrl: string): string {
  return `${normalizeSupabaseUrl(supabaseUrl)}/auth/v1`;
}

export function loadConfig(): GatewayConfig {
  const appEnv = parseAppEnv(getEnv("APP_ENV"));
  const enableAuth = getEnv("ENABLE_AUTH") !== "false";

  if (appEnv === "production" && !enableAuth) {
    throw new Error(
      "ENABLE_AUTH=false is not allowed in production. Set ENABLE_AUTH=true or use a non-production APP_ENV.",
    );
  }

  let usedLegacyEnv = false;

  const supabaseJwksUrlRaw = getEnv("SUPABASE_JWKS_URL");
  const supabaseIssuerRaw = getEnv("SUPABASE_ISSUER");
  const supabaseAudienceRaw = getEnv("SUPABASE_AUDIENCE");
  const supabaseUrlRaw = getEnv("SUPABASE_URL");
  const supabaseJwtAudienceRaw = getEnv("SUPABASE_JWT_AUDIENCE");

  if (!supabaseJwksUrlRaw && !supabaseIssuerRaw && !supabaseAudienceRaw) {
    usedLegacyEnv = true;
  }

  const supabaseJwksUrl = supabaseJwksUrlRaw ??
    (supabaseUrlRaw ? getJwksUrl(supabaseUrlRaw) : undefined);
  const supabaseIssuer = supabaseIssuerRaw ??
    (supabaseUrlRaw ? getExpectedIssuer(supabaseUrlRaw) : undefined);
  const supabaseAudience = supabaseAudienceRaw ?? supabaseJwtAudienceRaw ??
    "authenticated";

  if (
    enableAuth &&
    (!supabaseJwksUrl || !supabaseIssuer || !supabaseAudience)
  ) {
    throw new Error(
      "Authentication is enabled but Supabase JWKS/issuer/audience are not configured. " +
        "Set SUPABASE_JWKS_URL, SUPABASE_ISSUER, and SUPABASE_AUDIENCE (or legacy SUPABASE_URL).",
    );
  }

  const allowedOriginsRaw = getEnv("ALLOWED_ORIGINS") ?? "*";
  const allowedOrigins = allowedOriginsRaw
    .split(",")
    .map((origin) => origin.trim())
    .filter(Boolean);

  return {
    appEnv,
    port: parseIntEnv("PORT", 8080),
    kimiApiUrl: requireEnv("KIMI_API_URL"),
    kimiApiKey: requireEnv("KIMI_API_KEY"),
    kimiModel: requireEnv("KIMI_MODEL"),
    kimiMaxTokens: parseIntEnv("KIMI_MAX_TOKENS", 1500),
    kimiTemperature: parseFloatEnv("KIMI_TEMPERATURE", 0.6),
    supabaseJwksUrl: supabaseJwksUrl ?? "",
    supabaseIssuer: supabaseIssuer ?? "",
    supabaseAudience: supabaseAudience,
    supabaseUrl: supabaseUrlRaw,
    supabaseJwtAudience: supabaseJwtAudienceRaw,
    allowedOrigins,
    requestBodyLimitBytes: parseIntEnv("REQUEST_BODY_LIMIT_MB", 10) *
      1024 * 1024,
    logLevel: parseLogLevel(getEnv("LOG_LEVEL")),
    enableAuth,
    usedLegacyEnv,
  };
}
