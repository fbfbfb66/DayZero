import { assertEquals, assertStringIncludes } from "@std/assert";
import {
  buildTitleUserContent,
  sanitizeGeneratedTitle,
  TITLE_SYSTEM_PROMPT,
} from "./title_worker.ts";

const TEST_CONFIG = {
  supabaseUrl: "https://example.supabase.co",
  serviceRoleKey: "service-key",
  kimiApiUrl: "https://api.moonshot.cn/v1/chat/completions",
  kimiApiKey: "kimi-key",
  titleModel: "kimi-k2.6",
  titleTimeoutMs: 12_000,
  concurrency: 2,
  maxAttempts: 5,
  retryBaseDelayMs: 30_000,
  pollIntervalMs: 2_000,
  leaseSeconds: 120,
  workerId: "test-worker",
};

const TEXT_JOB = {
  id: "job-1",
  user_id: "user-1",
  conversation_id: "conv-1",
  first_user_message_id: "msg-1",
  first_user_text: "早餐吃了包子",
  first_user_media_count: 0,
  attempt_count: 1,
};

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

Deno.test("buildTitleUserContent returns plain text for text-only jobs", async () => {
  const content = await buildTitleUserContent(TEST_CONFIG, TEXT_JOB);
  assertEquals(content, "早餐吃了包子");
});

Deno.test("buildTitleUserContent attaches downloaded images for media jobs", async () => {
  const originalFetch = globalThis.fetch;
  const calls: string[] = [];
  globalThis.fetch = (input: Request | URL | string): Promise<Response> => {
    const url = String(input);
    calls.push(url);
    if (url.includes("/rest/v1/media_assets")) {
      return Promise.resolve(
        new Response(
          JSON.stringify([{
            thumbnail_object_path: "user-1/media-1/thumb.jpg",
            master_object_path: "user-1/media-1/master.jpg",
          }]),
          { status: 200 },
        ),
      );
    }
    if (url.includes("/storage/v1/object/media-assets/")) {
      return Promise.resolve(new Response(new Uint8Array([1, 2, 3]), { status: 200 }));
    }
    return Promise.resolve(new Response("unexpected", { status: 500 }));
  };
  try {
    const content = await buildTitleUserContent(TEST_CONFIG, {
      ...TEXT_JOB,
      first_user_text: "",
      first_user_media_count: 1,
    }) as Array<Record<string, unknown>>;
    assertEquals(content.length, 2);
    assertEquals(content[0], { type: "text", text: "（用户只发送了图片，没有文字）" });
    const imagePart = content[1] as { type: string; image_url: { url: string } };
    assertEquals(imagePart.type, "image_url");
    assertStringIncludes(imagePart.image_url.url, "data:image/jpeg;base64,");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

Deno.test("buildTitleUserContent throws retryable MEDIA_NOT_READY when upload lags", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = (): Promise<Response> => Promise.resolve(new Response("[]", { status: 200 }));
  try {
    let error: unknown = null;
    try {
      await buildTitleUserContent(TEST_CONFIG, { ...TEXT_JOB, first_user_media_count: 2 });
    } catch (e) {
      error = e;
    }
    assertEquals((error as { code?: string }).code, "MEDIA_NOT_READY");
    assertEquals((error as { retryable?: boolean }).retryable, true);
  } finally {
    globalThis.fetch = originalFetch;
  }
});
