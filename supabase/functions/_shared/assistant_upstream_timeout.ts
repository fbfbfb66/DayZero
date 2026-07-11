export const TEXT_STREAM_HEADER_TIMEOUT_MS = 15_000;
export const BASE_VISION_HEADER_TIMEOUT_MS = 15_000;
export const PER_MIB_TIMEOUT_MS = 10_000;
export const MIN_VISION_HEADER_TIMEOUT_MS = 25_000;
export const MAX_VISION_HEADER_TIMEOUT_MS = 50_000;
export const FALLBACK_UPSTREAM_TOTAL_TIMEOUT_MS = 50_000;

const MIB = 1024 * 1024;

export function selectStreamHeaderTimeoutMs(
  attachmentCount: number,
  totalDecodedAttachmentBytes: number,
): number {
  if (!Number.isFinite(attachmentCount) || attachmentCount <= 0) {
    return TEXT_STREAM_HEADER_TIMEOUT_MS;
  }
  const safeBytes = Number.isFinite(totalDecodedAttachmentBytes)
    ? Math.max(0, totalDecodedAttachmentBytes)
    : 0;
  const sizeMiB = Math.ceil(safeBytes / MIB);
  return Math.min(
    MAX_VISION_HEADER_TIMEOUT_MS,
    Math.max(
      MIN_VISION_HEADER_TIMEOUT_MS,
      BASE_VISION_HEADER_TIMEOUT_MS + sizeMiB * PER_MIB_TIMEOUT_MS,
    ),
  );
}

export function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === "AbortError";
}
