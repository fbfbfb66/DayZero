package com.example.domain.model.ai

/**
 * 混合意图分类结果的意图枚举。
 * 由 LocalIntentRouter 或远程 Kimi classify-user-intent 返回。
 */
enum class HybridIntent {
    FoodLogging,
    FoodInfoQuery,
    CravingSupport,
    EmotionalFoodLogging,
    WeightLogging,
    DailyAdvice,
    DailySummary,
    FoodEdit,
    FoodDelete,
    GeneralChat,
    Unsupported
}
