import { assertEquals, assertThrows } from "@std/assert";
import { loadConfig } from "./config.ts";

function withEnv(
  env: Record<string, string | undefined>,
  fn: () => void,
): void {
  const previous: Record<string, string | undefined> = {};
  for (const key of Object.keys(env)) {
    previous[key] = Deno.env.get(key);
    if (env[key] === undefined) {
      Deno.env.delete(key);
    } else {
      Deno.env.set(key, env[key]!);
    }
  }
  try {
    fn();
  } finally {
    for (const key of Object.keys(env)) {
      if (previous[key] === undefined) {
        Deno.env.delete(key);
      } else {
        Deno.env.set(key, previous[key]!);
      }
    }
  }
}

const BASE_ENV = {
  KIMI_API_URL: "https://api.moonshot.cn/v1/chat/completions",
  KIMI_API_KEY: "test-key",
  KIMI_MODEL: "kimi-k2.6",
  SUPABASE_JWKS_URL: "https://new-project.supabase.co/auth/v1/.well-known/jwks.json",
  SUPABASE_ISSUER: "https://new-project.supabase.co/auth/v1",
  SUPABASE_AUDIENCE: "authenticated",
};

Deno.test("config: production with ENABLE_AUTH=false fails fast", () => {
  withEnv({ ...BASE_ENV, APP_ENV: "production", ENABLE_AUTH: "false" }, () => {
    assertThrows(loadConfig, Error, "ENABLE_AUTH=false is not allowed in production");
  });
});

Deno.test("config: development allows ENABLE_AUTH=false", () => {
  withEnv({ ...BASE_ENV, APP_ENV: "development", ENABLE_AUTH: "false" }, () => {
    const config = loadConfig();
    assertEquals(config.appEnv, "development");
    assertEquals(config.enableAuth, false);
  });
});

Deno.test("config: test env allows ENABLE_AUTH=false", () => {
  withEnv({ ...BASE_ENV, APP_ENV: "test", ENABLE_AUTH: "false" }, () => {
    const config = loadConfig();
    assertEquals(config.appEnv, "test");
    assertEquals(config.enableAuth, false);
  });
});

Deno.test("config: new JWKS/issuer/audience take priority over legacy", () => {
  withEnv({
    ...BASE_ENV,
    APP_ENV: "production",
    ENABLE_AUTH: "true",
    SUPABASE_URL: "https://legacy-project.supabase.co",
    SUPABASE_JWT_AUDIENCE: "legacy-aud",
  }, () => {
    const config = loadConfig();
    assertEquals(config.supabaseJwksUrl, BASE_ENV.SUPABASE_JWKS_URL);
    assertEquals(config.supabaseIssuer, BASE_ENV.SUPABASE_ISSUER);
    assertEquals(config.supabaseAudience, BASE_ENV.SUPABASE_AUDIENCE);
    assertEquals(config.usedLegacyEnv, false);
  });
});

Deno.test("config: legacy SUPABASE_URL and SUPABASE_JWT_AUDIENCE are used when new vars absent", () => {
  withEnv({
    KIMI_API_URL: BASE_ENV.KIMI_API_URL,
    KIMI_API_KEY: BASE_ENV.KIMI_API_KEY,
    KIMI_MODEL: BASE_ENV.KIMI_MODEL,
    APP_ENV: "production",
    ENABLE_AUTH: "true",
    SUPABASE_URL: "https://legacy-project.supabase.co",
    SUPABASE_JWT_AUDIENCE: "legacy-aud",
  }, () => {
    const config = loadConfig();
    assertEquals(
      config.supabaseJwksUrl,
      "https://legacy-project.supabase.co/auth/v1/.well-known/jwks.json",
    );
    assertEquals(config.supabaseIssuer, "https://legacy-project.supabase.co/auth/v1");
    assertEquals(config.supabaseAudience, "legacy-aud");
    assertEquals(config.usedLegacyEnv, true);
  });
});

Deno.test("config: auth enabled without Supabase config throws", () => {
  withEnv({
    KIMI_API_URL: BASE_ENV.KIMI_API_URL,
    KIMI_API_KEY: BASE_ENV.KIMI_API_KEY,
    KIMI_MODEL: BASE_ENV.KIMI_MODEL,
    APP_ENV: "production",
    ENABLE_AUTH: "true",
  }, () => {
    assertThrows(loadConfig, Error, "Supabase JWKS/issuer/audience are not configured");
  });
});

Deno.test("config: defaults to production when APP_ENV is absent", () => {
  withEnv({ ...BASE_ENV, ENABLE_AUTH: "true" }, () => {
    const config = loadConfig();
    assertEquals(config.appEnv, "production");
  });
});
