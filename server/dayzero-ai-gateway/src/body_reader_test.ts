import { assertEquals } from "@std/assert";
import { readLimitedJsonBody } from "./body_reader.ts";
import { createTestConfig } from "./test_helpers.ts";

const corsHeaders = { "X-Request-Id": "req-br-1" };

function buildRequestWithBody(bodyText: string): Request {
  return new Request("http://localhost/assistant-turn-v2", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: bodyText,
  });
}

Deno.test("readLimitedJsonBody accepts valid JSON object", async () => {
  const result = await readLimitedJsonBody(
    buildRequestWithBody('{"date":"2026-06-26","userText":"hello"}'),
    createTestConfig(),
    corsHeaders,
  );
  assertEquals(result.ok, true);
  if (!result.ok) return;
  assertEquals(result.body.date, "2026-06-26");
});

Deno.test("readLimitedJsonBody rejects null body", async () => {
  const result = await readLimitedJsonBody(
    buildRequestWithBody("null"),
    createTestConfig(),
    corsHeaders,
  );
  assertEquals(result.ok, false);
  if (result.ok) return;
  assertEquals(result.response.status, 400);
  const body = await result.response.json();
  assertEquals(body.errorCode, "INVALID_BODY_TYPE");
});

Deno.test("readLimitedJsonBody rejects array body", async () => {
  const result = await readLimitedJsonBody(
    buildRequestWithBody('["not","object"]'),
    createTestConfig(),
    corsHeaders,
  );
  assertEquals(result.ok, false);
  if (result.ok) return;
  assertEquals(result.response.status, 400);
  const body = await result.response.json();
  assertEquals(body.errorCode, "INVALID_BODY_TYPE");
});

Deno.test("readLimitedJsonBody rejects string body", async () => {
  const result = await readLimitedJsonBody(
    buildRequestWithBody('"string-body"'),
    createTestConfig(),
    corsHeaders,
  );
  assertEquals(result.ok, false);
  if (result.ok) return;
  assertEquals(result.response.status, 400);
});

Deno.test("readLimitedJsonBody rejects number body", async () => {
  const result = await readLimitedJsonBody(
    buildRequestWithBody("123"),
    createTestConfig(),
    corsHeaders,
  );
  assertEquals(result.ok, false);
  if (result.ok) return;
  assertEquals(result.response.status, 400);
});

Deno.test("readLimitedJsonBody rejects malformed JSON", async () => {
  const result = await readLimitedJsonBody(
    buildRequestWithBody("not json"),
    createTestConfig(),
    corsHeaders,
  );
  assertEquals(result.ok, false);
  if (result.ok) return;
  assertEquals(result.response.status, 400);
});
