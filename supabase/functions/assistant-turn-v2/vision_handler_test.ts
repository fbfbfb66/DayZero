import { assertEquals } from "jsr:@std/assert@1";
import { handler } from "./handler.ts";

const VALID_BASE64_4B = "dGVzdA==";
const MOONSHOT_API_URL = "https://api.moonshot.cn/v1/chat/completions";

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
    return Promise.resolve(
      new Response(
        JSON.stringify({
          choices: [{
            message: {
              content: JSON.stringify({ r: "ok", a: [] }),
            },
          }],
        }),
        { status: 200, headers: { "Content-Type": "application/json" } },
      ),
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

Deno.test("fallback handler: text-only request keeps string user content", async () => {
  const mocks = setupMocks();
  try {
    const response = await handler(buildRequest({
      date: "2026-06-26",
      userText: "hello",
    }));

    assertEquals(response.status, 200);
    const responseBody = await response.json();
    assertEquals(responseBody.reply, "ok");
    assertEquals(responseBody.actions, []);

    assertEquals(mocks.captured.length, 1);
    const outbound = mocks.captured[0].body;
    const messages = outbound.messages as Record<string, unknown>[];
    assertEquals(messages.length, 2);
    assertEquals(messages[0].role, "system");
    assertEquals(messages[1].role, "user");
    assertEquals(typeof messages[1].content, "string");
    assertEquals(
      (messages[1].content as string).includes("User:hello"),
      true,
    );
    assertEquals(outbound.model, "kimi-k2.6");
  } finally {
    mocks.restore();
  }
});

Deno.test("fallback handler: vision request uses array user content", async () => {
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

    assertEquals(mocks.captured.length, 1);
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
    assertEquals(content[1], {
      type: "image_url",
      image_url: { url: `data:image/jpeg;base64,${VALID_BASE64_4B}` },
    });
  } finally {
    mocks.restore();
  }
});

Deno.test("fallback handler: interaction_result with attachments returns 400", async () => {
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

    assertEquals(response.status, 400);
    const body = await response.json();
    assertEquals(body.errorCode, "ATTACHMENTS_NOT_ALLOWED_FOR_TURN_TYPE");
    assertEquals(mocks.captured.length, 0);
  } finally {
    mocks.restore();
  }
});

Deno.test("fallback handler: vision meal selection creates confirm action even when model omits actions", async () => {
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

    assertEquals(response.status, 200);
    const body = await response.json();
    assertEquals(body.actions.length, 1);
    assertEquals(body.actions[0].type, "show_confirm_card");
    assertEquals(body.actions[0].payload.meals[0].mealType, "lunch");
    assertEquals(body.actions[0].payload.meals[0].items[0].name, "apple");
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

Deno.test("fallback handler: empty text with attachments returns 400", async () => {
  const mocks = setupMocks();
  try {
    const response = await handler(buildRequest({
      date: "2026-06-26",
      userText: "   ",
      attachments: [{
        mediaId: "m1",
        mimeType: "image/jpeg",
        base64: VALID_BASE64_4B,
      }],
    }));

    assertEquals(response.status, 400);
    const body = await response.json();
    assertEquals(body.errorCode, "EMPTY_VISION_TEXT");
    assertEquals(mocks.captured.length, 0);
  } finally {
    mocks.restore();
  }
});

Deno.test("fallback handler: empty attachments array keeps string content", async () => {
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

Deno.test("fallback handler: outbound URL is Moonshot API", async () => {
  const mocks = setupMocks();
  try {
    await handler(buildRequest({ date: "2026-06-26", userText: "hello" }));
    assertEquals(mocks.captured[0].url, MOONSHOT_API_URL);
  } finally {
    mocks.restore();
  }
});
