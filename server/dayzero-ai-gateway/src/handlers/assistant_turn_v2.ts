import {
  applyVisionContentToCurrentUserMessage,
  parseAndValidateAttachments,
  VisionValidationError,
} from "../shared/assistant_vision.ts";
import {
  FALLBACK_UPSTREAM_TOTAL_TIMEOUT_MS,
  isAbortError,
} from "../shared/assistant_upstream_timeout.ts";
import { ensureInteractionContinuationAction, normalizeActions } from "../shared/normalization.ts";
import {
  buildPromptInputs,
  buildSystemPrompt,
  buildUserContent,
  extractMediaIds,
  FALLBACK_PROMPT_VERSION,
} from "../shared/prompt.ts";
import {
  buildKimiRequestBody,
  fetchKimi,
  type KimiConfig,
  type KimiResult,
} from "../shared/kimi_client.ts";
import { mapVisionError, type ParsedRequest, parseRequestBody } from "../shared/request_parser.ts";
import { parseKimiContent, roundTiming, validateActions } from "../shared/protocol.ts";
import type { GatewayConfig } from "../config.ts";
import { getCorsHeaders } from "../cors.ts";
import {
  createErrorResponse,
  createJsonResponse,
  createUpstreamErrorResponse,
  isRetryableUpstreamStatus,
  mapKimiStatusToErrorCode,
} from "../errors.ts";
import type { StructuredLogger } from "../logger.ts";
import { readLimitedJsonBody } from "../body_reader.ts";

export async function handleAssistantTurnV2(
  req: Request,
  config: GatewayConfig,
  logger: StructuredLogger,
  requestId: string,
  userIdDigest = "anonymous",
): Promise<Response> {
  const functionStartedAt = performance.now();
  const corsHeaders = {
    ...getCorsHeaders(config, req.headers.get("Origin")),
    "X-Request-Id": requestId,
  };

  if (req.method !== "POST") {
    return createErrorResponse(405, "Method Not Allowed", undefined, corsHeaders);
  }

  const bodyResult = await readLimitedJsonBody(req, config, corsHeaders);
  if (!bodyResult.ok) {
    return bodyResult.response;
  }

  const parsed = parseRequestBody(bodyResult.body);
  if (!parsed.ok) {
    return createJsonResponse(parsed.body, parsed.status, corsHeaders);
  }
  const request = parsed.request;
  const requestParsedAt = performance.now();

  logger.info("assistant-turn-v2 start", {
    requestId,
    userIdDigest,
    traceId: request.traceId,
    userTextLength: request.userText.length,
    turnType: request.turnType,
  });

  const promptBuildStartedAt = performance.now();
  const systemPrompt = buildSystemPrompt();
  const promptInputs = buildPromptInputs(request);

  const userContent = buildUserContent(promptInputs);

  let userMessage;
  let validatedAttachments;
  try {
    validatedAttachments = parseAndValidateAttachments(
      request.attachments,
      request.turnType,
      request.userText,
    );
    userMessage = applyVisionContentToCurrentUserMessage(
      userContent,
      validatedAttachments,
    );
  } catch (error) {
    if (error instanceof VisionValidationError) {
      const mapped = mapVisionError(error);
      return createJsonResponse(mapped.body, mapped.status, corsHeaders);
    }
    throw error;
  }
  const promptBuiltAt = performance.now();

  const totalDecodedAttachmentBytes = validatedAttachments.reduce(
    (total, attachment) => total + attachment.decodedByteSize,
    0,
  );

  const kimiConfig: KimiConfig = {
    apiUrl: config.kimiApiUrl,
    apiKey: config.kimiApiKey,
    model: config.kimiModel,
    maxTokens: config.kimiMaxTokens,
    temperature: config.kimiTemperature,
  };

  const kimiRequestStartedAt = performance.now();
  const abortController = new AbortController();
  const timeoutId = setTimeout(
    () => abortController.abort(),
    FALLBACK_UPSTREAM_TOTAL_TIMEOUT_MS,
  );

  let response: Response;
  let kimiResult: KimiResult;
  let kimiResponseReceivedAt: number;

  try {
    const kimiBody = buildKimiRequestBody(
      systemPrompt,
      userMessage,
      request.promptCacheKey,
      false,
      kimiConfig,
    );
    response = await fetchKimi(kimiConfig, kimiBody, abortController.signal);
    kimiResponseReceivedAt = performance.now();

    if (!response.ok) {
      const code = mapKimiStatusToErrorCode(response.status);
      const retryable = isRetryableUpstreamStatus(response.status);
      logger.warn("kimi request failed", {
        requestId,
        httpStatus: response.status,
        errorCode: code,
        retryable,
      });
      return createUpstreamErrorResponse(code, 502, retryable, corsHeaders);
    }

    kimiResult = await response.json() as KimiResult;
  } catch (error) {
    if (isAbortError(error)) {
      return createUpstreamErrorResponse(
        "UPSTREAM_TOTAL_TIMEOUT",
        504,
        true,
        corsHeaders,
      );
    }
    throw error;
  } finally {
    clearTimeout(timeoutId);
  }

  const kimiJsonParseStartedAt = kimiResponseReceivedAt;
  const kimiJsonParsedAt = performance.now();
  const content = kimiResult?.choices?.[0]?.message?.content;

  if (typeof content !== "string" || content.trim().length === 0) {
    return createErrorResponse(502, "Kimi returned empty content", undefined, corsHeaders);
  }

  const protocolValidationStartedAt = performance.now();
  let parsedResponse;
  try {
    parsedResponse = parseKimiContent(content);
  } catch (_error) {
    return createErrorResponse(502, "Failed to parse Kimi JSON", undefined, corsHeaders);
  }

  const { reply, actions, compactJsonUsed } = parsedResponse;

  if (!reply) {
    return createErrorResponse(502, "Kimi returned blank reply", undefined, corsHeaders);
  }

  const interactionContext = extractInteractionContext(request);
  if (request.turnType === "interaction_result") {
    ensureInteractionContinuationAction(
      actions,
      interactionContext.actionType,
      interactionContext.selectedOptionId,
      interactionContext.continuationContext,
    );
  }

  const photoAssignmentDebug = normalizeActions(
    actions,
    request.date,
    interactionContext.fallbackOriginalText,
    request.todayRecord as Record<string, unknown> | null | undefined,
    {
      inheritedContinuationContext: interactionContext.continuationContext,
      mediaIds: extractMediaIds(request.attachments),
      selectedMealType: interactionContext.actionType === "ask_missing_info_card"
        ? interactionContext.selectedOptionId
        : null,
    },
  );

  try {
    validateActions(actions);
  } catch (_error) {
    return createUpstreamErrorResponse(
      "INVALID_UPSTREAM_RESPONSE",
      502,
      false,
      corsHeaders,
    );
  }

  const protocolValidatedAt = performance.now();

  logger.info("assistant-turn-v2 success", {
    requestId,
    userIdDigest,
    traceId: request.traceId,
    replyLength: reply.length,
    actionsCount: actions.length,
  });

  return createJsonResponse(
    {
      reply,
      actions,
      debugTiming: {
        traceId: request.traceId,
        promptVersion: FALLBACK_PROMPT_VERSION,
        totalMs: roundTiming(performance.now() - functionStartedAt),
        requestParseMs: roundTiming(requestParsedAt - functionStartedAt),
        promptBuildMs: roundTiming(promptBuiltAt - promptBuildStartedAt),
        kimiRequestMs: roundTiming(kimiResponseReceivedAt - kimiRequestStartedAt),
        kimiJsonParseMs: roundTiming(kimiJsonParsedAt - kimiJsonParseStartedAt),
        protocolValidationMs: roundTiming(
          protocolValidatedAt - protocolValidationStartedAt,
        ),
        promptChars: systemPrompt.length + userContent.length,
        outputJsonChars: content.length,
        compactJsonUsed,
        promptCacheKeyUsed: Boolean(request.promptCacheKey),
        totalDecodedAttachmentBytes,
        upstreamHeadersMs: roundTiming(
          kimiResponseReceivedAt - kimiRequestStartedAt,
        ),
        upstreamTotalMs: roundTiming(kimiJsonParsedAt - kimiRequestStartedAt),
        timeoutStage: "NONE",
        ...photoAssignmentDebug,
      },
    },
    200,
    corsHeaders,
  );
}

function extractInteractionContext(request: ParsedRequest) {
  const interactionResult = request.interactionResult &&
      typeof request.interactionResult === "object"
    ? request.interactionResult as Record<string, unknown>
    : null;

  let fallbackOriginalText = request.userText;
  if (
    interactionResult &&
    typeof interactionResult.originalText === "string" &&
    interactionResult.originalText.trim().length > 0
  ) {
    fallbackOriginalText = interactionResult.originalText.trim();
  }

  return {
    fallbackOriginalText,
    actionType: String(interactionResult?.actionType ?? ""),
    selectedOptionId: String(interactionResult?.selectedOptionId ?? ""),
    continuationContext: interactionResult?.continuationContext ?? null,
  };
}
