import { assertEquals } from "@std/assert";
import { handleAssistantTurnV2Stream } from "./assistant_turn_v2_stream.ts";
import { buildRequest, createLoggerStub, createTestConfig } from "../test_helpers.ts";
import { StructuredLogger } from "../logger.ts";

const VALID_BASE64_4B = "dGVzdA==";

type CapturedRequest = {
  url: string | URL | Request;
  init?: RequestInit;
  body: Record<string, unknown>;
};

function setupStreamingMocks() {
  const originalFetch = globalThis.fetch;
  const captured: CapturedRequest[] = [];

  globalThis.fetch = (
    url: string | URL | Request,
    init?: RequestInit,
  ): Promise<Response> => {
    captured.push({
      url,
      init,
      body: JSON.parse(init?.body as string),
    });

    const requestBody = JSON.parse(init?.body as string);
    if (requestBody.stream === false) {
      return Promise.resolve(
        new Response(
          JSON.stringify({ choices: [{ message: { content: '{"r":"backup","a":[]}' } }] }),
          { status: 200, headers: { "Content-Type": "application/json" } },
        ),
      );
    }

    const encoder = new TextEncoder();
    const chunks = [
      'data: {"choices":[{"delta":{"content":"{\\"r\\":\\""}}]}\n\n',
      'data: {"choices":[{"delta":{"content":"ok"}}]}\n\n',
      'data: {"choices":[{"delta":{"content":"\\""}}]}\n\n',
      'data: {"choices":[{"delta":{"content":",\\"a\\":[]}"}}]}\n\n',
      "data: [DONE]\n\n",
    ];

    const stream = new ReadableStream({
      start(controller) {
        for (const chunk of chunks) {
          controller.enqueue(encoder.encode(chunk));
        }
        controller.close();
      },
    });

    return Promise.resolve(
      new Response(stream, {
        status: 200,
        headers: { "Content-Type": "text/event-stream" },
      }),
    );
  };

  return {
    captured,
    restore: () => {
      globalThis.fetch = originalFetch;
    },
  };
}

async function readSseEvents(response: Response): Promise<Record<string, unknown>[]> {
  const reader = response.body?.getReader();
  if (!reader) return [];
  const decoder = new TextDecoder();
  let buffer = "";
  const events: Record<string, unknown>[] = [];

  while (true) {
    const { value, done } = await reader.read();
    buffer += decoder.decode(value ?? new Uint8Array(), { stream: !done });
    if (done) break;

    const lines = buffer.split(/\r?\n/);
    buffer = lines.pop() ?? "";

    let currentEvent = "message";
    for (const line of lines) {
      if (line.startsWith("event:")) {
        currentEvent = line.slice(6).trim();
      } else if (line.startsWith("data:")) {
        const data = line.slice(5).trim();
        events.push({
          event: currentEvent,
          data: data === "[DONE]" ? null : JSON.parse(data),
        });
        currentEvent = "message";
      }
    }
  }

  return events;
}

function buildBaseRequest(body: unknown): Request {
  return buildRequest("POST", "/assistant-turn-v2-stream", body);
}

Deno.test("streaming handler: text-only SSE event order", async () => {
  const mocks = setupStreamingMocks();
  try {
    const response = await handleAssistantTurnV2Stream(
      buildBaseRequest({ date: "2026-06-26", userText: "hello" }),
      createTestConfig(),
      createLoggerStub() as never,
      "req-s1",
    );

    assertEquals(response.status, 200);
    assertEquals(
      response.headers.get("Content-Type"),
      "text/event-stream; charset=utf-8",
    );
    assertEquals(response.headers.get("X-Request-Id"), "req-s1");

    const events = await readSseEvents(response);
    const eventNames = events.map((e) => e.event);
    assertEquals(eventNames, ["status", "reply_delta", "final", "debug_timing", "done"]);

    const finalEvent = events.find((e) => e.event === "final");
    const finalData = finalEvent?.data as Record<string, unknown>;
    assertEquals(finalData?.reply, "ok");
    assertEquals(finalData?.actions, []);
    assertEquals(typeof finalData?.debugTiming, "object");
  } finally {
    mocks.restore();
  }
});

Deno.test("streaming handler: vision request uses array content", async () => {
  const mocks = setupStreamingMocks();
  try {
    const response = await handleAssistantTurnV2Stream(
      buildBaseRequest({
        date: "2026-06-26",
        userText: "look at this",
        attachments: [{
          mediaId: "m1",
          mimeType: "image/jpeg",
          base64: VALID_BASE64_4B,
        }],
      }),
      createTestConfig(),
      createLoggerStub() as never,
      "req-s2",
    );

    assertEquals(response.status, 200);
    const events = await readSseEvents(response);
    const errorEvents = events.filter((e) => e.event === "error");
    assertEquals(errorEvents.length, 0);

    assertEquals(mocks.captured.length, 3);
    const outbound = mocks.captured[0].body;
    const messages = outbound.messages as Record<string, unknown>[];
    const content = messages[1].content as Record<string, unknown>[];
    assertEquals(Array.isArray(content), true);
    assertEquals(content.length, 2);
    assertEquals(content[1], {
      type: "image_url",
      image_url: { url: `data:image/jpeg;base64,${VALID_BASE64_4B}` },
    });
  } finally {
    mocks.restore();
  }
});

Deno.test("streaming handler: upstream error does not leak raw Kimi body or message", async () => {
  const sensitiveMarker = "STREAMING_RAW_ERROR_MARKER_503";
  const originalFetch = globalThis.fetch;
  globalThis.fetch = (
    _url: string | URL | Request,
    _init?: RequestInit,
  ): Promise<Response> => {
    return Promise.resolve(
      new Response(
        JSON.stringify({ error: { message: sensitiveMarker } }),
        { status: 503, headers: { "Content-Type": "application/json" } },
      ),
    );
  };

  const logs: string[] = [];
  const originalError = console.error;
  console.error = (message: string) => logs.push(message);

  try {
    const response = await handleAssistantTurnV2Stream(
      buildBaseRequest({ date: "2026-06-26", userText: "hello" }),
      createTestConfig(),
      new StructuredLogger("test", "error"),
      "req-s-err",
    );

    assertEquals(response.status, 200);
    const events = await readSseEvents(response);
    const errorEvent = events.find((e) => e.event === "error");
    assertEquals(errorEvent !== undefined, true);
    const errorData = errorEvent?.data as Record<string, unknown>;
    assertEquals(errorData?.code, "UPSTREAM_ALL_ATTEMPTS_FAILED");

    const allOutput = JSON.stringify(events) + logs.join("\n");
    assertEquals(allOutput.includes(sensitiveMarker), false);
  } finally {
    globalThis.fetch = originalFetch;
    console.error = originalError;
  }
});

Deno.test("streaming handler: vision validation error is in-band 200 + event:error {message,code}", async () => {
  const mocks = setupStreamingMocks();
  try {
    const response = await handleAssistantTurnV2Stream(
      buildBaseRequest({
        date: "2026-06-26",
        userText: "confirm",
        turnType: "interaction_result",
        interactionResult: {
          interactionId: "card-1",
          actionType: "show_confirm_card",
          selectedOptionId: "confirm",
          selectedOptionLabel: "确认",
        },
        attachments: [{
          mediaId: "m1",
          mimeType: "image/jpeg",
          base64: VALID_BASE64_4B,
        }],
      }),
      createTestConfig(),
      createLoggerStub() as never,
      "req-s3",
    );

    // Matches the existing Edge Function SSE protocol: headers are already committed as 200,
    // so the failure is delivered as an in-band error event carrying {message, code} — NOT a
    // non-200 response nor an {error, errorCode} body.
    assertEquals(response.status, 200);
    assertEquals(
      response.headers.get("Content-Type"),
      "text/event-stream; charset=utf-8",
    );
    assertEquals(response.headers.get("X-Request-Id"), "req-s3");

    const events = await readSseEvents(response);
    const eventNames = events.map((e) => e.event);
    assertEquals(eventNames, ["status", "error"]);

    const errorEvent = events.find((e) => e.event === "error");
    const errorData = errorEvent?.data as Record<string, unknown>;
    assertEquals(errorData?.code, "ATTACHMENTS_NOT_ALLOWED_FOR_TURN_TYPE");
    assertEquals(typeof errorData?.message, "string");
    // The Edge SSE error shape uses `message`/`code`, not `error`/`errorCode`.
    assertEquals(errorData?.errorCode, undefined);
    assertEquals(errorData?.error, undefined);

    // No upstream Kimi call should have been made for a rejected attachment turn.
    assertEquals(mocks.captured.length, 0);
  } finally {
    mocks.restore();
  }
});
