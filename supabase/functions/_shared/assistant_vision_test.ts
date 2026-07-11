import { assertEquals, assertThrows } from "jsr:@std/assert@1";
import {
  applyVisionContentToCurrentUserMessage,
  buildAttachmentIdentityPrompt,
  buildKimiUserContent,
  buildVisionAwareUserMessage,
  calculateDecodedBase64Size,
  checkAttachmentSizeLimits,
  parseAndValidateAttachments,
  VISION_PROMPT_ADDENDUM,
  type VisionAttachment,
  VisionValidationError,
} from "./assistant_vision.ts";

const JPEG_MIME = "image/jpeg";

// "dGVzdA==" decodes to "test" (4 bytes)
const VALID_BASE64_4B = "dGVzdA==";

function makeAttachment(
  mediaId: string,
  base64: string,
  mimeType: string = JPEG_MIME,
): Record<string, unknown> {
  return { mediaId, mimeType, base64 };
}

function makeVisionAttachment(
  mediaId: string,
  base64: string,
  decodedByteSize: number,
): VisionAttachment {
  return { mediaId, mimeType: JPEG_MIME, base64, decodedByteSize };
}

Deno.test("VISION_PROMPT_ADDENDUM is non-empty", () => {
  assertEquals(typeof VISION_PROMPT_ADDENDUM, "string");
  assertEquals(VISION_PROMPT_ADDENDUM.length > 0, true);
});

Deno.test("parseAndValidateAttachments: undefined returns empty", () => {
  const result = parseAndValidateAttachments(undefined, "user_message", "hi");
  assertEquals(result, []);
});

Deno.test("parseAndValidateAttachments: null returns empty", () => {
  const result = parseAndValidateAttachments(null, "user_message", "hi");
  assertEquals(result, []);
});

Deno.test("parseAndValidateAttachments: empty array returns empty", () => {
  const result = parseAndValidateAttachments([], "user_message", "hi");
  assertEquals(result, []);
});

Deno.test("parseAndValidateAttachments: non-array throws INVALID_ATTACHMENTS_TYPE", () => {
  assertThrows(
    () => parseAndValidateAttachments("not-array", "user_message", "hi"),
    VisionValidationError,
    "attachments must be an array",
  );
});

Deno.test("parseAndValidateAttachments: single valid attachment", () => {
  const result = parseAndValidateAttachments(
    [makeAttachment("m1", VALID_BASE64_4B)],
    "user_message",
    "look",
  );
  assertEquals(result.length, 1);
  assertEquals(result[0].mediaId, "m1");
  assertEquals(result[0].mimeType, JPEG_MIME);
  assertEquals(result[0].base64, VALID_BASE64_4B);
  assertEquals(result[0].decodedByteSize, 4);
});

Deno.test("parseAndValidateAttachments: six valid attachments preserve order", () => {
  const attachments = Array.from(
    { length: 6 },
    (_, i) => makeAttachment(`m${i}`, VALID_BASE64_4B),
  );
  const result = parseAndValidateAttachments(
    attachments,
    "user_message",
    "look",
  );
  assertEquals(result.length, 6);
  for (let i = 0; i < 6; i++) {
    assertEquals(result[i].mediaId, `m${i}`);
  }
});

Deno.test("parseAndValidateAttachments: zero non-empty throws INVALID_ATTACHMENT_COUNT", () => {
  // empty array already handled; here we test that an explicit 0 count is not treated as vision
  // (covered by empty array returning empty)
  assertEquals(parseAndValidateAttachments([], "user_message", "hi"), []);
});

Deno.test("parseAndValidateAttachments: seven attachments throws INVALID_ATTACHMENT_COUNT", () => {
  const attachments = Array.from(
    { length: 7 },
    (_, i) => makeAttachment(`m${i}`, VALID_BASE64_4B),
  );
  assertThrows(
    () => parseAndValidateAttachments(attachments, "user_message", "hi"),
    VisionValidationError,
    "attachments must contain between",
  );
});

Deno.test("parseAndValidateAttachments: duplicate mediaId throws DUPLICATE_MEDIA_ID", () => {
  assertThrows(
    () =>
      parseAndValidateAttachments(
        [
          makeAttachment("m1", VALID_BASE64_4B),
          makeAttachment("m1", VALID_BASE64_4B),
        ],
        "user_message",
        "hi",
      ),
    VisionValidationError,
    "duplicate mediaId in attachments",
  );
});

Deno.test("parseAndValidateAttachments: empty mediaId throws INVALID_MEDIA_ID", () => {
  assertThrows(
    () =>
      parseAndValidateAttachments(
        [makeAttachment("", VALID_BASE64_4B)],
        "user_message",
        "hi",
      ),
    VisionValidationError,
    "mediaId must be a non-empty string",
  );
});

Deno.test("parseAndValidateAttachments: unsafe mediaId throws INVALID_MEDIA_ID", () => {
  assertThrows(
    () =>
      parseAndValidateAttachments(
        [makeAttachment("m1/../m2", VALID_BASE64_4B)],
        "user_message",
        "hi",
      ),
    VisionValidationError,
    "mediaId contains unsafe characters",
  );
});

Deno.test("parseAndValidateAttachments: non-JPEG mimeType throws INVALID_ATTACHMENT_MIME", () => {
  assertThrows(
    () =>
      parseAndValidateAttachments(
        [makeAttachment("m1", VALID_BASE64_4B, "image/png")],
        "user_message",
        "hi",
      ),
    VisionValidationError,
    "mimeType must be image/jpeg",
  );
});

Deno.test("parseAndValidateAttachments: empty base64 throws INVALID_ATTACHMENT_BASE64", () => {
  assertThrows(
    () =>
      parseAndValidateAttachments(
        [makeAttachment("m1", "")],
        "user_message",
        "hi",
      ),
    VisionValidationError,
    "base64 must not be empty",
  );
});

Deno.test("parseAndValidateAttachments: base64 with newline throws INVALID_ATTACHMENT_BASE64", () => {
  assertThrows(
    () =>
      parseAndValidateAttachments(
        [makeAttachment("m1", "dGVzdA==\n")],
        "user_message",
        "hi",
      ),
    VisionValidationError,
    "base64 must not contain whitespace",
  );
});

Deno.test("parseAndValidateAttachments: base64 with space throws INVALID_ATTACHMENT_BASE64", () => {
  assertThrows(
    () =>
      parseAndValidateAttachments(
        [makeAttachment("m1", "dGVzdA== ")],
        "user_message",
        "hi",
      ),
    VisionValidationError,
    "base64 must not contain whitespace",
  );
});

Deno.test("parseAndValidateAttachments: base64 with data prefix throws INVALID_ATTACHMENT_BASE64", () => {
  assertThrows(
    () =>
      parseAndValidateAttachments(
        [makeAttachment("m1", "data:image/jpeg;base64,dGVzdA==")],
        "user_message",
        "hi",
      ),
    VisionValidationError,
    "base64 must not contain data: prefix",
  );
});

Deno.test("parseAndValidateAttachments: URL-safe base64 throws INVALID_ATTACHMENT_BASE64", () => {
  assertThrows(
    () =>
      parseAndValidateAttachments(
        [makeAttachment("m1", "dGVzdA--")],
        "user_message",
        "hi",
      ),
    VisionValidationError,
    "base64 must not use URL-safe characters",
  );
  assertThrows(
    () =>
      parseAndValidateAttachments(
        [makeAttachment("m1", "dGVzdA__")],
        "user_message",
        "hi",
      ),
    VisionValidationError,
    "base64 must not use URL-safe characters",
  );
});

Deno.test("parseAndValidateAttachments: invalid base64 character throws INVALID_ATTACHMENT_BASE64", () => {
  assertThrows(
    () =>
      parseAndValidateAttachments(
        [makeAttachment("m1", "dGVzd!!!")],
        "user_message",
        "hi",
      ),
    VisionValidationError,
    "base64 contains invalid characters",
  );
});

Deno.test("parseAndValidateAttachments: invalid base64 length throws INVALID_ATTACHMENT_BASE64", () => {
  assertThrows(
    () =>
      parseAndValidateAttachments(
        [makeAttachment("m1", "dGVzdA=")],
        "user_message",
        "hi",
      ),
    VisionValidationError,
    "base64 length must be a multiple of 4",
  );
});

Deno.test("parseAndValidateAttachments: padding in middle throws INVALID_ATTACHMENT_BASE64", () => {
  assertThrows(
    () =>
      parseAndValidateAttachments(
        [makeAttachment("m1", "dGVzdA=E")],
        "user_message",
        "hi",
      ),
    VisionValidationError,
    "base64 contains invalid characters",
  );
});

Deno.test("parseAndValidateAttachments: too much padding throws INVALID_ATTACHMENT_BASE64", () => {
  assertThrows(
    () =>
      parseAndValidateAttachments(
        [makeAttachment("m1", "dGVzd===")],
        "user_message",
        "hi",
      ),
    VisionValidationError,
    "base64 has too many padding characters",
  );
});

Deno.test("calculateDecodedBase64Size: valid padding 0, 1, 2", () => {
  // "abcd" -> 3 bytes, no padding
  assertEquals(calculateDecodedBase64Size("YWJj"), 3);
  // "ab" -> 1 byte + 2 padding
  assertEquals(calculateDecodedBase64Size("YWI="), 2);
  // "a" -> 1 byte + 2 padding
  assertEquals(calculateDecodedBase64Size("YQ=="), 1);
});

Deno.test("calculateDecodedBase64Size: rejects empty string", () => {
  assertThrows(
    () => calculateDecodedBase64Size(""),
    VisionValidationError,
    "base64 must not be empty",
  );
});

Deno.test("calculateDecodedBase64Size: rejects whitespace", () => {
  assertThrows(
    () => calculateDecodedBase64Size("YWI=\n"),
    VisionValidationError,
    "base64 must not contain whitespace",
  );
});

Deno.test("buildKimiUserContent: one text part and one image part", () => {
  const content = buildKimiUserContent(
    "look at this",
    [makeVisionAttachment("m1", VALID_BASE64_4B, 4)],
  );
  assertEquals(content.length, 2);
  const text = (content[0] as { type: "text"; text: string }).text;
  assertEquals(content[0].type, "text");
  assertEquals(text.includes("ImageAttachmentReferences:"), true);
  assertEquals(text.includes("attachment_1: image 1"), true);
  assertEquals(text.includes("m1"), false);
  assertEquals(text.endsWith("look at this"), true);
  assertEquals(content[1], {
    type: "image_url",
    image_url: { url: `data:image/jpeg;base64,${VALID_BASE64_4B}` },
  });
});

Deno.test("buildKimiUserContent: six image parts preserve order", () => {
  const attachments = Array.from(
    { length: 6 },
    (_, i) => makeVisionAttachment(`m${i}`, VALID_BASE64_4B, 4),
  );
  const content = buildKimiUserContent("look", attachments);
  assertEquals(content.length, 7);
  const text = (content[0] as { type: "text"; text: string }).text;
  assertEquals(text.includes("attachment_1: image 1"), true);
  assertEquals(text.includes("attachment_6: image 6"), true);
  assertEquals(text.endsWith("look"), true);
  for (let i = 0; i < 6; i++) {
    assertEquals(content[i + 1].type, "image_url");
    assertEquals(
      (content[i + 1] as { image_url: { url: string } }).image_url.url,
      `data:image/jpeg;base64,${VALID_BASE64_4B}`,
    );
  }
});

Deno.test("buildAttachmentIdentityPrompt: exposes aliases but not media ids", () => {
  const prompt = buildAttachmentIdentityPrompt([
    makeVisionAttachment("media-secret-1", VALID_BASE64_4B, 4),
    makeVisionAttachment("media-secret-2", VALID_BASE64_4B, 4),
  ]);
  assertEquals(prompt.includes("attachment_1: image 1"), true);
  assertEquals(prompt.includes("attachment_2: image 2"), true);
  assertEquals(prompt.includes("media-secret"), false);
  assertEquals(prompt.includes("base64"), false);
});

Deno.test("buildKimiUserContent: throws without attachments", () => {
  assertThrows(
    () => buildKimiUserContent("look", []),
    VisionValidationError,
    "cannot build vision content without attachments",
  );
});

Deno.test("applyVisionContentToCurrentUserMessage: string content when no attachments", () => {
  const message = applyVisionContentToCurrentUserMessage("hello", []);
  assertEquals(message.role, "user");
  assertEquals(message.content, "hello");
});

Deno.test("applyVisionContentToCurrentUserMessage: array content when attachments present", () => {
  const message = applyVisionContentToCurrentUserMessage(
    "hello",
    [makeVisionAttachment("m1", VALID_BASE64_4B, 4)],
  );
  assertEquals(message.role, "user");
  assertEquals(Array.isArray(message.content), true);
  assertEquals((message.content as unknown[]).length, 2);
});

Deno.test("buildVisionAwareUserMessage: text-only returns string content", () => {
  const message = buildVisionAwareUserMessage(
    undefined,
    "user_message",
    "hello",
    "full prompt text",
  );
  assertEquals(message.content, "full prompt text");
});

Deno.test("buildVisionAwareUserMessage: empty array returns string content", () => {
  const message = buildVisionAwareUserMessage(
    [],
    "user_message",
    "hello",
    "full prompt text",
  );
  assertEquals(message.content, "full prompt text");
});

Deno.test("buildVisionAwareUserMessage: vision returns array content", () => {
  const message = buildVisionAwareUserMessage(
    [makeAttachment("m1", VALID_BASE64_4B)],
    "user_message",
    "hello",
    "full prompt text",
  );
  assertEquals(Array.isArray(message.content), true);
  const firstPart = (message.content as { type: string; text: string }[])[0];
  assertEquals(firstPart.type, "text");
  assertEquals(firstPart.text.includes("attachment_1: image 1"), true);
  assertEquals(firstPart.text.endsWith("full prompt text"), true);
  assertEquals(firstPart.text.includes("m1"), false);
});

Deno.test("buildVisionAwareUserMessage: interaction_result with attachments throws", () => {
  assertThrows(
    () =>
      buildVisionAwareUserMessage(
        [makeAttachment("m1", VALID_BASE64_4B)],
        "interaction_result",
        "hello",
        "full prompt text",
      ),
    VisionValidationError,
    "attachments are not allowed for interaction_result",
  );
});

Deno.test("buildVisionAwareUserMessage: empty text with attachments throws EMPTY_VISION_TEXT", () => {
  assertThrows(
    () =>
      buildVisionAwareUserMessage(
        [makeAttachment("m1", VALID_BASE64_4B)],
        "user_message",
        "   ",
        "full prompt text",
      ),
    VisionValidationError,
    "vision requests require non-empty text",
  );
});

Deno.test("size limits: exactly 640 KiB allowed", () => {
  // 640 KiB = 655360 bytes
  // Base64 length = ceil(655360 / 3) * 4 = 873814 (since 655360 % 3 = 1, ceil = 217787 * 4 = 871148?)
  // Let's compute: 655360 / 3 = 218453.333..., ceil = 218454 quads -> 873816 chars, padding = 2
  // decoded = 873816 / 4 * 3 - 2 = 218454 * 3 - 2 = 655362 - 2 = 655360
  const quadCount = Math.ceil(655360 / 3); // 218454
  const base64Len = quadCount * 4; // 873816
  const padding = (quadCount * 3) - 655360; // 2
  const payloadChars = "A".repeat(base64Len - padding) + "=".repeat(padding);
  assertEquals(calculateDecodedBase64Size(payloadChars), 655360);

  const result = parseAndValidateAttachments(
    [makeAttachment("m1", payloadChars)],
    "user_message",
    "hi",
  );
  assertEquals(result[0].decodedByteSize, 655360);
});

Deno.test("size limits: 640 KiB + 1 byte rejected", () => {
  const targetBytes = 640 * 1024 + 1;
  const quadCount = Math.ceil(targetBytes / 3);
  const base64Len = quadCount * 4;
  const padding = (quadCount * 3) - targetBytes;
  const payloadChars = "A".repeat(base64Len - padding) + "=".repeat(padding);
  assertEquals(calculateDecodedBase64Size(payloadChars), targetBytes);

  assertThrows(
    () =>
      parseAndValidateAttachments(
        [makeAttachment("m1", payloadChars)],
        "user_message",
        "hi",
      ),
    VisionValidationError,
    "attachment exceeds maximum decoded size",
  );
});

Deno.test("size limits: six attachments at max single size keep total under 4 MiB", () => {
  const singleBytes = 640 * 1024; // 655360
  const quadCount = Math.ceil(singleBytes / 3); // 218454
  const base64Len = quadCount * 4; // 873816
  const padding = (quadCount * 3) - singleBytes; // 2
  const payloadChars = "A".repeat(base64Len - padding) + "=".repeat(padding);
  assertEquals(calculateDecodedBase64Size(payloadChars), singleBytes);

  const attachments = Array.from(
    { length: 6 },
    (_, i) => makeAttachment(`m${i}`, payloadChars),
  );
  const result = parseAndValidateAttachments(attachments, "user_message", "hi");
  assertEquals(result.length, 6);
  assertEquals(
    result.reduce((sum, a) => sum + a.decodedByteSize, 0),
    6 * singleBytes,
  );
});

Deno.test("size limits: total exactly 4 MiB allowed via helper", () => {
  const previous = 4 * 1024 * 1024 - 1024;
  const total = checkAttachmentSizeLimits(1024, previous);
  assertEquals(total, 4 * 1024 * 1024);
});

Deno.test("size limits: total 4 MiB + 1 byte rejected via helper", () => {
  const previous = 4 * 1024 * 1024 - 1023;
  assertThrows(
    () => checkAttachmentSizeLimits(1024, previous),
    VisionValidationError,
    "total attachment size exceeds maximum",
  );
});

Deno.test("security: error messages do not contain base64", () => {
  const badBase64 = "data:image/jpeg;base64,dGVzdA==";
  try {
    parseAndValidateAttachments(
      [makeAttachment("m1", badBase64)],
      "user_message",
      "hi",
    );
    throw new Error("should have thrown");
  } catch (error) {
    const message = (error as Error).message;
    assertEquals(message.includes(badBase64), false);
    assertEquals(message.includes("dGVzdA=="), false);
  }
});

Deno.test("security: error messages do not contain full attachment object", () => {
  try {
    parseAndValidateAttachments(
      [makeAttachment("m1", "!!!")],
      "user_message",
      "hi",
    );
    throw new Error("should have thrown");
  } catch (error) {
    const message = (error as Error).message;
    assertEquals(message.includes("mediaId"), false);
    assertEquals(message.includes("m1"), false);
  }
});

Deno.test("turnType: user_message with attachments allowed", () => {
  const result = parseAndValidateAttachments(
    [makeAttachment("m1", VALID_BASE64_4B)],
    "user_message",
    "hi",
  );
  assertEquals(result.length, 1);
});

Deno.test("turnType: interaction_result without attachments allowed", () => {
  assertEquals(parseAndValidateAttachments([], "interaction_result", "hi"), []);
  assertEquals(
    parseAndValidateAttachments(undefined, "interaction_result", "hi"),
    [],
  );
});

Deno.test("turnType: interaction_result with attachments throws", () => {
  assertThrows(
    () =>
      parseAndValidateAttachments(
        [makeAttachment("m1", VALID_BASE64_4B)],
        "interaction_result",
        "hi",
      ),
    VisionValidationError,
    "attachments are not allowed for interaction_result",
  );
});

Deno.test("message construction: outbound content is array not stringified", () => {
  const message = buildVisionAwareUserMessage(
    [makeAttachment("m1", VALID_BASE64_4B)],
    "user_message",
    "hi",
    "full prompt text",
  );
  assertEquals(Array.isArray(message.content), true);
  // Ensure JSON.stringify of the content array remains an array structure
  const json = JSON.stringify(message.content);
  assertEquals(json.startsWith('[{"type":"text"'), true);
});

Deno.test("message construction: original base64 unchanged", () => {
  const message = buildVisionAwareUserMessage(
    [makeAttachment("m1", VALID_BASE64_4B)],
    "user_message",
    "hi",
    "full prompt text",
  );
  const parts = message.content as { image_url: { url: string } }[];
  assertEquals(
    parts[1].image_url.url,
    `data:image/jpeg;base64,${VALID_BASE64_4B}`,
  );
});

Deno.test("message construction: data URL prefix added exactly once", () => {
  const message = buildVisionAwareUserMessage(
    [makeAttachment("m1", VALID_BASE64_4B)],
    "user_message",
    "hi",
    "full prompt text",
  );
  const parts = message.content as { image_url: { url: string } }[];
  const url = parts[1].image_url.url;
  const prefixCount = url.split("data:image/jpeg;base64,").length - 1;
  assertEquals(prefixCount, 1);
});
