import { assertEquals } from "jsr:@std/assert@1";
import {
  applyExplicitPhotoMealAssignments,
  parseExplicitPhotoMealHints,
} from "./explicit_photo_meal_assignment.ts";
import { normalizeActions as normalizeFallback } from "../assistant-turn-v2/normalization.ts";
import { normalizeActions as normalizeStream } from "../assistant-turn-v2-stream/normalization.ts";

type JsonObject = Record<string, unknown>;

const text = {
  direct:
    "\u7b2c\u4e00\u5f20\u662f\u65e9\u9910\uff0c\u7b2c\u4e8c\u5f20\u662f\u5348\u9910\uff0c\u7b2c\u4e09\u5f20\u662f\u665a\u9910\u3002",
  compact:
    "\u7b2c1\u5f20\u65e9\u9910\uff0c\u56fe2\u5f20\u5348\u9910\uff0c\u56fe\u72473\u665a\u9910\u3002",
  list:
    "\u4e09\u5f20\u5206\u522b\u662f\u65e9\u9910\u3001\u5348\u9910\u3001\u665a\u9910\u3002",
  ordered:
    "\u8fd9\u4e09\u5f20\u4f9d\u6b21\u662f\u65e9\u9910\u3001\u5348\u9910\u3001\u665a\u9910\u3002",
  english:
    "The first photo is breakfast, the second is lunch, and the third is dinner.",
};

function entries(input: ReturnType<typeof parseExplicitPhotoMealHints>) {
  return [...input.assignments.entries()];
}

Deno.test("explicit photo meal parser supports direct Chinese, list, ordered, and English syntax", () => {
  const expected = [[1, "breakfast"], [2, "lunch"], [3, "dinner"]];
  for (
    const value of [
      text.direct,
      text.compact,
      text.list,
      text.ordered,
      text.english,
    ]
  ) {
    assertEquals(entries(parseExplicitPhotoMealHints(value, 3)), expected);
  }
});

Deno.test("explicit photo meal parser supports Chinese and Arabic indexes one through six", () => {
  const chinese =
    "\u7b2c\u4e00\u5f20\u65e9\u9910\uff0c\u7b2c\u4e8c\u5f20\u5348\u9910\uff0c\u7b2c\u4e09\u5f20\u665a\u9910\uff0c\u7b2c\u56db\u5f20\u52a0\u9910\u3002";
  assertEquals(entries(parseExplicitPhotoMealHints(chinese, 6)), [
    [1, "breakfast"],
    [2, "lunch"],
    [3, "dinner"],
    [4, "snack"],
  ]);
  assertEquals(
    entries(
      parseExplicitPhotoMealHints(
        "photo 1 breakfast, image 2 lunch, picture 3 dinner",
        6,
      ),
    ),
    [[1, "breakfast"], [2, "lunch"], [3, "dinner"]],
  );
});

Deno.test("explicit photo meal parser rejects out of range, conflicts, vague text, mismatched list, and text-only", () => {
  assertEquals(
    entries(
      parseExplicitPhotoMealHints("\u7b2c\u56db\u5f20\u662f\u65e9\u9910", 3),
    ),
    [],
  );
  const conflict = parseExplicitPhotoMealHints(
    "\u7b2c\u4e00\u5f20\u662f\u65e9\u9910\uff0c\u7b2c\u4e00\u5f20\u662f\u5348\u9910",
    3,
  );
  assertEquals(entries(conflict), []);
  assertEquals([...conflict.conflictedIndexes], [1]);
  assertEquals(
    entries(
      parseExplicitPhotoMealHints(
        "\u5927\u6982\u662f\u65e9\u9910\uff0c\u4eca\u5929\u5403\u4e86\u65e9\u9910\u5348\u9910\u665a\u9910",
        3,
      ),
    ),
    [],
  );
  assertEquals(
    entries(
      parseExplicitPhotoMealHints(
        "\u4e09\u5f20\u5206\u522b\u662f\u65e9\u9910\u3001\u5348\u9910",
        3,
      ),
    ),
    [],
  );
  assertEquals(entries(parseExplicitPhotoMealHints(text.direct, 0)), []);
});

function meal(type: string, ids?: unknown[]) {
  return ids === undefined
    ? {
      mealType: type,
      items: [{ id: "fixture_item", name: "fixture", calories: 0 }],
    }
    : {
      mealType: type,
      items: [{ id: "fixture_item", name: "fixture", calories: 0 }],
      sourceMediaIds: ids,
    };
}

Deno.test("explicit assignment injects real ids by meal type and overrides incorrect model references", () => {
  const meals: JsonObject[] = [
    meal("dinner", ["id-two"]),
    meal("breakfast", ["id-two"]),
    meal("lunch"),
  ];
  const result = applyExplicitPhotoMealAssignments(meals, [
    "id-one",
    "id-two",
    "id-three",
  ], text.direct);
  assertEquals(result, {
    explicitPhotoHintCount: 3,
    deterministicPhotoAssignmentCount: 3,
    unmatchedExplicitMealHintCount: 0,
  });
  assertEquals(meals.map((value) => value.sourceMediaIds), [["id-three"], [
    "id-one",
  ], ["id-two"]]);
});

Deno.test("explicit assignment permits multiple photos per meal but never guesses duplicate or missing model meals", () => {
  const multiple: JsonObject[] = [meal("breakfast"), meal("lunch")];
  const result = applyExplicitPhotoMealAssignments(
    multiple,
    ["id-one", "id-two", "id-three"],
    "\u7b2c\u4e00\u5f20\u65e9\u9910\uff0c\u7b2c\u4e8c\u5f20\u65e9\u9910\uff0c\u7b2c\u4e09\u5f20\u5348\u9910",
  );
  assertEquals(result.deterministicPhotoAssignmentCount, 3);
  assertEquals(multiple.map((value) => value.sourceMediaIds), [[
    "id-one",
    "id-two",
  ], ["id-three"]]);
  const ambiguous: JsonObject[] = [
    meal("breakfast", ["id-one"]),
    meal("breakfast"),
    meal("lunch"),
  ];
  const ambiguousResult = applyExplicitPhotoMealAssignments(ambiguous, [
    "id-one",
    "id-two",
  ], "\u7b2c\u4e00\u5f20\u65e9\u9910\uff0c\u7b2c\u4e8c\u5f20\u5348\u9910");
  assertEquals(ambiguousResult, {
    explicitPhotoHintCount: 2,
    deterministicPhotoAssignmentCount: 1,
    unmatchedExplicitMealHintCount: 1,
  });
  assertEquals(ambiguous[0].sourceMediaIds, []);
  assertEquals(ambiguous[2].sourceMediaIds, ["id-two"]);
});

function confirmFixture(): JsonObject[] {
  return [{
    type: "show_confirm_card",
    payload: { meals: [meal("lunch"), meal("dinner"), meal("breakfast")] },
  }];
}

Deno.test("fallback and streaming production normalization share explicit assignment, sanitizer, and safe diagnostics", () => {
  const fallback = confirmFixture();
  const stream = structuredClone(fallback);
  fallback[0].id = "fixed";
  stream[0].id = "fixed";
  const options = { mediaIds: ["id-one", "id-two", "id-three"] };
  const fallbackDebug = normalizeFallback(
    fallback,
    "2026-07-09",
    text.direct,
    null,
    options,
  );
  const streamDebug = normalizeStream(
    stream,
    "2026-07-09",
    text.direct,
    null,
    options,
  );
  assertEquals(fallback, stream);
  assertEquals(fallbackDebug, streamDebug);
  assertEquals(fallbackDebug.explicitPhotoHintCount, 3);
  assertEquals(fallbackDebug.deterministicPhotoAssignmentCount, 3);
  assertEquals(fallbackDebug.finalPhotoAssignmentCount, 3);
  assertEquals(fallbackDebug.photoAssignmentPath, "EXPLICIT_TEXT");
  assertEquals(JSON.stringify(fallbackDebug).includes("id-one"), false);
});

Deno.test("production normalization does not create a confirm card or meals for non-confirm actions", () => {
  const actions: JsonObject[] = [{ type: "ask_missing_info_card" }];
  const debug = normalizeFallback(actions, "2026-07-09", text.direct, null, {
    mediaIds: ["id-one", "id-two", "id-three"],
  });
  assertEquals(actions.length, 0);
  assertEquals(debug.photoAssignmentPath, "NO_CONFIRM_CARD");
  assertEquals(debug.finalPhotoAssignmentCount, 0);
});
