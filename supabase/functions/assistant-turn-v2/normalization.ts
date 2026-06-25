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

export function normalizeActions(
  actions: MutableJsonObject[],
  date: string,
  originalText: string,
  todayRecord?: MutableJsonObject | null,
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
            : (existingWeight !== undefined && existingWeight !== null
              ? existingWeight
              : null);
      }

      // Handle legacy mealType + items compact format
      if (!Array.isArray(payload.meals)) {
        let meals = payload.meals || action.meals || [];
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
