import { assertEquals } from "jsr:@std/assert@1";
import {
  detectExplicitMealAttachmentAssignments,
  detectMealHints,
  ensureInteractionContinuationAction,
  normalizeActions,
  normalizeNullableNonNegativeNumber,
} from "./normalization.ts";
import {
  ensureInteractionContinuationAction
    as ensureStreamInteractionContinuationAction,
  normalizeActions as normalizeStreamActions,
} from "../assistant-turn-v2-stream/normalization.ts";

type JsonObject = Record<string, unknown>;

function payloadOf(action: JsonObject): JsonObject {
  return action.payload as JsonObject;
}

function mealsOf(action: JsonObject): JsonObject[] {
  return payloadOf(action).meals as JsonObject[];
}

function itemsOf(meal: JsonObject): JsonObject[] {
  return meal.items as JsonObject[];
}

Deno.test("meal source media ids allow only attachments, dedupe across meals, and keep attachment order", () => {
  const actions: JsonObject[] = [{
    type: "show_confirm_card",
    payload: {
      meals: [
        {
          mealType: "lunch",
          items: [{ id: "fixture_item", name: "fixture", calories: 0 }],
          sourceMediaIds: ["m2", "fake", "m2"],
        },
        {
          mealType: "dinner",
          items: [{ id: "fixture_item", name: "fixture", calories: 0 }],
          sourceMediaIds: ["m2", "m1", " "],
        },
      ],
    },
  }];
  normalizeActions(actions, "2026-07-07", "food", null, {
    mediaIds: ["m1", "m2"],
  });
  assertEquals(mealsOf(actions[0])[0].sourceMediaIds, ["m2"]);
  assertEquals(mealsOf(actions[0])[1].sourceMediaIds, ["m1"]);
});

Deno.test("meal source media refs convert attachment aliases and indexes to real media ids", () => {
  const actions: JsonObject[] = [{
    type: "show_confirm_card",
    payload: {
      meals: [
        {
          mealType: "breakfast",
          items: [{ id: "fixture_item", name: "fixture", calories: 0 }],
          sourceMediaIds: ["attachment_1", "image_2", "2", "attachment_1"],
        },
        {
          mealType: "lunch",
          items: [{ id: "fixture_item", name: "fixture", calories: 0 }],
          sourceMediaIds: ["photo_3", "attachment_2"],
        },
      ],
    },
  }];

  normalizeActions(actions, "2026-07-09", "food", null, {
    mediaIds: ["media1", "media2", "media3"],
  });

  assertEquals(mealsOf(actions[0])[0].sourceMediaIds, ["media1", "media2"]);
  assertEquals(mealsOf(actions[0])[1].sourceMediaIds, ["media3"]);
});

Deno.test("meal source media refs drop invalid indexes, unknown aliases, and invented ids", () => {
  const actions: JsonObject[] = [{
    type: "show_confirm_card",
    payload: {
      meals: [
        {
          mealType: "breakfast",
          items: [{ id: "fixture_item", name: "fixture", calories: 0 }],
          sourceMediaIds: [
            "attachment_0",
            "attachment_4",
            "-1",
            "attachment_x",
            "fake-media-id",
            "media2",
          ],
        },
      ],
    },
  }];

  normalizeActions(actions, "2026-07-09", "food", null, {
    mediaIds: ["media1", "media2", "media3"],
  });

  assertEquals(mealsOf(actions[0])[0].sourceMediaIds, ["media2"]);
});

Deno.test("explicit first breakfast second lunch third dinner maps meals to real media ids", () => {
  const text = "第一张是早餐，第二张是午餐，第三张是晚餐，请直接帮我记录。";
  const assignments = detectExplicitMealAttachmentAssignments(text, [
    "media1",
    "media2",
    "media3",
  ]);
  assertEquals(assignments.get("breakfast"), "media1");
  assertEquals(assignments.get("lunch"), "media2");
  assertEquals(assignments.get("dinner"), "media3");

  const actions: JsonObject[] = [{
    type: "show_confirm_card",
    payload: {
      meals: [
        {
          mealType: "breakfast",
          items: [{ id: "fixture_item", name: "fixture", calories: 0 }],
        },
        {
          mealType: "lunch",
          items: [{ id: "fixture_item", name: "fixture", calories: 0 }],
        },
        {
          mealType: "dinner",
          items: [{ id: "fixture_item", name: "fixture", calories: 0 }],
        },
      ],
    },
  }];
  normalizeActions(actions, "2026-07-09", text, null, {
    mediaIds: ["media1", "media2", "media3"],
  });

  assertEquals(mealsOf(actions[0])[0].sourceMediaIds, ["media1"]);
  assertEquals(mealsOf(actions[0])[1].sourceMediaIds, ["media2"]);
  assertEquals(mealsOf(actions[0])[2].sourceMediaIds, ["media3"]);
});

Deno.test("explicit three meals phrase maps sequential attachment order", () => {
  const actions: JsonObject[] = [{
    type: "show_confirm_card",
    payload: {
      meals: [
        {
          mealType: "breakfast",
          items: [{ id: "fixture_item", name: "fixture", calories: 0 }],
        },
        {
          mealType: "lunch",
          items: [{ id: "fixture_item", name: "fixture", calories: 0 }],
        },
        {
          mealType: "dinner",
          items: [{ id: "fixture_item", name: "fixture", calories: 0 }],
        },
      ],
    },
  }];

  normalizeActions(actions, "2026-07-09", "三张分别是早餐、午餐、晚餐", null, {
    mediaIds: ["media1", "media2", "media3"],
  });

  assertEquals(mealsOf(actions[0])[0].sourceMediaIds, ["media1"]);
  assertEquals(mealsOf(actions[0])[1].sourceMediaIds, ["media2"]);
  assertEquals(mealsOf(actions[0])[2].sourceMediaIds, ["media3"]);
});

Deno.test("single meal receives deterministic attachment default while multiple meals do not", () => {
  const single: JsonObject[] = [{
    type: "show_confirm_card",
    payload: {
      meals: [{
        mealType: "lunch",
        items: [{ id: "fixture_item", name: "fixture", calories: 0 }],
      }],
    },
  }];
  normalizeActions(single, "2026-07-07", "food", null, {
    mediaIds: ["m2", "m1"],
  });
  assertEquals(mealsOf(single[0])[0].sourceMediaIds, ["m2", "m1"]);

  const multiple: JsonObject[] = [{
    type: "show_confirm_card",
    payload: {
      meals: [
        {
          mealType: "lunch",
          items: [{ id: "fixture_item", name: "fixture", calories: 0 }],
        },
        {
          mealType: "dinner",
          items: [{ id: "fixture_item", name: "fixture", calories: 0 }],
        },
      ],
    },
  }];
  normalizeActions(multiple, "2026-07-07", "food", null, { mediaIds: ["m1"] });
  assertEquals(mealsOf(multiple[0])[0].sourceMediaIds, undefined);
  assertEquals(mealsOf(multiple[0])[1].sourceMediaIds, undefined);
});

Deno.test("text-only confirm card cannot retain invented media ids and stream matches fallback", () => {
  const fixture: JsonObject[] = [{
    type: "show_confirm_card",
    id: "fixed",
    payload: {
      meals: [
        {
          mealType: "lunch",
          items: [{ id: "fixture_item", name: "fixture", calories: 0 }],
          sourceMediaIds: ["fake"],
        },
      ],
    },
  }];
  const fallback = structuredClone(fixture);
  const stream = structuredClone(fixture);
  normalizeActions(fallback, "2026-07-07", "food", null, { mediaIds: [] });
  normalizeStreamActions(stream, "2026-07-07", "food", null, { mediaIds: [] });
  assertEquals(mealsOf(fallback[0])[0].sourceMediaIds, []);
  assertEquals(fallback, stream);
});

Deno.test("normalizeNullableNonNegativeNumber keeps only finite non-negative numbers", () => {
  assertEquals(normalizeNullableNonNegativeNumber(10), 10);
  assertEquals(normalizeNullableNonNegativeNumber(2.5), 2.5);
  assertEquals(normalizeNullableNonNegativeNumber(0), 0);
  assertEquals(normalizeNullableNonNegativeNumber(null), null);
  assertEquals(normalizeNullableNonNegativeNumber(undefined), null);
  assertEquals(normalizeNullableNonNegativeNumber(-5), null);
  assertEquals(normalizeNullableNonNegativeNumber(NaN), null);
  assertEquals(normalizeNullableNonNegativeNumber(Infinity), null);
  assertEquals(normalizeNullableNonNegativeNumber(-Infinity), null);
  assertEquals(normalizeNullableNonNegativeNumber("10"), null);
  assertEquals(normalizeNullableNonNegativeNumber(""), null);
  assertEquals(normalizeNullableNonNegativeNumber(true), null);
  assertEquals(normalizeNullableNonNegativeNumber({ value: 10 }), null);
  assertEquals(normalizeNullableNonNegativeNumber([10]), null);
});

Deno.test("normalizeActions normalizes nutrition fields on every confirm-card meal item", () => {
  const actions: JsonObject[] = [
    {
      type: "show_confirm_card",
      payload: {
        weightKg: 66,
        meals: [
          {
            mealType: "lunch",
            items: [
              {
                name: "item1",
                amountText: "1份",
                calories: 300,
                calorieConfidence: "manual",
                carbohydratesG: 50,
                proteinG: 15.5,
                fatG: 0,
                fiberG: null,
              },
              {
                name: "item2",
                amountText: "100g",
                calories: 150,
                carbohydratesG: -5,
                proteinG: NaN,
                fatG: Infinity,
                fiberG: -Infinity,
              },
            ],
          },
          {
            mealType: "dinner",
            items: [
              {
                name: "item3",
                amountText: "1碗",
                calories: 200,
                carbohydratesG: "30",
                proteinG: "",
                fatG: true,
                fiberG: { grams: 3 },
              },
              {
                name: "item4",
                amountText: "1个",
                calories: 80,
                carbohydratesG: [1],
              },
            ],
          },
        ],
      },
    },
  ];

  normalizeActions(actions, "2026-06-26", "original text", null);

  const firstMealItems = itemsOf(mealsOf(actions[0])[0]);
  assertEquals(firstMealItems[0].carbohydratesG, 50);
  assertEquals(firstMealItems[0].proteinG, 15.5);
  assertEquals(firstMealItems[0].fatG, 0);
  assertEquals(firstMealItems[0].fiberG, null);
  assertEquals(firstMealItems[1].carbohydratesG, null);
  assertEquals(firstMealItems[1].proteinG, null);
  assertEquals(firstMealItems[1].fatG, null);
  assertEquals(firstMealItems[1].fiberG, null);

  const secondMealItems = itemsOf(mealsOf(actions[0])[1]);
  assertEquals(secondMealItems[0].carbohydratesG, null);
  assertEquals(secondMealItems[0].proteinG, null);
  assertEquals(secondMealItems[0].fatG, null);
  assertEquals(secondMealItems[0].fiberG, null);
  assertEquals(secondMealItems[1].carbohydratesG, null);
  assertEquals(secondMealItems[1].proteinG, null);
  assertEquals(secondMealItems[1].fatG, null);
  assertEquals(secondMealItems[1].fiberG, null);

  assertEquals(firstMealItems[0].name, "item1");
  assertEquals(firstMealItems[0].amountText, "1份");
  assertEquals(firstMealItems[0].calories, 300);
  assertEquals(firstMealItems[0].calorieConfidence, "manual");
  assertEquals(mealsOf(actions[0])[0].mealType, "lunch");
  assertEquals(payloadOf(actions[0]).weightKg, 66);
});

Deno.test("normalizeActions fills missing legacy nutrition fields with null", () => {
  const actions: JsonObject[] = [
    {
      type: "show_confirm_card",
      payload: {
        meals: [
          {
            mealType: "dinner",
            items: [
              {
                name: "apple",
                amountText: "1个",
                calories: 80,
              },
            ],
          },
        ],
      },
    },
  ];

  normalizeActions(actions, "2026-06-26", "apple", null);

  const appleItem = itemsOf(mealsOf(actions[0])[0])[0];
  assertEquals(appleItem.carbohydratesG, null);
  assertEquals(appleItem.proteinG, null);
  assertEquals(appleItem.fatG, null);
  assertEquals(appleItem.fiberG, null);
  assertEquals(appleItem.calories, 80);
});

Deno.test("normalizeActions does not add nutrition fields to non-confirm cards", () => {
  const actions: JsonObject[] = [
    {
      type: "ask_record_intent_card",
      payload: {},
    },
  ];

  normalizeActions(actions, "2026-06-26", "apple", null);

  assertEquals(payloadOf(actions[0]).carbohydratesG, undefined);
  assertEquals(payloadOf(actions[0]).proteinG, undefined);
  assertEquals(payloadOf(actions[0]).fatG, undefined);
  assertEquals(payloadOf(actions[0]).fiberG, undefined);
});

Deno.test("normalizeActions prefills weightKg from todayRecord without changing item fields", () => {
  const actions: JsonObject[] = [
    {
      type: "show_confirm_card",
      payload: {
        meals: [
          {
            mealType: "breakfast",
            items: [
              {
                name: "egg",
                amountText: "1个",
                calories: 70,
                carbohydratesG: 1,
                proteinG: 6,
                fatG: 5,
                fiberG: 0,
              },
            ],
          },
        ],
      },
    },
  ];

  normalizeActions(actions, "2026-06-26", "egg", { weightKg: 70.5 });

  assertEquals(payloadOf(actions[0]).weightKg, 70.5);
  const egg = itemsOf(mealsOf(actions[0])[0])[0];
  assertEquals(egg.carbohydratesG, 1);
  assertEquals(egg.proteinG, 6);
  assertEquals(egg.fatG, 5);
  assertEquals(egg.fiberG, 0);
});

Deno.test("streaming and fallback normalization produce the same output for the same fixture", () => {
  const fixture: JsonObject[] = [
    {
      t: "show_confirm_card",
      id: "confirm_fixed",
      p: {
        meals: [
          {
            mealType: "snack",
            items: [
              {
                id: "item_fixed",
                name: "banana",
                amountText: "1根",
                calories: 105,
                carbohydratesG: 27,
                proteinG: "bad",
                fatG: 0.3,
              },
            ],
          },
        ],
      },
    },
  ];
  const fallbackActions = structuredClone(fixture);
  const streamActions = structuredClone(fixture);

  normalizeActions(fallbackActions, "2026-06-26", "banana", { weightKg: 68 });
  normalizeStreamActions(streamActions, "2026-06-26", "banana", {
    weightKg: 68,
  });

  assertEquals(fallbackActions, streamActions);
});

Deno.test("vision meal selection deterministically creates confirm card from continuation context", () => {
  const context = {
    schemaVersion: 1,
    originalText: "",
    weightKg: 71.2,
    mediaIds: ["11111111-1111-4111-8111-111111111111"],
    recognizedFoods: [{
      id: "item-fixed",
      name: "apple",
      amountText: "1 item",
      calories: 95,
      calorieConfidence: "estimated",
      carbohydratesG: 25,
      proteinG: 0.5,
      fatG: 0.3,
      fiberG: 4.4,
      imageUrl: "https://forbidden.example/image.jpg",
    }],
    futureCompatibleField: { nested: true },
    base64: "forbidden",
    filePath: "C:\\private\\photo.jpg",
  };
  const fallbackActions: JsonObject[] = [];
  const streamActions: JsonObject[] = [];

  ensureInteractionContinuationAction(
    fallbackActions,
    "ask_missing_info_card",
    "lunch",
    context,
  );
  ensureStreamInteractionContinuationAction(
    streamActions,
    "ask_missing_info_card",
    "lunch",
    context,
  );
  fallbackActions[0].id = "confirm-fixed";
  streamActions[0].id = "confirm-fixed";
  normalizeActions(fallbackActions, "2026-06-29", "", null, {
    inheritedContinuationContext: context,
    selectedMealType: "lunch",
  });
  normalizeStreamActions(streamActions, "2026-06-29", "", null, {
    inheritedContinuationContext: context,
    selectedMealType: "lunch",
  });

  const payload = payloadOf(fallbackActions[0]);
  const storedContext = payload.continuationContext as JsonObject;
  assertEquals(fallbackActions, streamActions);
  assertEquals(fallbackActions[0].type, "show_confirm_card");
  assertEquals(
    (mealsOf(fallbackActions[0])[0].items as JsonObject[])[0].name,
    "apple",
  );
  assertEquals(
    (mealsOf(fallbackActions[0])[0].items as JsonObject[])[0].proteinG,
    0.5,
  );
  assertEquals(payload.weightKg, 71.2);
  assertEquals(
    (storedContext.futureCompatibleField as JsonObject).nested,
    true,
  );
  assertEquals(storedContext.base64, undefined);
  assertEquals(storedContext.filePath, undefined);
  assertEquals(
    (storedContext.recognizedFoods as JsonObject[])[0].imageUrl,
    undefined,
  );
});

Deno.test("vision record intent selection preserves context into missing-meal card", () => {
  const actions: JsonObject[] = [];
  const context = {
    recognizedFoods: [{ name: "soup", amountText: "1 bowl", calories: 180 }],
  };

  ensureInteractionContinuationAction(
    actions,
    "ask_record_intent_card",
    "record",
    context,
  );
  normalizeActions(actions, "2026-06-29", "", null, {
    inheritedContinuationContext: context,
  });

  assertEquals(actions[0].type, "ask_missing_info_card");
  assertEquals(
    ((payloadOf(actions[0]).continuationContext as JsonObject)
      .recognizedFoods as JsonObject[])[0].name,
    "soup",
  );
});

Deno.test("final action sanitizer removes ask cards when confirm exists in any order", () => {
  const fixtures: JsonObject[][] = [
    [
      {
        id: "confirm_1",
        type: "show_confirm_card",
        payload: {
          meals: [{
            mealType: "lunch",
            items: [{ id: "fixture_item", name: "fixture", calories: 0 }],
          }],
        },
      },
      { type: "ask_missing_info_card" },
    ],
    [
      { type: "ask_missing_info_card" },
      {
        id: "confirm_1",
        type: "show_confirm_card",
        payload: {
          meals: [{
            mealType: "lunch",
            items: [{ id: "fixture_item", name: "fixture", calories: 0 }],
          }],
        },
      },
    ],
    [
      {
        id: "confirm_1",
        type: "show_confirm_card",
        payload: {
          meals: [{
            mealType: "lunch",
            items: [{ id: "fixture_item", name: "fixture", calories: 0 }],
          }],
        },
      },
      { type: "ask_record_intent_card" },
    ],
  ];

  for (const fixture of fixtures) {
    const fallbackActions = structuredClone(fixture);
    const streamActions = structuredClone(fixture);
    normalizeActions(fallbackActions, "2026-07-09", "午餐", null);
    normalizeStreamActions(streamActions, "2026-07-09", "午餐", null);
    assertEquals(fallbackActions, streamActions);
    assertEquals(fallbackActions.length, 1);
    assertEquals(fallbackActions[0].type, "show_confirm_card");
  }
});

Deno.test("final action sanitizer keeps one deterministic ask without confirm", () => {
  const actions: JsonObject[] = [
    { type: "ask_record_intent_card" },
    { type: "ask_missing_info_card" },
    { type: "ask_missing_info_card" },
  ];
  normalizeActions(actions, "2026-07-09", "吃了苹果", null);
  assertEquals(actions.length, 1);
  assertEquals(actions[0].type, "ask_missing_info_card");
});

Deno.test("explicit multi-meal text has meal hints and removes meal-type ask without confirm", () => {
  assertEquals(detectMealHints("第一张早餐，第二张午餐，第三张晚餐"), [
    "breakfast",
    "lunch",
    "dinner",
  ]);
  const actions: JsonObject[] = [{ type: "ask_missing_info_card" }];
  normalizeActions(
    actions,
    "2026-07-09",
    "第一张早餐，第二张午餐，第三张晚餐",
    null,
  );
  assertEquals(actions, []);
});
