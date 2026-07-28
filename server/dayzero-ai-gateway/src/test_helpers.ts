import type { GatewayConfig } from "./config.ts";

const TEST_SUPABASE_URL = "https://test-project.supabase.co";

export function createTestConfig(overrides: Partial<GatewayConfig> = {}): GatewayConfig {
  return {
    appEnv: "test",
    port: 8080,
    kimiApiUrl: "https://api.moonshot.cn/v1/chat/completions",
    kimiApiKey: "test-kimi-key",
    kimiModel: "kimi-k2.6",
    kimiMaxTokens: 1500,
    kimiTemperature: 0.6,
    supabaseJwksUrl: `${TEST_SUPABASE_URL}/auth/v1/.well-known/jwks.json`,
    supabaseIssuer: `${TEST_SUPABASE_URL}/auth/v1`,
    supabaseAudience: "authenticated",
    supabaseUrl: TEST_SUPABASE_URL,
    supabaseJwtAudience: "authenticated",
    allowedOrigins: ["*"],
    requestBodyLimitBytes: 10 * 1024 * 1024,
    logLevel: "error",
    enableAuth: false,
    usedLegacyEnv: false,
    ...overrides,
  };
}

export function createLoggerStub() {
  return {
    debug: () => {},
    info: () => {},
    warn: () => {},
    error: () => {},
  };
}

export function buildRequest(
  method: string,
  path: string,
  body?: unknown,
  headers?: Record<string, string>,
): Request {
  const init: RequestInit = { method };
  if (body !== undefined) {
    init.body = JSON.stringify(body);
    init.headers = {
      "Content-Type": "application/json",
      ...headers,
    };
  } else if (headers) {
    init.headers = headers;
  }
  return new Request(`http://localhost${path}`, init);
}
