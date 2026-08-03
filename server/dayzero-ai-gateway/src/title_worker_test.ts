import { assertEquals, assertStringIncludes } from "@std/assert";
import { sanitizeGeneratedTitle, TITLE_SYSTEM_PROMPT } from "./title_worker.ts";

Deno.test("title sanitizer removes wrappers and uses only first line", () => {
  assertEquals(sanitizeGeneratedTitle("标题：《午餐牛肉面记录》。\n解释"), "午餐牛肉面记录");
  assertEquals(sanitizeGeneratedTitle('"Spoken English Practice"'), "Spoken English Practice");
});

Deno.test("title sanitizer rejects blank markdown and overlong output", () => {
  assertEquals(sanitizeGeneratedTitle("  \n"), null);
  assertEquals(sanitizeGeneratedTitle("## invalid"), null);
  assertEquals(sanitizeGeneratedTitle("x".repeat(49)), null);
});

Deno.test("title sanitizer rejects common sensitive identifiers", () => {
  assertEquals(sanitizeGeneratedTitle("联系 alice@example.com"), null);
  assertEquals(sanitizeGeneratedTitle("拨打 138-0013-8000"), null);
  assertEquals(sanitizeGeneratedTitle("身份证 110101199001011234"), null);
});

Deno.test("title prompt is isolated and forbids sensitive-title leakage", () => {
  assertStringIncludes(TITLE_SYSTEM_PROMPT, "第一条消息");
  assertStringIncludes(TITLE_SYSTEM_PROMPT, "不要使用换行");
  assertStringIncludes(TITLE_SYSTEM_PROMPT, "手机号");
});
