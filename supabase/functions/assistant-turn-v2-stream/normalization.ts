export function normalizeNullableNonNegativeNumber(
  value: unknown,
): number | null {
  return typeof value === "number" &&
      Number.isFinite(value) &&
      value >= 0
    ? value
    : null;
}

export function generateId(prefix: string): string {
  return `${prefix}_${Math.random().toString(36).substring(2, 10)}`;
}

export function getMealLabel(type: string): string {
  switch (type) {
    case "breakfast":
      return "早餐";
    case "lunch":
      return "午餐";
    case "dinner":
      return "晚餐";
    case "snack":
      return "加餐";
    default:
      return type || "";
  }
}

type MutableJsonObject = Record<string, unknown>;

type ContinuationOptions = {
  inheritedContinuationContext?: unknown;
  mediaIds?: string[];
  selectedMealType?: string | null;
};

export function ensureInteractionContinuationAction(
  actions: MutableJsonObject[],
  actionType: string,
  selectedOptionId: string,
  rawContext: unknown,
) {
  const context = normalizeContinuationContext(rawContext, "", []);
  if (
    !context || !Array.isArray(context.recognizedFoods) ||
    context.recognizedFoods.length === 0
  ) return;
  const hasAction = (type: string) =>
    actions.some((action) => action?.type === type || action?.t === type);
  if (actionType === "ask_missing_info_card" && isMealType(selectedOptionId)) {
    if (!hasAction("show_confirm_card")) {
      actions.push({
        type: "show_confirm_card",
        mealType: selectedOptionId,
        continuationContext: context,
      });
    }
  } else if (
    actionType === "ask_record_intent_card" && selectedOptionId === "record"
  ) {
    const mealType =
      typeof context.mealType === "string" && isMealType(context.mealType)
        ? context.mealType
        : null;
    if (mealType && !hasAction("show_confirm_card")) {
      actions.push({
        type: "show_confirm_card",
        mealType,
        continuationContext: context,
      });
    } else if (!mealType && !hasAction("ask_missing_info_card")) {
      actions.push({
        type: "ask_missing_info_card",
        continuationContext: context,
      });
    }
  }
}

export function normalizeActions(
  actions: MutableJsonObject[],
  date: string,
  originalText: string,
  todayRecord?: MutableJsonObject | null,
  continuationOptions: ContinuationOptions = {},
) {
  for (const action of actions) {
    if (!action || typeof action !== "object") continue;

    // Map compact fields t -> type, p -> payload if present
    if (action.t && !action.type) {
      action.type = action.t;
    }
    if (action.p && !action.payload) {
      action.payload = action.p;
    }

    const payloadBeforeNormalization =
      action.payload && typeof action.payload === "object"
        ? action.payload as MutableJsonObject
        : {};
    const continuationContext = normalizeContinuationContext(
      action.continuationContext ?? action.c ??
        payloadBeforeNormalization.continuationContext ??
        continuationOptions.inheritedContinuationContext,
      originalText,
      continuationOptions.mediaIds ?? [],
    );

    if (action.type === "ask_record_intent_card") {
      if (!action.interactionId && !action.id) {
        action.interactionId = generateId("record_intent");
      }
      if (!action.payload) action.payload = {};
      const payload = action.payload as MutableJsonObject;
      payload.title = payload.title || "需要帮你记录吗？";
      payload.message = payload.message ||
        "我看到你提到了刚吃/喝的内容，要不要把它录入今天？";
      payload.originalText = payload.originalText || originalText;
      if (continuationContext) {
        payload.continuationContext = continuationContext;
      }
      if (!payload.options) {
        payload.options = [
          { id: "record", label: "帮我记录" },
          { id: "chat_only", label: "只是聊聊" },
          { id: "not_now", label: "先不用" },
        ];
      }
    } else if (action.type === "ask_missing_info_card") {
      if (!action.interactionId && !action.id) {
        action.interactionId = generateId("missing_info");
      }
      if (!action.payload) action.payload = {};
      const payload = action.payload as MutableJsonObject;
      payload.title = payload.title || "补充一下餐次";
      payload.message = payload.message ||
        "这次饮食算在哪一餐呀？";
      payload.field = payload.field || action.field || "mealType";
      payload.originalText = payload.originalText || originalText;
      if (continuationContext) {
        payload.continuationContext = continuationContext;
      }
      if (!payload.options) {
        payload.options = [
          { id: "breakfast", label: "早餐" },
          { id: "lunch", label: "午餐" },
          { id: "dinner", label: "晚餐" },
          { id: "snack", label: "加餐" },
        ];
      }
    } else if (action.type === "show_confirm_card") {
      if (!action.id && !action.interactionId) {
        action.id = generateId("confirm");
      }
      if (!action.payload) action.payload = {};
      const payload = action.payload as MutableJsonObject;
      payload.confirmType = "food_record";
      payload.title = payload.title || "今日记录草稿";
      payload.message = payload.message ||
        "我先帮你估算了一版，你可以修改后再确认。";
      payload.date = payload.date || date;
      if (continuationContext) {
        payload.continuationContext = continuationContext;
      }

      if (
        payload.weightKg === undefined ||
        payload.weightKg === null
      ) {
        const existingWeight = todayRecord && typeof todayRecord === "object"
          ? todayRecord.weightKg
          : null;
        payload.weightKg =
          (action.weightKg !== undefined && action.weightKg !== null)
            ? action.weightKg
            : (continuationContext?.weightKg !== undefined &&
                continuationContext?.weightKg !== null
              ? continuationContext.weightKg
              : (existingWeight !== undefined && existingWeight !== null
                ? existingWeight
                : null));
      }

      // Handle legacy mealType + items compact format
      if (!Array.isArray(payload.meals)) {
        let meals = payload.meals || action.meals || [];
        if (Array.isArray(meals) && meals.length === 0 && continuationContext) {
          const contextItems = continuationContext.recognizedFoods;
          const contextMealType = action.mealType || payload.mealType ||
            continuationOptions.selectedMealType ||
            continuationContext.mealType;
          if (
            Array.isArray(contextItems) && contextItems.length > 0 &&
            isMealType(contextMealType)
          ) {
            meals = [{ mealType: contextMealType, items: contextItems }];
          }
        }
        if (
          Array.isArray(meals) && meals.length === 0 &&
          (action.mealType || payload.mealType)
        ) {
          const fallbackItems = action.items || payload.items || [];
          if (Array.isArray(fallbackItems) && fallbackItems.length > 0) {
            const typeToUse = action.mealType || payload.mealType;
            meals = [{
              mealType: typeToUse,
              mealLabel: getMealLabel(String(typeToUse ?? "")),
              items: fallbackItems,
            }];
          }
        }
        payload.meals = meals;
      }

      // Calculate totals and normalize items
      let totalCals = 0;
      for (const meal of payload.meals as MutableJsonObject[]) {
        meal.mealLabel = meal.mealLabel ||
          getMealLabel(String(meal.mealType ?? ""));
        let subtotal = 0;
        if (!Array.isArray(meal.items)) meal.items = [];
        for (const item of meal.items as MutableJsonObject[]) {
          if (!item.id) item.id = generateId("item");
          if (item.calorieConfidence === undefined) {
            item.calorieConfidence = "estimated";
          }
          if (typeof item.calories !== "number") item.calories = 0;
          subtotal += item.calories as number;

          item.carbohydratesG = normalizeNullableNonNegativeNumber(
            item.carbohydratesG,
          );
          item.proteinG = normalizeNullableNonNegativeNumber(item.proteinG);
          item.fatG = normalizeNullableNonNegativeNumber(item.fatG);
          item.fiberG = normalizeNullableNonNegativeNumber(item.fiberG);
        }
        meal.subtotalCalories = meal.subtotalCalories !== undefined
          ? meal.subtotalCalories
          : subtotal;
        totalCals += meal.subtotalCalories as number;
      }
      payload.totalCalories = payload.totalCalories !== undefined
        ? payload.totalCalories
        : totalCals;

      if (!payload.buttons) {
        payload.buttons = [
          { id: "confirm", label: "确认记录" },
          { id: "cancel", label: "先不记录" },
        ];
      }
    }
  }
}

function normalizeContinuationContext(
  raw: unknown,
  originalText: string,
  mediaIds: string[],
): MutableJsonObject | null {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return null;
  const source = sanitizeContinuationObject(raw as MutableJsonObject, 0);
  const rawFoods = Array.isArray(source.recognizedFoods)
    ? source.recognizedFoods
    : Array.isArray(source.items)
    ? source.items
    : [];
  const recognizedFoods = rawFoods
    .filter((item): item is MutableJsonObject =>
      Boolean(item) && typeof item === "object" && !Array.isArray(item)
    )
    .map((item) => ({
      ...item,
      name: typeof item.name === "string" ? item.name.trim().slice(0, 120) : "",
      amountText: typeof item.amountText === "string"
        ? item.amountText.trim().slice(0, 120)
        : "1份",
      calories: normalizeNullableNonNegativeNumber(item.calories) ?? 0,
      calorieConfidence: typeof item.calorieConfidence === "string"
        ? item.calorieConfidence
        : "estimated",
      carbohydratesG: normalizeNullableNonNegativeNumber(item.carbohydratesG),
      proteinG: normalizeNullableNonNegativeNumber(item.proteinG),
      fatG: normalizeNullableNonNegativeNumber(item.fatG),
      fiberG: normalizeNullableNonNegativeNumber(item.fiberG),
    }))
    .filter((item) => item.name.length > 0);
  if (recognizedFoods.length === 0) return null;
  const safeMediaIds = mediaIds.filter((id) =>
    /^[A-Za-z0-9._-]{1,160}$/.test(id)
  );
  return {
    ...source,
    schemaVersion: 1,
    originalText:
      typeof source.originalText === "string" && source.originalText.trim()
        ? source.originalText.trim().slice(0, 500)
        : originalText.slice(0, 500),
    mediaIds: safeMediaIds.length > 0
      ? safeMediaIds
      : Array.isArray(source.mediaIds)
      ? source.mediaIds.filter((id): id is string =>
        typeof id === "string" && /^[A-Za-z0-9._-]{1,160}$/.test(id)
      )
      : [],
    mealType: typeof source.mealType === "string" && isMealType(source.mealType)
      ? source.mealType
      : null,
    weightKg: normalizeNullableNonNegativeNumber(source.weightKg),
    recognizedFoods,
  };
}

const FORBIDDEN_CONTINUATION_KEY =
  /(base64|data_?url|image_?url|file_?path|absolute_?path|binary|bytes)/i;

function sanitizeContinuationObject(
  source: MutableJsonObject,
  depth: number,
): MutableJsonObject {
  if (depth > 5) return {};
  const result: MutableJsonObject = {};
  for (const [key, value] of Object.entries(source).slice(0, 80)) {
    if (FORBIDDEN_CONTINUATION_KEY.test(key)) continue;
    const sanitized = sanitizeContinuationValue(value, depth + 1);
    if (sanitized !== undefined) result[key] = sanitized;
  }
  return result;
}

function sanitizeContinuationValue(value: unknown, depth: number): unknown {
  if (value === null || typeof value === "boolean") return value;
  if (typeof value === "number") {
    return Number.isFinite(value) ? value : undefined;
  }
  if (typeof value === "string") {
    const trimmed = value.trim();
    if (
      /^(data:|https?:|file:)/i.test(trimmed) || /^[A-Za-z]:[\\/]/.test(trimmed)
    ) return undefined;
    return trimmed.slice(0, 500);
  }
  if (Array.isArray(value)) {
    if (depth > 5) return [];
    return value.slice(0, 40).flatMap((item) => {
      const sanitized = sanitizeContinuationValue(item, depth + 1);
      return sanitized === undefined ? [] : [sanitized];
    });
  }
  if (value && typeof value === "object") {
    return sanitizeContinuationObject(value as MutableJsonObject, depth);
  }
  return undefined;
}

function isMealType(value: unknown): value is string {
  return value === "breakfast" || value === "lunch" || value === "dinner" ||
    value === "snack";
}
