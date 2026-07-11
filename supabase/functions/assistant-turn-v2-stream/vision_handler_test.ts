import { assertEquals } from "jsr:@std/assert@1";
import { handler, isValidCandidateContent } from "./handler.ts";

const VALID_BASE64_4B = "dGVzdA==";
const MOONSHOT_API_URL = "https://api.moonshot.cn/v1/chat/completions";

Deno.test("candidate validation accepts compact actions before normalization", () => {
  assertEquals(
    isValidCandidateContent(
      '{"r":"ok","a":[{"t":"show_confirm_card","p":{"meals":[]}}]}',
    ),
    true,
  );
  assertEquals(
    isValidCandidateContent('{"r":"ok","a":[null]}'),
    false,
  );
});

type CapturedRequest = {
  url: string | URL | Request;
  init?: RequestInit;
  body: Record<string, unknown>;
};

function setupMocks() {
  const originalFetch = globalThis.fetch;
  const originalEnvGet = Deno.env.get;
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
      return Promise.resolve(new Response(JSON.stringify({
        choices: [{ message: { content: '{"r":"backup","a":[]}' } }],
      }), { status: 200, headers: { "Content-Type": "application/json" } }));
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

  Deno.env.get = (key: string): string | undefined => {
    if (key === "MOONSHOT_API_KEY") return "test-key";
    return originalEnvGet(key);
  };

  return {
    captured,
    restore: () => {
      globalThis.fetch = originalFetch;
      Deno.env.get = originalEnvGet;
    },
  };
}

function buildRequest(body: unknown): Request {
  return new Request("http://localhost/", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
}

async function readSseEvents(
  response: Response,
): Promise<Record<string, unknown>[]> {
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

Deno.test("streaming handler: text-only request keeps string user content", async () => {
  const mocks = setupMocks();
  try {
    const response = await handler(buildRequest({
      date: "2026-06-26",
      userText: "hello",
    }));

    assertEquals(response.status, 200);
    assertEquals(
      response.headers.get("Content-Type"),
      "text/event-stream; charset=utf-8",
    );

    const events = await readSseEvents(response);
    const finalEvent = events.find((e) => e.event === "final");
    assertEquals(finalEvent !== undefined, true);
    const finalData = finalEvent!.data as Record<string, unknown>;
    assertEquals(finalData.reply, "ok");
    assertEquals(finalData.actions, []);
    assertEquals(typeof finalData.debugTiming, "object");

    assertEquals(mocks.captured.length, 1);
    const outbound = mocks.captured[0].body;
    const messages = outbound.messages as Record<string, unknown>[];
    assertEquals(messages[1].role, "user");
    assertEquals(typeof messages[1].content, "string");
  } finally {
    mocks.restore();
  }
});

Deno.test("streaming handler: vision request uses array user content", async () => {
  const mocks = setupMocks();
  try {
    const response = await handler(buildRequest({
      date: "2026-06-26",
      userText: "look at this",
      attachments: [{
        mediaId: "m1",
        mimeType: "image/jpeg",
        base64: VALID_BASE64_4B,
      }],
    }));

    assertEquals(response.status, 200);
    const events = await readSseEvents(response);
    const errorEvents = events.filter((e) => e.event === "error");
    assertEquals(errorEvents.length, 0);

    assertEquals(mocks.captured.length, 3);
    const outbound = mocks.captured[0].body;
    const messages = outbound.messages as Record<string, unknown>[];
    assertEquals(messages[1].role, "user");
    const content = messages[1].content as Record<string, unknown>[];
    assertEquals(Array.isArray(content), true);
    assertEquals(content.length, 2);
    assertEquals(content[0].type, "text");
    assertEquals(
      (content[0] as { text: string }).text.includes("User:look at this"),
      true,
    );
    assertEquals(
      (content[0] as { text: string }).text.includes("attachment_1: image 1"),
      true,
    );
    assertEquals(
      (content[0] as { text: string }).text.includes("mediaId"),
      false,
    );
    assertEquals(content[1], {
      type: "image_url",
      image_url: { url: `data:image/jpeg;base64,${VALID_BASE64_4B}` },
    });
  } finally {
    mocks.restore();
  }
});

Deno.test("streaming handler: interaction_result with attachments emits error event", async () => {
  const mocks = setupMocks();
  try {
    const response = await handler(buildRequest({
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
    }));

    assertEquals(response.status, 200);
    const events = await readSseEvents(response);
    const errorEvents = events.filter((e) => e.event === "error");
    assertEquals(errorEvents.length, 1);
    assertEquals(
      (errorEvents[0].data as { code: string }).code,
      "ATTACHMENTS_NOT_ALLOWED_FOR_TURN_TYPE",
    );
    assertEquals(mocks.captured.length, 0);
  } finally {
    mocks.restore();
  }
});

Deno.test("streaming handler: vision meal selection creates confirm action even when model omits actions", async () => {
  const mocks = setupMocks();
  try {
    const response = await handler(buildRequest({
      date: "2026-06-29",
      userText: "selected lunch",
      turnType: "interaction_result",
      interactionResult: {
        interactionId: "meal-card",
        actionType: "ask_missing_info_card",
        selectedOptionId: "lunch",
        selectedOptionLabel: "Lunch",
        continuationContext: {
          schemaVersion: 1,
          recognizedFoods: [{
            name: "apple",
            amountText: "1 item",
            calories: 95,
            proteinG: 0.5,
          }],
        },
      },
    }));

    const events = await readSseEvents(response);
    const final = events.find((event) => event.event === "final")!
      .data as Record<string, unknown>;
    const actions = final.actions as Record<string, unknown>[];
    const payload = actions[0].payload as Record<string, unknown>;
    const meals = payload.meals as Record<string, unknown>[];
    const items = meals[0].items as Record<string, unknown>[];
    assertEquals(actions[0].type, "show_confirm_card");
    assertEquals(meals[0].mealType, "lunch");
    assertEquals(items[0].name, "apple");
    const outboundMessages = mocks.captured[0].body.messages as Record<
      string,
      unknown
    >[];
    assertEquals(
      (outboundMessages[1].content as string).includes("ContinuationContext:"),
      true,
    );
  } finally {
    mocks.restore();
  }
});

Deno.test("streaming handler: empty attachments array keeps string content", async () => {
  const mocks = setupMocks();
  try {
    await handler(buildRequest({
      date: "2026-06-26",
      userText: "hello",
      attachments: [],
    }));

    assertEquals(mocks.captured.length, 1);
    const messages = mocks.captured[0].body.messages as Record<
      string,
      unknown
    >[];
    assertEquals(typeof messages[1].content, "string");
  } finally {
    mocks.restore();
  }
});

Deno.test("streaming handler: outbound URL is Moonshot API", async () => {
  const mocks = setupMocks();
  try {
    await handler(buildRequest({ date: "2026-06-26", userText: "hello" }));
    assertEquals(mocks.captured[0].url, MOONSHOT_API_URL);
  } finally {
    mocks.restore();
  }
});
