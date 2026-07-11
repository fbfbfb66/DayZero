export type CanonicalMealType = "breakfast" | "lunch" | "dinner" | "snack";

export type ExplicitPhotoMealHints = {
  assignments: Map<number, CanonicalMealType>;
  conflictedIndexes: Set<number>;
};

export type ExplicitPhotoAssignmentResult = {
  explicitPhotoHintCount: number;
  deterministicPhotoAssignmentCount: number;
  unmatchedExplicitMealHintCount: number;
};

type MutableMeal = Record<string, unknown>;

const CHINESE_ORDINALS: Record<string, number> = {
  "\u4e00": 1,
  "\u4e8c": 2,
  "\u4e09": 3,
  "\u56db": 4,
  "\u4e94": 5,
  "\u516d": 6,
};

const ENGLISH_ORDINALS: Record<string, number> = {
  first: 1,
  second: 2,
  third: 3,
  fourth: 4,
  fifth: 5,
  sixth: 6,
  one: 1,
  two: 2,
  three: 3,
  four: 4,
  five: 5,
  six: 6,
};

const MEAL_PATTERN =
  "(breakfast|lunch|dinner|supper|snack|\\u65e9\\u9910|\\u65e9\\u996d|\\u5348\\u9910|\\u5348\\u996d|\\u665a\\u9910|\\u665a\\u996d|\\u52a0\\u9910|\\u96f6\\u98df)";

function ordinalToIndex(value: string | undefined): number | null {
  if (!value) return null;
  const raw = value.trim().toLowerCase();
  const normalized = /^[1-6](?:st|nd|rd|th)$/.test(raw)
    ? raw.replace(/(?:st|nd|rd|th)$/, "")
    : raw;
  if (/^[1-6]$/.test(normalized)) return Number(normalized);
  return CHINESE_ORDINALS[value] ?? ENGLISH_ORDINALS[normalized] ?? null;
}

export function canonicalMealType(value: unknown): CanonicalMealType | null {
  if (typeof value !== "string") return null;
  const token = value.trim().toLowerCase();
  if (!token) return null;
  if (
    token === "breakfast" || token === "\u65e9\u9910" ||
    token === "\u65e9\u996d"
  ) return "breakfast";
  if (
    token === "lunch" || token === "\u5348\u9910" || token === "\u5348\u996d"
  ) return "lunch";
  if (
    token === "dinner" || token === "supper" || token === "\u665a\u9910" ||
    token === "\u665a\u996d"
  ) return "dinner";
  if (
    token === "snack" || token === "\u52a0\u9910" || token === "\u96f6\u98df"
  ) return "snack";
  return null;
}

function collectMealTokens(text: string): CanonicalMealType[] {
  const tokens: CanonicalMealType[] = [];
  const matcher = new RegExp(MEAL_PATTERN, "gi");
  for (const match of text.matchAll(matcher)) {
    const meal = canonicalMealType(match[1]);
    if (meal) tokens.push(meal);
  }
  return tokens;
}

export function parseExplicitPhotoMealHints(
  userText: string,
  attachmentCount: number,
): ExplicitPhotoMealHints {
  const assignments = new Map<number, CanonicalMealType>();
  const conflictedIndexes = new Set<number>();
  if (!userText.trim() || attachmentCount < 1) {
    return { assignments, conflictedIndexes };
  }
  const add = (index: number | null, meal: CanonicalMealType | null) => {
    if (
      !index || !meal || index > attachmentCount || conflictedIndexes.has(index)
    ) {
      return;
    }
    const current = assignments.get(index);
    if (current && current !== meal) {
      assignments.delete(index);
      conflictedIndexes.add(index);
    } else if (!current) {
      assignments.set(index, meal);
    }
  };

  const chineseDirect = new RegExp(
    `(?:\\u7b2c\\s*([\\u4e00\\u4e8c\\u4e09\\u56db\\u4e94\\u516d1-6])\\s*(?:\\u5f20|\\u5f35|\\u5e45|\\u4e2a|\\u500b)?|(?:\\u56fe|\\u5716|\\u56fe\\u7247|\\u7167\\u7247|photo|image|picture|pic)\\s*([1-6]))\\s*(?:\\u5f20|\\u5f35|\\u5e45|\\u4e2a|\\u500b)?\\s*(?:\\u662f|\\u4e3a|\\u5bf9\\u5e94|is|was|=|:|\\uff1a)?\\s*${MEAL_PATTERN}`,
    "gi",
  );
  for (const match of userText.matchAll(chineseDirect)) {
    add(ordinalToIndex(match[1] ?? match[2]), canonicalMealType(match[3]));
  }

  const englishDirect =
    /(?:the\s+)?(first|second|third|fourth|fifth|sixth|[1-6](?:st|nd|rd|th)?)\s*(?:photo|image|picture|pic)?\s*(?:is|was|=|:)?\s*(breakfast|lunch|dinner|supper|snack)/gi;
  for (const match of userText.matchAll(englishDirect)) {
    add(ordinalToIndex(match[1]), canonicalMealType(match[2]));
  }

  const sequential = new RegExp(
    `(?:\\u8fd9)?([\\u4e00\\u4e8c\\u4e09\\u56db\\u4e94\\u516d1-6]|one|two|three|four|five|six)\\s*(?:\\u5f20|\\u5f35|photos?|images?|pictures?)\\s*(?:\\u5206\\u522b|\\u5206\\u5225|\\u4f9d\\u6b21|respectively|in\\s+order|are)\\s*(?:\\u662f|are|is)?\\s*([^\\n.\\u3002;\\uff1b]{1,100})`,
    "gi",
  );
  for (const match of userText.matchAll(sequential)) {
    const count = ordinalToIndex(match[1]);
    if (!count || count !== attachmentCount) continue;
    const meals = collectMealTokens(match[2]);
    if (meals.length !== count) continue;
    meals.forEach((meal, zeroIndex) => add(zeroIndex + 1, meal));
  }

  return { assignments, conflictedIndexes };
}

function mealCanonicalType(meal: MutableMeal): CanonicalMealType | null {
  return canonicalMealType(meal.mealType) ??
    canonicalMealType(meal.mealLabel) ??
    canonicalMealType(meal.type);
}

export function applyExplicitPhotoMealAssignments(
  meals: MutableMeal[],
  attachmentMediaIds: string[],
  userText: string,
): ExplicitPhotoAssignmentResult {
  const allowed = attachmentMediaIds.filter((id, index, values) =>
    typeof id === "string" && id.trim().length > 0 &&
    values.indexOf(id) === index
  );
  const hints = parseExplicitPhotoMealHints(userText, allowed.length);
  if (hints.assignments.size === 0) {
    return {
      explicitPhotoHintCount: 0,
      deterministicPhotoAssignmentCount: 0,
      unmatchedExplicitMealHintCount: 0,
    };
  }

  const byMeal = new Map<CanonicalMealType, MutableMeal[]>();
  for (const meal of meals) {
    const mealType = mealCanonicalType(meal);
    if (!mealType) continue;
    byMeal.set(mealType, [...(byMeal.get(mealType) ?? []), meal]);
  }
  const explicitIds = new Set(
    [...hints.assignments.keys()].map((index) => allowed[index - 1]).filter(
      Boolean,
    ),
  );
  for (const meal of meals) {
    if (!Array.isArray(meal.sourceMediaIds)) continue;
    meal.sourceMediaIds = meal.sourceMediaIds.filter((id) =>
      typeof id === "string" && !explicitIds.has(id)
    );
  }

  let deterministicPhotoAssignmentCount = 0;
  let unmatchedExplicitMealHintCount = 0;
  for (
    const [index, mealType] of [...hints.assignments.entries()].sort((
      [a],
      [b],
    ) => a - b)
  ) {
    const candidates = byMeal.get(mealType) ?? [];
    const mediaId = allowed[index - 1];
    if (candidates.length !== 1 || !mediaId) {
      unmatchedExplicitMealHintCount++;
      continue;
    }
    const meal = candidates[0];
    const current = Array.isArray(meal.sourceMediaIds)
      ? meal.sourceMediaIds.filter((id): id is string => typeof id === "string")
      : [];
    if (!current.includes(mediaId)) current.push(mediaId);
    current.sort((a, b) => allowed.indexOf(a) - allowed.indexOf(b));
    meal.sourceMediaIds = current;
    deterministicPhotoAssignmentCount++;
  }
  return {
    explicitPhotoHintCount: hints.assignments.size,
    deterministicPhotoAssignmentCount,
    unmatchedExplicitMealHintCount,
  };
}
