import { assertEquals } from "@std/assert";
import { digestUserId, sanitizeContext, StructuredLogger } from "./logger.ts";

Deno.test("sanitizeContext: keeps whitelist fields", () => {
  const sanitized = sanitizeContext({
    requestId: "req-1",
    userIdDigest: "u_abc123",
    routePath: "/api/ai/assistant-turn-v2",
    method: "POST",
    status: 200,
    httpStatus: 502,
    errorCode: "UPSTREAM_UNAVAILABLE",
    retryable: true,
    totalMs: 123,
  });
  assertEquals(sanitized.requestId, "req-1");
  assertEquals(sanitized.userIdDigest, "u_abc123");
  assertEquals(sanitized.routePath, "/api/ai/assistant-turn-v2");
  assertEquals(sanitized.method, "POST");
  assertEquals(sanitized.status, 200);
  assertEquals(sanitized.httpStatus, 502);
  assertEquals(sanitized.errorCode, "UPSTREAM_UNAVAILABLE");
  assertEquals(sanitized.retryable, true);
  assertEquals(sanitized.totalMs, 123);
});

Deno.test("sanitizeContext: drops unknown fields", () => {
  const sanitized = sanitizeContext({
    requestId: "req-1",
    unknownField: "should be dropped",
    anotherUnknown: 123,
  });
  assertEquals(Object.keys(sanitized).sort(), ["requestId"]);
});

Deno.test("sanitizeContext: drops pendingDraft", () => {
  const sanitized = sanitizeContext({
    requestId: "req-1",
    pendingDraft: { food: "sensitive draft" },
  });
  assertEquals(sanitized.pendingDraft, undefined);
});

Deno.test("sanitizeContext: drops detail", () => {
  const sanitized = sanitizeContext({
    requestId: "req-1",
    detail: "raw exception detail",
  });
  assertEquals(sanitized.detail, undefined);
});

Deno.test("sanitizeContext: drops message", () => {
  const sanitized = sanitizeContext({
    requestId: "req-1",
    message: "raw error message",
  });
  assertEquals(sanitized.message, undefined);
});

Deno.test("sanitizeContext: drops error object", () => {
  const sanitized = sanitizeContext({
    requestId: "req-1",
    error: new Error("secret"),
  });
  assertEquals(sanitized.error, undefined);
});

Deno.test("sanitizeContext: drops cause", () => {
  const sanitized = sanitizeContext({
    requestId: "req-1",
    cause: new Error("nested cause"),
  });
  assertEquals(sanitized.cause, undefined);
});

Deno.test("sanitizeContext: drops stack", () => {
  const sanitized = sanitizeContext({
    requestId: "req-1",
    stack: "at secretFunction (/app/src/secret.ts:1:1)",
  });
  assertEquals(sanitized.stack, undefined);
});

Deno.test("sanitizeContext: drops imagePath", () => {
  const sanitized = sanitizeContext({
    requestId: "req-1",
    imagePath: "/data/private/photo.jpg",
  });
  assertEquals(sanitized.imagePath, undefined);
});

Deno.test("sanitizeContext: drops nested object sensitive values", () => {
  const sanitized = sanitizeContext({
    requestId: "req-1",
    nested: { secret: "value" },
  });
  assertEquals(sanitized.nested, undefined);
});

Deno.test("sanitizeContext: drops arrays with exception objects", () => {
  const sanitized = sanitizeContext({
    requestId: "req-1",
    errors: [new Error("secret"), "another"],
  });
  assertEquals(sanitized.errors, undefined);
});

Deno.test("sanitizeContext: drops base64 and data URL values", () => {
  const sanitized = sanitizeContext({
    requestId: "req-1",
    base64: "A".repeat(120),
    dataUrl: "data:image/jpeg;base64,dGVzdA==",
  });
  assertEquals(sanitized.base64, undefined);
  assertEquals(sanitized.dataUrl, undefined);
});

Deno.test("sanitizeContext: drops userText", () => {
  const sanitized = sanitizeContext({
    requestId: "req-1",
    userText: "my secret message",
  });
  assertEquals(sanitized.userText, undefined);
});

Deno.test("sanitizeContext: drops prompt", () => {
  const sanitized = sanitizeContext({
    requestId: "req-1",
    prompt: "system prompt secret",
  });
  assertEquals(sanitized.prompt, undefined);
});

Deno.test("sanitizeContext: drops authorization and JWT values", () => {
  const sanitized = sanitizeContext({
    requestId: "req-1",
    authorization: "Bearer eyJhbGciOiJFUzI1NiJ9",
    jwt: "eyJhbGciOiJFUzI1NiJ9.eyJzdWIiOiIxMjMifQ",
  });
  assertEquals(sanitized.authorization, undefined);
  assertEquals(sanitized.jwt, undefined);
});

Deno.test("sanitizeContext: drops raw sub", () => {
  const sanitized = sanitizeContext({
    requestId: "req-1",
    sub: "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  });
  assertEquals(sanitized.sub, undefined);
});

Deno.test("logger: emits JSON with allowed fields only", () => {
  const logs: string[] = [];
  const originalInfo = console.info;
  console.info = (message: string) => logs.push(message);

  try {
    const logger = new StructuredLogger("test", "info");
    logger.info("test message", { requestId: "req-1", foo: "bar" });
    assertEquals(logs.length, 1);
    const entry = JSON.parse(logs[0]);
    assertEquals(entry.level, "info");
    assertEquals(entry.message, "test message");
    assertEquals(entry.requestId, "req-1");
    assertEquals(entry.foo, undefined);
    assertEquals(entry.service, "test");
  } finally {
    console.info = originalInfo;
  }
});

Deno.test("logger: does not emit raw sub, Bearer tokens, or userText", () => {
  const logs: string[] = [];
  const originalWarn = console.warn;
  console.warn = (message: string) => logs.push(message);

  try {
    const logger = new StructuredLogger("test", "warn");
    logger.warn("auth failed", {
      requestId: "req-1",
      sub: "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      authorization: "Bearer eyJhbGciOiJFUzI1NiJ9.eyJzdWIiOiIxMjMifQ",
      userText: "my secret message",
      base64: "A".repeat(120),
    });
    assertEquals(logs.length, 1);
    const entry = JSON.parse(logs[0]);
    assertEquals(entry.sub, undefined);
    assertEquals(entry.authorization, undefined);
    assertEquals(entry.userText, undefined);
    assertEquals(entry.base64, undefined);
    const serialized = JSON.stringify(entry);
    assertEquals(serialized.includes("Bearer "), false);
    assertEquals(serialized.includes("a1b2c3d4-e5f6-7890-abcd-ef1234567890"), false);
    assertEquals(serialized.includes("my secret message"), false);
  } finally {
    console.warn = originalWarn;
  }
});

Deno.test("digestUserId: returns stable prefixed hex", async () => {
  const a = await digestUserId("a1b2c3d4-e5f6-7890-abcd-ef1234567890", 16);
  const b = await digestUserId("a1b2c3d4-e5f6-7890-abcd-ef1234567890", 16);
  assertEquals(a, b);
  assertEquals(a.startsWith("u_"), true);
  assertEquals(a.length, "u_".length + 16);
});

Deno.test("digestUserId: different inputs produce different digests", async () => {
  const a = await digestUserId("user-a", 16);
  const b = await digestUserId("user-b", 16);
  assertEquals(a !== b, true);
});
