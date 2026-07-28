/**
 * Shared pure functions for constructing Kimi multimodal (vision) user content.
 *
 * No environment reads, no network calls, no logging, no Base64 decoding.
 */

export const VISION_PROMPT_ADDENDUM =
  "当当前用户消息包含图片时，应结合图片与文字识别食物、份量、餐次和营养；不确定的信息使用现有缺失值规则，不得假装精确。";

export type VisionAttachment = {
  mediaId: string;
  mimeType: string;
  base64: string;
  decodedByteSize: number;
};

export type KimiContentPart =
  | { type: "text"; text: string }
  | { type: "image_url"; image_url: { url: string } };

export type KimiUserMessage = {
  role: "user";
  content: string | KimiContentPart[];
};

export const VISION_ERROR_CODES = {
  INVALID_ATTACHMENTS_TYPE: "INVALID_ATTACHMENTS_TYPE",
  INVALID_ATTACHMENT_COUNT: "INVALID_ATTACHMENT_COUNT",
  DUPLICATE_MEDIA_ID: "DUPLICATE_MEDIA_ID",
  INVALID_MEDIA_ID: "INVALID_MEDIA_ID",
  INVALID_ATTACHMENT_MIME: "INVALID_ATTACHMENT_MIME",
  INVALID_ATTACHMENT_BASE64: "INVALID_ATTACHMENT_BASE64",
  ATTACHMENT_TOO_LARGE: "ATTACHMENT_TOO_LARGE",
  ATTACHMENTS_TOTAL_TOO_LARGE: "ATTACHMENTS_TOTAL_TOO_LARGE",
  ATTACHMENTS_NOT_ALLOWED_FOR_TURN_TYPE: "ATTACHMENTS_NOT_ALLOWED_FOR_TURN_TYPE",
  EMPTY_VISION_TEXT: "EMPTY_VISION_TEXT",
} as const;

export type VisionErrorCode = keyof typeof VISION_ERROR_CODES;

export class VisionValidationError extends Error {
  readonly code: VisionErrorCode;

  constructor(code: VisionErrorCode, message: string) {
    super(message);
    this.code = code;
    this.name = "VisionValidationError";
  }
}

const ALLOWED_MIME_TYPE = "image/jpeg";
const MAX_SINGLE_ATTACHMENT_BYTES = 640 * 1024;
const MAX_TOTAL_ATTACHMENT_BYTES = 4 * 1024 * 1024;
const MIN_ATTACHMENT_COUNT = 1;
const MAX_ATTACHMENT_COUNT = 6;
const SAFE_MEDIA_ID_PATTERN = /^[A-Za-z0-9._-]+$/;
const BASE64_ALPHABET_PATTERN = /^[A-Za-z0-9+/]+=*$/;

/**
 * Validate raw attachment input and return normalized vision attachments.
 *
 * - `undefined` / `null` / empty array -> no attachments.
 * - `interaction_result` with non-empty attachments is rejected.
 * - Empty effective text with non-empty attachments is rejected.
 */
export function parseAndValidateAttachments(
  raw: unknown,
  turnType: string,
  effectiveText: string,
): VisionAttachment[] {
  if (raw === undefined || raw === null) return [];

  if (!Array.isArray(raw)) {
    throw new VisionValidationError(
      "INVALID_ATTACHMENTS_TYPE",
      "attachments must be an array",
    );
  }

  if (raw.length === 0) return [];

  if (turnType === "interaction_result") {
    throw new VisionValidationError(
      "ATTACHMENTS_NOT_ALLOWED_FOR_TURN_TYPE",
      "attachments are not allowed for interaction_result",
    );
  }

  if (effectiveText.trim().length === 0) {
    throw new VisionValidationError(
      "EMPTY_VISION_TEXT",
      "vision requests require non-empty text",
    );
  }

  if (
    raw.length < MIN_ATTACHMENT_COUNT || raw.length > MAX_ATTACHMENT_COUNT
  ) {
    throw new VisionValidationError(
      "INVALID_ATTACHMENT_COUNT",
      `attachments must contain between ${MIN_ATTACHMENT_COUNT} and ${MAX_ATTACHMENT_COUNT} items`,
    );
  }

  const seenMediaIds = new Set<string>();
  const validated: VisionAttachment[] = [];
  let totalBytes = 0;

  for (const item of raw) {
    if (!item || typeof item !== "object") {
      throw new VisionValidationError(
        "INVALID_ATTACHMENTS_TYPE",
        "each attachment must be an object",
      );
    }

    const attachment = item as Record<string, unknown>;

    const mediaId = attachment.mediaId;
    if (typeof mediaId !== "string" || mediaId.length === 0) {
      throw new VisionValidationError(
        "INVALID_MEDIA_ID",
        "mediaId must be a non-empty string",
      );
    }
    if (!SAFE_MEDIA_ID_PATTERN.test(mediaId)) {
      throw new VisionValidationError(
        "INVALID_MEDIA_ID",
        "mediaId contains unsafe characters",
      );
    }
    if (seenMediaIds.has(mediaId)) {
      throw new VisionValidationError(
        "DUPLICATE_MEDIA_ID",
        "duplicate mediaId in attachments",
      );
    }
    seenMediaIds.add(mediaId);

    const mimeType = attachment.mimeType;
    if (mimeType !== ALLOWED_MIME_TYPE) {
      throw new VisionValidationError(
        "INVALID_ATTACHMENT_MIME",
        `mimeType must be ${ALLOWED_MIME_TYPE}`,
      );
    }

    const base64 = attachment.base64;
    if (typeof base64 !== "string") {
      throw new VisionValidationError(
        "INVALID_ATTACHMENT_BASE64",
        "base64 must be a string",
      );
    }

    const decodedByteSize = calculateDecodedBase64Size(base64);
    totalBytes = checkAttachmentSizeLimits(decodedByteSize, totalBytes);

    validated.push({
      mediaId,
      mimeType: ALLOWED_MIME_TYPE,
      base64,
      decodedByteSize,
    });
  }

  return validated;
}

/**
 * Calculate the exact decoded byte size of a standard Base64 string.
 *
 * Performs strict validation without decoding the payload:
 * - rejects empty strings
 * - rejects whitespace, data: prefixes, URL-safe chars
 * - validates length and padding rules
 */
export function calculateDecodedBase64Size(base64: string): number {
  if (base64.length === 0) {
    throw new VisionValidationError(
      "INVALID_ATTACHMENT_BASE64",
      "base64 must not be empty",
    );
  }

  if (base64.includes("data:")) {
    throw new VisionValidationError(
      "INVALID_ATTACHMENT_BASE64",
      "base64 must not contain data: prefix",
    );
  }

  if (/[\s]/.test(base64)) {
    throw new VisionValidationError(
      "INVALID_ATTACHMENT_BASE64",
      "base64 must not contain whitespace",
    );
  }

  if (base64.includes("-") || base64.includes("_")) {
    throw new VisionValidationError(
      "INVALID_ATTACHMENT_BASE64",
      "base64 must not use URL-safe characters",
    );
  }

  if (base64.length % 4 !== 0) {
    throw new VisionValidationError(
      "INVALID_ATTACHMENT_BASE64",
      "base64 length must be a multiple of 4",
    );
  }

  if (!BASE64_ALPHABET_PATTERN.test(base64)) {
    throw new VisionValidationError(
      "INVALID_ATTACHMENT_BASE64",
      "base64 contains invalid characters",
    );
  }

  // Validate padding position and count.
  const firstPaddingIndex = base64.indexOf("=");
  const paddingCount = firstPaddingIndex === -1 ? 0 : base64.length - firstPaddingIndex;

  if (paddingCount > 2) {
    throw new VisionValidationError(
      "INVALID_ATTACHMENT_BASE64",
      "base64 has too many padding characters",
    );
  }

  if (firstPaddingIndex !== -1) {
    const afterPadding = base64.slice(firstPaddingIndex);
    if (!afterPadding.split("").every((c) => c === "=")) {
      throw new VisionValidationError(
        "INVALID_ATTACHMENT_BASE64",
        "base64 padding must only appear at the end",
      );
    }
  }

  return (base64.length / 4) * 3 - paddingCount;
}

/**
 * Check a single attachment size and running total against the configured limits.
 *
 * Returns the new running total. Throws VisionValidationError with the appropriate
 * code when either limit is exceeded.
 */
export function checkAttachmentSizeLimits(
  singleBytes: number,
  totalBefore: number,
): number {
  if (singleBytes > MAX_SINGLE_ATTACHMENT_BYTES) {
    throw new VisionValidationError(
      "ATTACHMENT_TOO_LARGE",
      "attachment exceeds maximum decoded size",
    );
  }

  const totalAfter = totalBefore + singleBytes;
  if (totalAfter > MAX_TOTAL_ATTACHMENT_BYTES) {
    throw new VisionValidationError(
      "ATTACHMENTS_TOTAL_TOO_LARGE",
      "total attachment size exceeds maximum",
    );
  }

  return totalAfter;
}

/**
 * Stable, model-visible aliases for request attachments.
 *
 * The model must not see or invent Android media IDs. It may refer to images by
 * `attachment_1`, `attachment_2`, ... in the same order as the image_url blocks.
 * Edge normalization maps those aliases back to the validated media IDs.
 */
export function buildAttachmentIdentityPrompt(
  attachments: VisionAttachment[],
): string {
  if (attachments.length === 0) return "";
  const lines = attachments.map((_, index) => `- attachment_${index + 1}: image ${index + 1}`);
  return [
    "ImageAttachmentReferences:",
    ...lines,
    "When assigning meal photos, use only these attachment_N references in sourceMediaIds; never use Base64, URLs, file paths, or invented IDs.",
  ].join("\n");
}

/**
 * Build a Kimi multimodal content array: one text part followed by image parts.
 */
export function buildKimiUserContent(
  text: string,
  attachments: VisionAttachment[],
): KimiContentPart[] {
  if (attachments.length === 0) {
    throw new VisionValidationError(
      "INVALID_ATTACHMENT_COUNT",
      "cannot build vision content without attachments",
    );
  }

  const identityPrompt = buildAttachmentIdentityPrompt(attachments);
  const textWithIdentity = identityPrompt.length > 0 ? `${identityPrompt}\n\n${text}` : text;
  const content: KimiContentPart[] = [{ type: "text", text: textWithIdentity }];

  for (const attachment of attachments) {
    content.push({
      type: "image_url",
      image_url: {
        url: `data:${attachment.mimeType};base64,${attachment.base64}`,
      },
    });
  }

  return content;
}

/**
 * Apply vision content to the current user message if attachments are present.
 *
 * Returns the message as-is (string content) when there are no attachments.
 */
export function applyVisionContentToCurrentUserMessage(
  textContent: string,
  attachments: VisionAttachment[],
): KimiUserMessage {
  const content = attachments.length > 0
    ? buildKimiUserContent(textContent, attachments)
    : textContent;

  return { role: "user", content };
}

/**
 * Convenience helper: parse attachments and build the final user message in one call.
 */
export function buildVisionAwareUserMessage(
  rawAttachments: unknown,
  turnType: string,
  effectiveText: string,
  textContent: string,
): KimiUserMessage {
  const attachments = parseAndValidateAttachments(
    rawAttachments,
    turnType,
    effectiveText,
  );
  return applyVisionContentToCurrentUserMessage(textContent, attachments);
}
