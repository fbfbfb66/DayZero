import { assertEquals } from "@std/assert";
import { handleReady } from "./ready.ts";
import { createTestConfig } from "../test_helpers.ts";

function buildGet(path: string): Request {
  return new Request(`http://localhost${path}`, { method: "GET" });
}

Deno.test("ready returns 200 with valid config", async () => {
  const response = handleReady(buildGet("/ready"), createTestConfig(), "req-ready-1");

  assertEquals(response.status, 200);
  const body = await response.json();
  assertEquals(body.status, "ready");
  assertEquals(response.headers.get("X-Request-Id"), "req-ready-1");
});

Deno.test("ready returns 200 using normalized fields derived from legacy variables and reports usedLegacyEnv", async () => {
  // ready.ts only sees the normalized config that loadConfig() produced from legacy vars.
  const response = handleReady(
    buildGet("/ready"),
    createTestConfig({
      enableAuth: true,
      supabaseJwksUrl: "https://legacy-project.supabase.co/auth/v1/.well-known/jwks.json",
      supabaseIssuer: "https://legacy-project.supabase.co/auth/v1",
      supabaseAudience: "authenticated",
      supabaseUrl: "https://legacy-project.supabase.co",
      supabaseJwtAudience: "authenticated",
      usedLegacyEnv: true,
    }),
    "req-ready-legacy",
  );

  assertEquals(response.status, 200);
  const body = await response.json();
  assertEquals(body.status, "ready");
  assertEquals(body.usedLegacyEnv, true);
});

Deno.test("ready returns 503 when Supabase JWKS URL is missing", async () => {
  const response = handleReady(
    buildGet("/ready"),
    createTestConfig({ enableAuth: true, supabaseJwksUrl: "" }),
    "req-ready-2",
  );

  assertEquals(response.status, 503);
  const body = await response.json();
  assertEquals(body.errorCode, "INVALID_SUPABASE_JWKS_URL");
  assertEquals(response.headers.get("X-Request-Id"), "req-ready-2");
});

Deno.test("ready returns 503 when Supabase JWKS URL is malformed", async () => {
  const response = handleReady(
    buildGet("/ready"),
    createTestConfig({ enableAuth: true, supabaseJwksUrl: "not-a-url" }),
    "req-ready-3",
  );

  assertEquals(response.status, 503);
  const body = await response.json();
  assertEquals(body.errorCode, "INVALID_SUPABASE_JWKS_URL");
});

Deno.test("ready returns 503 when Supabase issuer is missing", async () => {
  const response = handleReady(
    buildGet("/ready"),
    createTestConfig({ enableAuth: true, supabaseIssuer: "" }),
    "req-ready-4",
  );

  assertEquals(response.status, 503);
  const body = await response.json();
  assertEquals(body.errorCode, "INVALID_SUPABASE_ISSUER");
});

Deno.test("ready returns 503 when Supabase issuer is malformed", async () => {
  const response = handleReady(
    buildGet("/ready"),
    createTestConfig({ enableAuth: true, supabaseIssuer: "not-a-url" }),
    "req-ready-5",
  );

  assertEquals(response.status, 503);
  const body = await response.json();
  assertEquals(body.errorCode, "INVALID_SUPABASE_ISSUER");
});

Deno.test("ready returns 503 when Supabase audience is missing", async () => {
  const response = handleReady(
    buildGet("/ready"),
    createTestConfig({ enableAuth: true, supabaseAudience: "" }),
    "req-ready-6",
  );

  assertEquals(response.status, 503);
  const body = await response.json();
  assertEquals(body.errorCode, "MISSING_SUPABASE_AUDIENCE");
});

Deno.test("ready returns 503 when Kimi API URL is missing", async () => {
  const response = handleReady(
    buildGet("/ready"),
    createTestConfig({ kimiApiUrl: "" }),
    "req-ready-7",
  );

  assertEquals(response.status, 503);
  const body = await response.json();
  assertEquals(body.errorCode, "INVALID_KIMI_API_URL");
});

Deno.test("ready response does not contain config values", async () => {
  const response = handleReady(buildGet("/ready"), createTestConfig(), "req-ready-8");
  const text = await response.text();

  assertEquals(text.includes("test-kimi-key"), false);
  assertEquals(text.includes("moonshot"), false);
  assertEquals(text.includes("supabase"), false);
});
