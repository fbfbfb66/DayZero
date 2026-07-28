import { assertEquals } from "@std/assert";
import { generateRequestId, resolveRequestId } from "./request_id.ts";

Deno.test("generateRequestId returns non-empty string", () => {
  const id = generateRequestId();
  assertEquals(typeof id, "string");
  assertEquals(id.length > 0, true);
});

Deno.test("resolveRequestId reuses valid inbound id", () => {
  assertEquals(resolveRequestId("valid-req-123"), "valid-req-123");
  assertEquals(resolveRequestId("abc:def_ghi.123"), "abc:def_ghi.123");
});

Deno.test("resolveRequestId generates new id when inbound is missing", () => {
  const id = resolveRequestId(null);
  assertEquals(typeof id, "string");
  assertEquals(id.length > 0, true);
});

Deno.test("resolveRequestId generates new id when inbound is too short", () => {
  const id = resolveRequestId("short");
  assertEquals(id !== "short", true);
});

Deno.test("resolveRequestId generates new id when inbound is too long", () => {
  const long = "a".repeat(200);
  const id = resolveRequestId(long);
  assertEquals(id !== long, true);
});

Deno.test("resolveRequestId rejects ids with spaces", () => {
  const id = resolveRequestId("bad id with spaces");
  assertEquals(id !== "bad id with spaces", true);
});

Deno.test("resolveRequestId rejects ids with newlines", () => {
  const id = resolveRequestId("bad\nid");
  assertEquals(id !== "bad\nid", true);
});

Deno.test("resolveRequestId rejects ids with unicode", () => {
  const id = resolveRequestId("bäd-unicode-123");
  assertEquals(id !== "bäd-unicode-123", true);
});

Deno.test("resolveRequestId rejects control characters", () => {
  const id = resolveRequestId("bad\x00id");
  assertEquals(id !== "bad\x00id", true);
});

Deno.test("resolveRequestId rejects ids with unsafe punctuation", () => {
  const id = resolveRequestId("bad<id>");
  assertEquals(id !== "bad<id>", true);
});
