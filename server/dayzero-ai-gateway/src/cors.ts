import type { GatewayConfig } from "./config.ts";

export function getCorsHeaders(
  config: GatewayConfig,
  requestOrigin?: string | null,
): Record<string, string> {
  const allowed = config.allowedOrigins;
  const allowAll = allowed.length === 1 && allowed[0] === "*";

  let origin = allowAll ? "*" : "";
  if (!allowAll && requestOrigin && allowed.includes(requestOrigin)) {
    origin = requestOrigin;
  }

  return {
    "Access-Control-Allow-Origin": origin,
    "Access-Control-Allow-Headers":
      "authorization, x-client-info, apikey, content-type, x-request-id",
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
    "Access-Control-Max-Age": "86400",
    "Access-Control-Expose-Headers": "X-Request-Id",
  };
}
