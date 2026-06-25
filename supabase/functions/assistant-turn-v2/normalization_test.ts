import { assertEquals } from "jsr:@std/assert@1";
import {
  normalizeActions,
  normalizeNullableNonNegativeNumber,
} from "./normalization.ts";
import { normalizeActions as normalizeStreamActions } from "../assistant-turn-v2-stream/normalization.ts";

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
