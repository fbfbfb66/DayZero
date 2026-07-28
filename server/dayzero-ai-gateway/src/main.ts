import { type GatewayConfig, loadConfig } from "./config.ts";
import { createLogger, type StructuredLogger } from "./logger.ts";
import { verifyAccessToken } from "./auth.ts";
import { getCorsHeaders } from "./cors.ts";
import { resolveRequestId } from "./request_id.ts";
import { handleHealth } from "./handlers/health.ts";
import { handleReady } from "./handlers/ready.ts";
import { handleAssistantTurnV2 } from "./handlers/assistant_turn_v2.ts";
import { handleAssistantTurnV2Stream } from "./handlers/assistant_turn_v2_stream.ts";
import { createErrorResponse, handleUnexpectedError } from "./errors.ts";

const AI_PATHS = new Set([
  "/assistant-turn-v2",
  "/assistant-turn-v2-stream",
  "/api/ai/assistant-turn-v2",
  "/api/ai/assistant-turn-v2-stream",
]);

function isStreamingPath(path: string): boolean {
  return path === "/assistant-turn-v2-stream" ||
    path === "/api/ai/assistant-turn-v2-stream";
}

function isAssistantPostPath(path: string): boolean {
  return AI_PATHS.has(path);
}

/**
 * Ensures every outgoing response carries the resolved X-Request-Id header.
 * Response objects are immutable, so a missing header requires cloning the response.
 */
function withRequestId(response: Response, requestId: string): Response {
  if (response.headers.has("X-Request-Id")) {
    return response;
  }
  const headers = new Headers(response.headers);
  headers.set("X-Request-Id", requestId);
  return new Response(response.body, {
    status: response.status,
    statusText: response.statusText,
    headers,
  });
}

export async function handler(
  req: Request,
  config: GatewayConfig,
  logger: StructuredLogger,
): Promise<Response> {
  const requestId = resolveRequestId(req.headers.get("X-Request-Id"));
  const corsHeaders = {
    ...getCorsHeaders(config, req.headers.get("Origin")),
    "X-Request-Id": requestId,
  };

  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  const url = new URL(req.url);
  const routePath = url.pathname;

  if (routePath === "/health") {
    if (req.method !== "GET") {
      return withRequestId(
        createErrorResponse(405, "Method Not Allowed", undefined, corsHeaders),
        requestId,
      );
    }
    return withRequestId(handleHealth(req, config, requestId), requestId);
  }

  if (routePath === "/ready") {
    if (req.method !== "GET") {
      return withRequestId(
        createErrorResponse(405, "Method Not Allowed", undefined, corsHeaders),
        requestId,
      );
    }
    return withRequestId(handleReady(req, config, requestId), requestId);
  }

  if (isAssistantPostPath(routePath)) {
    if (req.method !== "POST") {
      return withRequestId(
        createErrorResponse(405, "Method Not Allowed", undefined, corsHeaders),
        requestId,
      );
    }

    const auth = await verifyAccessToken(
      req.headers.get("Authorization"),
      config,
      logger,
    );
    if (!auth.ok) {
      logger.warn("auth failed", {
        requestId,
        routePath,
        httpStatus: auth.status,
        errorCode: auth.code,
      });
      return withRequestId(
        createErrorResponse(
          auth.status,
          auth.message,
          auth.code,
          corsHeaders,
        ),
        requestId,
      );
    }

    try {
      if (isStreamingPath(routePath)) {
        return await handleAssistantTurnV2Stream(
          req,
          config,
          logger,
          requestId,
          auth.userIdDigest,
        );
      }
      return await handleAssistantTurnV2(
        req,
        config,
        logger,
        requestId,
        auth.userIdDigest,
      );
    } catch (error) {
      return withRequestId(
        handleUnexpectedError(error, requestId, logger, corsHeaders),
        requestId,
      );
    }
  }

  return withRequestId(
    createErrorResponse(404, "Not Found", undefined, corsHeaders),
    requestId,
  );
}

export function createServer(): {
  server: Deno.HttpServer;
  config: GatewayConfig;
  logger: StructuredLogger;
} {
  const config = loadConfig();
  const logger = createLogger(config);

  logger.info("server starting", {
    port: config.port,
    kimiModel: config.kimiModel,
    allowedOriginCount: config.allowedOrigins.length,
    enableAuth: config.enableAuth,
    appEnv: config.appEnv,
    usedLegacyEnv: config.usedLegacyEnv,
  });

  const server = Deno.serve(
    { port: config.port },
    (req) => handler(req, config, logger),
  );

  return { server, config, logger };
}

if (import.meta.main) {
  createServer();
}
