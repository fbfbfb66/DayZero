import {
  applyExplicitPhotoMealAssignments,
  parseExplicitPhotoMealHints,
} from "./explicit_photo_meal_assignment.ts";

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

export type PhotoAssignmentDebug = {
  attachmentCount: number;
  explicitPhotoHintCount: number;
  rawActionCount: number;
  rawConfirmCardCount: number;
  rawMealCount: number;
  rawModelPhotoReferenceCount: number;
  normalizedModelPhotoCount: number;
  deterministicPhotoAssignmentCount: number;
  finalPhotoAssignmentCount: number;
  unmatchedExplicitMealHintCount: number;
  photoAssignmentPath:
    | "EXPLICIT_TEXT"
    | "MODEL_REFERENCE"
    | "MIXED"
    | "UNASSIGNED"
    | "NO_CONFIRM_CARD";
};

function countPhotoReferences(meals: unknown): number {
  if (!Array.isArray(meals)) return 0;
  return meals.reduce((count, meal) =>
    count +
    (meal && typeof meal === "object" &&
        Array.isArray((meal as MutableJsonObject).sourceMediaIds)
      ? ((meal as MutableJsonObject).sourceMediaIds as unknown[]).length
      : 0), 0);
}

function initialPhotoAssignmentDebug(
  actions: MutableJsonObject[],
  originalText: string,
  mediaIds: string[],
): PhotoAssignmentDebug {
  const confirms = actions.filter((action) => normalizedType(action) === "show_confirm_card");
  const rawMeals = confirms.flatMap((action) => {
    const payload = action.payload && typeof action.payload === "object"
      ? action.payload as MutableJsonObject
      : {};
    return Array.isArray(payload.meals) ? payload.meals : [];
  });
  return {
    attachmentCount: mediaIds.length,
    explicitPhotoHintCount: parseExplicitPhotoMealHints(originalText, mediaIds.length).assignments
      .size,
    rawActionCount: actions.length,
    rawConfirmCardCount: confirms.length,
    rawMealCount: rawMeals.length,
    rawModelPhotoReferenceCount: countPhotoReferences(rawMeals),
    normalizedModelPhotoCount: 0,
    deterministicPhotoAssignmentCount: 0,
    finalPhotoAssignmentCount: 0,
    unmatchedExplicitMealHintCount: 0,
    photoAssignmentPath: confirms.length === 0 ? "NO_CONFIRM_CARD" : "UNASSIGNED",
  };
}

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
    const mealType = typeof context.mealType === "string" && isMealType(context.mealType)
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
): PhotoAssignmentDebug {
  // interaction_result requests intentionally carry no image attachments. Their only legal
  // media allow-list is the sanitized continuation created by the preceding vision turn.
  // Reusing it here preserves model attachment_N / media-id assignments without guessing from
  // the conversation or exposing any new client-side source.
  const effectiveMediaIds = resolveContinuationMediaIds(continuationOptions);
  const photoDebug = initialPhotoAssignmentDebug(
    actions,
    originalText,
    effectiveMediaIds,
  );
  for (const action of actions) {
    if (!action || typeof action !== "object") continue;

    // Map compact fields t -> type, p -> payload if present
    if (action.t && !action.type) {
      action.type = action.t;
    }
    if (action.p && !action.payload) {
      action.payload = action.p;
    }

    const payloadBeforeNormalization = action.payload && typeof action.payload === "object"
      ? action.payload as MutableJsonObject
      : {};
    const continuationContext = normalizeContinuationContext(
      action.continuationContext ?? action.c ??
        payloadBeforeNormalization.continuationContext ??
        continuationOptions.inheritedContinuationContext,
      originalText,
      effectiveMediaIds,
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
        payload.weightKg = (action.weightKg !== undefined && action.weightKg !== null)
          ? action.weightKg
          : (continuationContext?.weightKg !== undefined &&
              continuationContext?.weightKg !== null
            ? continuationContext.weightKg
            : (existingWeight !== undefined && existingWeight !== null ? existingWeight : null));
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
      const assignmentResult = normalizeMealSourceMediaIds(
        payload.meals as MutableJsonObject[],
        effectiveMediaIds,
        originalText,
      );
      photoDebug.normalizedModelPhotoCount += assignmentResult.normalizedModelPhotoCount;
      photoDebug.deterministicPhotoAssignmentCount +=
        assignmentResult.deterministicPhotoAssignmentCount;
      photoDebug.unmatchedExplicitMealHintCount += assignmentResult.unmatchedExplicitMealHintCount;
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
      payload.meals = (payload.meals as MutableJsonObject[]).filter((meal) =>
        Array.isArray(meal.items) && meal.items.length > 0
      );
      if ((payload.meals as MutableJsonObject[]).length === 0) {
        action.__dropInvalidConfirmCard = true;
        continue;
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

  sanitizeActions(actions, originalText);
  const finalMeals = actions.filter((action) => normalizedType(action) === "show_confirm_card")
    .flatMap((action) => {
      const payload = action.payload && typeof action.payload === "object"
        ? action.payload as MutableJsonObject
        : {};
      return Array.isArray(payload.meals) ? payload.meals : [];
    });
  photoDebug.finalPhotoAssignmentCount = countPhotoReferences(finalMeals);
  photoDebug.photoAssignmentPath = photoDebug.rawConfirmCardCount === 0
    ? "NO_CONFIRM_CARD"
    : photoDebug.deterministicPhotoAssignmentCount > 0 &&
        photoDebug.normalizedModelPhotoCount > 0
    ? "MIXED"
    : photoDebug.deterministicPhotoAssignmentCount > 0
    ? "EXPLICIT_TEXT"
    : photoDebug.normalizedModelPhotoCount > 0
    ? "MODEL_REFERENCE"
    : "UNASSIGNED";
  return photoDebug;
}

function resolveContinuationMediaIds(options: ContinuationOptions): string[] {
  const direct = options.mediaIds?.filter((id) => typeof id === "string" && id.trim()) ?? [];
  if (direct.length > 0) return direct;
  const inherited = options.inheritedContinuationContext;
  if (!inherited || typeof inherited !== "object" || Array.isArray(inherited)) {
    return [];
  }
  const raw = (inherited as MutableJsonObject).mediaIds;
  if (!Array.isArray(raw)) return [];
  return raw.filter((id): id is string =>
    typeof id === "string" && /^[A-Za-z0-9._-]{1,160}$/.test(id)
  );
}

export function sanitizeActions(
  actions: MutableJsonObject[],
  originalText = "",
) {
  const validActions = actions.filter((action) => action.__dropInvalidConfirmCard !== true);
  actions.splice(0, actions.length, ...validActions);
  const hasConfirm = actions.some((action) => normalizedType(action) === "show_confirm_card");
  const mealHints = detectMealHints(originalText);
  const sanitized = hasConfirm
    ? actions.filter((action) => !isPreAnswerAction(action))
    : keepOneAskAction(
      actions.filter((action) => !isMealTypeAsk(action) || mealHints.length === 0),
    );

  actions.splice(0, actions.length, ...sanitized);
}

export function detectMealHints(text: string): string[] {
  const source = text.toLowerCase();
  const result: string[] = [];
  const specs: Array<[string, RegExp[]]> = [
    ["breakfast", [/早餐|早饭|早點|早点|早上|上午|清晨|早晨|breakfast/i]],
    ["lunch", [/午餐|午饭|中午|午间|lunch/i]],
    ["dinner", [/晚餐|晚饭|晚上|晚间|傍晚|夜宵|dinner|supper/i]],
    ["snack", [/加餐|零食|点心|下午茶|宵夜|snack/i]],
  ];
  for (const [mealType, patterns] of specs) {
    if (patterns.some((pattern) => pattern.test(source))) {
      result.push(mealType);
    }
  }
  return result;
}

function keepOneAskAction(actions: MutableJsonObject[]): MutableJsonObject[] {
  const preferredAsk =
    actions.find((action) => normalizedType(action) === "ask_missing_info_card") ??
      actions.find((action) => normalizedType(action) === "ask_record_intent_card") ??
      actions.find((action) => normalizedType(action) === "debug_show_choice_card");
  let keptAsk = false;
  return actions.filter((action) => {
    if (!isPreAnswerAction(action)) return true;
    if (action === preferredAsk && !keptAsk) {
      keptAsk = true;
      return true;
    }
    return false;
  });
}

function isPreAnswerAction(action: MutableJsonObject): boolean {
  const type = normalizedType(action);
  return type === "ask_missing_info_card" ||
    type === "ask_record_intent_card" ||
    type === "debug_show_choice_card";
}

function isMealTypeAsk(action: MutableJsonObject): boolean {
  if (normalizedType(action) !== "ask_missing_info_card") return false;
  const payload = action.payload && typeof action.payload === "object"
    ? action.payload as MutableJsonObject
    : {};
  return (payload.field ?? action.field ?? "mealType") === "mealType";
}

function normalizedType(action: MutableJsonObject): string {
  const type = action.type ?? action.t;
  return typeof type === "string" ? type : "";
}

export function normalizeMealSourceMediaIds(
  meals: MutableJsonObject[],
  attachmentMediaIds: string[],
  originalText = "",
): {
  normalizedModelPhotoCount: number;
  deterministicPhotoAssignmentCount: number;
  unmatchedExplicitMealHintCount: number;
} {
  const allowed = attachmentMediaIds
    .map((id) => typeof id === "string" ? id.trim() : "")
    .filter((id, index, all) => id.length > 0 && all.indexOf(id) === index);
  const claimed = new Set<string>();
  const hasExplicit = meals.some((meal) => Array.isArray(meal.sourceMediaIds));

  for (const meal of meals) {
    const requested = Array.isArray(meal.sourceMediaIds) ? meal.sourceMediaIds : null;
    const normalized = requested == null
      ? []
      : normalizeRequestedMediaReferences(requested, allowed, claimed);
    if (normalized.length > 0) {
      meal.sourceMediaIds = normalized;
      continue;
    }

    if (requested != null) {
      meal.sourceMediaIds = [];
    }
  }

  if (
    !hasExplicit && meals.length === 1 && allowed.length >= 1 &&
    allowed.length <= 6
  ) {
    meals[0].sourceMediaIds = [...allowed];
  }
  const normalizedModelPhotoCount = countPhotoReferences(meals);
  const explicit = applyExplicitPhotoMealAssignments(
    meals,
    allowed,
    originalText,
  );
  return {
    normalizedModelPhotoCount,
    deterministicPhotoAssignmentCount: explicit.deterministicPhotoAssignmentCount,
    unmatchedExplicitMealHintCount: explicit.unmatchedExplicitMealHintCount,
  };
}

function normalizeRequestedMediaReferences(
  rawReferences: unknown[],
  allowed: string[],
  claimed: Set<string>,
): string[] {
  const result: string[] = [];
  const localSeen = new Set<string>();
  for (const raw of rawReferences) {
    const resolved = resolveAttachmentReference(raw, allowed);
    if (!resolved || claimed.has(resolved) || localSeen.has(resolved)) continue;
    claimed.add(resolved);
    localSeen.add(resolved);
    result.push(resolved);
  }
  return result;
}

function resolveAttachmentReference(
  raw: unknown,
  allowed: string[],
): string | null {
  if (typeof raw !== "string" && typeof raw !== "number") return null;
  const text = String(raw).trim();
  if (!text) return null;
  if (allowed.includes(text)) return text;

  const normalized = text.toLowerCase();
  const aliasMatch = normalized.match(
    /^(?:attachment|image|photo|img|pic|picture)[_-]?([1-9][0-9]*)$/,
  );
  const indexText = aliasMatch?.[1] ??
    (/^[1-9][0-9]*$/.test(normalized) ? normalized : null);
  if (!indexText) return null;
  const index = Number(indexText);
  if (!Number.isInteger(index) || index < 1 || index > allowed.length) {
    return null;
  }
  return allowed[index - 1] ?? null;
}

export function detectExplicitMealAttachmentAssignments(
  text: string,
  allowedMediaIds: string[],
): Map<string, string> {
  const assignments = new Map<string, string>();
  if (allowedMediaIds.length === 0 || text.trim().length === 0) {
    return assignments;
  }

  const normalized = text.toLowerCase();
  const mealPattern =
    "(breakfast|lunch|dinner|supper|snack|早餐|早饭|早點|早点|早上|午餐|午饭|中午|晚餐|晚饭|晚上|加餐|零食|点心)";
  const ordinalPattern =
    "(?:第\\s*([一二三四五六1-6])\\s*(?:张|張|幅|个|個)?|(?:图|圖|照片|image|photo|pic|picture)\\s*([1-6]))";
  const direct = new RegExp(
    `${ordinalPattern}[^\\n，,。；;]{0,20}(?:是|为|為|对应|屬於|属于|=|:|：)?\\s*${mealPattern}`,
    "gi",
  );
  for (const match of normalized.matchAll(direct)) {
    const index = ordinalToIndex2(match[1] ?? match[2]);
    const meal = normalizeMealToken(match[3]);
    if (
      !index || !meal || index > allowedMediaIds.length || assignments.has(meal)
    ) {
      continue;
    }
    assignments.set(meal, allowedMediaIds[index - 1]);
  }

  if (assignments.size === 0) {
    const hasSequentialSignal = /分别|分別|依次|一日三餐/.test(normalized);
    const breakfastAt = findMealTokenIndex(normalized, "breakfast");
    const lunchAt = findMealTokenIndex(normalized, "lunch");
    const dinnerAt = findMealTokenIndex(normalized, "dinner");
    if (
      hasSequentialSignal && allowedMediaIds.length >= 3 &&
      breakfastAt >= 0 && lunchAt > breakfastAt && dinnerAt > lunchAt
    ) {
      assignments.set("breakfast", allowedMediaIds[0]);
      assignments.set("lunch", allowedMediaIds[1]);
      assignments.set("dinner", allowedMediaIds[2]);
    }
  }

  return assignments;
}

function findMealTokenIndex(text: string, mealType: string): number {
  const patterns: Record<string, RegExp> = {
    breakfast: /breakfast|早餐|早饭|早點|早点|早上/i,
    lunch: /lunch|午餐|午饭|中午/i,
    dinner: /dinner|supper|晚餐|晚饭|晚上/i,
    snack: /snack|加餐|零食|点心/i,
  };
  const match = text.match(patterns[mealType]);
  return match?.index ?? -1;
}

function ordinalToIndex2(value: string | undefined): number | null {
  if (!value) return null;
  const table: Record<string, number> = {
    "一": 1,
    "二": 2,
    "三": 3,
    "四": 4,
    "五": 5,
    "六": 6,
  };
  return table[value] ?? (Number(value) || null);
}

function normalizeMealToken(value: string | undefined): string | null {
  if (!value) return null;
  const token = value.toLowerCase();
  if (/breakfast|早餐|早饭|早點|早点|早上/.test(token)) return "breakfast";
  if (/lunch|午餐|午饭|中午/.test(token)) return "lunch";
  if (/dinner|supper|晚餐|晚饭|晚上/.test(token)) return "dinner";
  if (/snack|加餐|零食|点心/.test(token)) return "snack";
  return null;
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
  const safeMediaIds = mediaIds.filter((id) => /^[A-Za-z0-9._-]{1,160}$/.test(id));
  return {
    ...source,
    schemaVersion: 1,
    originalText: typeof source.originalText === "string" && source.originalText.trim()
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
