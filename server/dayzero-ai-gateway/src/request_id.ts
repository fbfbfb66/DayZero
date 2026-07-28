/**
 * Allowed characters for inbound X-Request-Id:
 * ASCII letters, digits, hyphen, underscore, dot, colon.
 * Length must be between 8 and 128 to be reused.
 */
const REQUEST_ID_SAFE = /^[A-Za-z0-9\-_.:]{8,128}$/;

export function generateRequestId(): string {
  const timestamp = Date.now().toString(36);
  const random = Math.random().toString(36).slice(2, 10);
  return `${timestamp}-${random}`;
}

/**
 * Resolves an inbound X-Request-Id header value.
 * Reuses the value only if it matches the safe format.
 * Otherwise generates a fresh request id without leaking the invalid input.
 */
export function resolveRequestId(raw: string | null): string {
  if (raw && REQUEST_ID_SAFE.test(raw)) {
    return raw;
  }
  return generateRequestId();
}
