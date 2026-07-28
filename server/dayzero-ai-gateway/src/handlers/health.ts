import type { GatewayConfig } from "../config.ts";
import { getCorsHeaders } from "../cors.ts";

export function handleHealth(
  req: Request,
  config: GatewayConfig,
  requestId?: string,
): Response {
  const headers = {
    ...getCorsHeaders(config, req.headers.get("Origin")),
    ...(requestId ? { "X-Request-Id": requestId } : {}),
  };
  return new Response(
    JSON.stringify({
      status: "ok",
      service: "dayzero-ai-gateway",
      timestamp: new Date().toISOString(),
    }),
    {
      status: 200,
      headers: {
        ...headers,
        "Content-Type": "application/json",
      },
    },
  );
}
