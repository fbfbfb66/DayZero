import {
  applyVisionContentToCurrentUserMessage,
  parseAndValidateAttachments,
  VisionValidationError,
} from "../shared/assistant_vision.ts";
import { selectStreamHeaderTimeoutMs } from "../shared/assistant_upstream_timeout.ts";
import { CandidateRaceError, raceMoonshotCandidates } from "../shared/kimi_stream.ts";
import { ensureInteractionContinuationAction, normalizeActions } from "../shared/normalization.ts";
import {
  buildPromptInputs,
  buildSystemPrompt,
  buildUserContent,
  extractMediaIds,
  STREAMING_PROMPT_VERSION,
} from "../shared/prompt.ts";
import type { KimiConfig } from "../shared/kimi_client.ts";
import { type ParsedRequest, parseRequestBody } from "../shared/request_parser.ts";
import { parseKimiContent, roundTiming, validateActions } from "../shared/protocol.ts";
import type { GatewayConfig } from "../config.ts";
import { getCorsHeaders } from "../cors.ts";
import { createErrorResponse } from "../errors.ts";
import type { StructuredLogger } from "../logger.ts";
import { readLimitedJsonBody } from "../body_reader.ts";

export async function handleAssistantTurnV2Stream(
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
    return new Response(JSON.stringify(parsed.body), {
      status: parsed.status,
      headers: {
        ...corsHeaders,
        "Content-Type": "application/json",
      },
    });
  }
  const request = parsed.request;
  const requestParsedAt = performance.now();

  logger.info("assistant-turn-v2-stream start", {
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

  const kimiConfig: KimiConfig = {
    apiUrl: config.kimiApiUrl,
    apiKey: config.kimiApiKey,
    model: config.kimiModel,
    maxTokens: config.kimiMaxTokens,
    temperature: config.kimiTemperature,
  };

  const stream = new ReadableStream({
    async start(controller) {
      const encoder = new TextEncoder();
      const sendEvent = (event: string, data: unknown) => {
        controller.enqueue(
          encoder.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`),
        );
      };

      try {
        sendEvent("status", { state: "started" });

        // Vision validation runs inside the (already-committed 200) stream so that invalid
        // attachments surface as an in-band `event: error` with `{message, code}`, matching the
        // existing Edge Function SSE protocol the Android client parses.
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
            sendEvent("error", { message: error.message, code: error.code });
            return;
          }
          throw error;
        }
        const promptBuiltAt = performance.now();

        const attachmentCount = validatedAttachments.length;
        const totalDecodedAttachmentBytes = validatedAttachments.reduce(
          (total, attachment) => total + attachment.decodedByteSize,
          0,
        );
        const selectedStreamHeaderTimeoutMs = selectStreamHeaderTimeoutMs(
          attachmentCount,
          totalDecodedAttachmentBytes,
        );

        const interactionResultForHedge = request.interactionResult &&
            typeof request.interactionResult === "object"
          ? request.interactionResult as Record<string, unknown>
          : null;
        const continuationHasMedia = hasContinuationMediaIds(interactionResultForHedge);
        const hedgedAttemptCount = attachmentCount > 0 || continuationHasMedia ? 3 : 1;
        const upstreamBudgetMs = attachmentCount > 0
          ? 35_000
          : continuationHasMedia
          ? 20_000
          : 15_000;

        const kimiRequestStartedAt = performance.now();
        const race = await raceMoonshotCandidates({
          config: kimiConfig,
          systemPrompt,
          userMessage,
          promptCacheKey: request.promptCacheKey,
          attemptCount: hedgedAttemptCount,
          budgetMs: upstreamBudgetMs,
          onPrimaryDelta: (text) => sendEvent("reply_delta", { text }),
        });
        const fullContent = race.content;
        const firstTokenAt = race.firstTokenAt;
        const upstreamHeadersAt = race.firstHeadersAt;
        const kimiStreamEndedAt = race.winnerCompletedAt;
        const kimiJsonParseStartedAt = performance.now();
        const parsedResponse = parseKimiContent(fullContent);
        const kimiJsonParsedAt = performance.now();
        const protocolValidationStartedAt = performance.now();

        const { reply, actions, compactJsonUsed } = parsedResponse;

        if (!reply) throw new Error("Kimi returned blank reply");

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

        validateActions(actions);
        const protocolValidatedAt = performance.now();

        logger.info("assistant-turn-v2-stream success", {
          requestId,
          userIdDigest,
          traceId: request.traceId,
          replyLength: reply.length,
          actionsCount: actions.length,
        });

        const edgeFinalPreparedAt = performance.now();
        const debugTiming = {
          traceId: request.traceId,
          promptVersion: STREAMING_PROMPT_VERSION,
          totalMs: roundTiming(performance.now() - functionStartedAt),
          requestParseMs: roundTiming(requestParsedAt - functionStartedAt),
          promptBuildMs: roundTiming(promptBuiltAt - promptBuildStartedAt),
          kimiTimeToFirstTokenMs: firstTokenAt == null
            ? null
            : roundTiming(firstTokenAt - kimiRequestStartedAt),
          kimiStreamMs: roundTiming(kimiStreamEndedAt - kimiRequestStartedAt),
          kimiJsonParseMs: roundTiming(kimiJsonParsedAt - kimiJsonParseStartedAt),
          protocolValidationMs: roundTiming(
            protocolValidatedAt - protocolValidationStartedAt,
          ),
          promptChars: systemPrompt.length + userContent.length,
          outputJsonChars: fullContent.length,
          compactJsonUsed,
          promptCacheKeyUsed: Boolean(request.promptCacheKey),
          totalDecodedAttachmentBytes,
          selectedStreamHeaderTimeoutMs,
          upstreamBudgetMs,
          hedgedAttemptCount,
          winnerAttemptIndex: race.winnerAttemptIndex,
          cancelledAttemptCount: race.cancelledAttemptCount,
          provisionalTextReplaced: race.provisionalTextReplaced,
          upstreamHeadersMs: roundTiming(upstreamHeadersAt - kimiRequestStartedAt),
          upstreamTotalMs: roundTiming(kimiStreamEndedAt - kimiRequestStartedAt),
          lastReplyContentAvailableMs: roundTiming(
            kimiStreamEndedAt - functionStartedAt,
          ),
          actionsReadyMs: roundTiming(protocolValidatedAt - functionStartedAt),
          edgeFinalEmittedMs: roundTiming(edgeFinalPreparedAt - functionStartedAt),
          lastReplyToActionsReadyMs: roundTiming(
            protocolValidatedAt - kimiStreamEndedAt,
          ),
          actionsReadyToEdgeFinalMs: roundTiming(
            edgeFinalPreparedAt - protocolValidatedAt,
          ),
          timeoutStage: "NONE",
          ...photoAssignmentDebug,
        };

        sendEvent("final", { reply, actions, debugTiming });
        sendEvent("debug_timing", debugTiming);
        sendEvent("done", {});
      } catch (error) {
        const code = error instanceof CandidateRaceError ? error.code : "PROTOCOL_INVALID_FINAL";
        const retryable = error instanceof CandidateRaceError;
        const message = error instanceof CandidateRaceError
          ? "AI response is temporarily unavailable. Please retry."
          : "AI response could not be validated.";

        logger.error("assistant-turn-v2-stream error", {
          requestId,
          traceId: request.traceId,
          errorCode: code,
          retryable,
        });

        sendEvent("error", { code, retryable, message });
      } finally {
        controller.close();
      }
    },
  });

  return new Response(stream, {
    headers: {
      ...corsHeaders,
      "Content-Type": "text/event-stream; charset=utf-8",
      "Cache-Control": "no-cache",
    },
  });
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

function hasContinuationMediaIds(interactionResult: unknown): boolean {
  if (!interactionResult || typeof interactionResult !== "object") return false;
  const context = (interactionResult as Record<string, unknown>).continuationContext;
  if (!context || typeof context !== "object") return false;
  const mediaIds = (context as Record<string, unknown>).mediaIds;
  return Array.isArray(mediaIds) && mediaIds.length > 0;
}
