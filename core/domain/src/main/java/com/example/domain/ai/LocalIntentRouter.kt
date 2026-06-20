package com.example.domain.ai

import java.math.BigDecimal

enum class EmotionType {
    Anxiety,
    Regret,
    LossOfControl,
    Frustration
}

data class LocalIntentSignals(
    val emotionDetected: Boolean,
    val foodDetected: Boolean,
    val mealTimeDetected: Boolean
)

sealed class LocalUserIntent {
    data class ClearFoodLogging(val text: String) : LocalUserIntent()
    data class WeightLogging(val weightKg: Double, val rawText: String) : LocalUserIntent()
    data class InvalidWeight(val weight: Double, val rawText: String) : LocalUserIntent()
    data class AppCommandClearData(val rawText: String) : LocalUserIntent()
    data class Unknown(val rawText: String) : LocalUserIntent()
}

object LocalIntentRouter {
    private val WEIGHT_KEYWORDS = listOf("体重", "kg", "公斤", "千克", "斤")
    private val ADVICE_KEYWORDS = listOf(
        "吃得怎么样",
        "吃的怎么样",
        "怎么样",
        "分析",
        "建议",
        "总结",
        "还能吃什么",
        "晚餐怎么吃",
        "今天摄入",
        "控制得怎么样"
    )
    private val DELETE_KEYWORDS = listOf("删除", "删了", "不要", "去掉")
    private val CLEAR_DATA_KEYWORDS = listOf("清空数据", "删除所有", "格式化")
    private val EDIT_KEYWORDS = listOf("不是", "改成", "修改", "应该是", "记错了")
    private val GENERAL_CHAT_KEYWORDS = listOf("你好", "你是谁", "介绍一下", "你能做什么", "hello", "hi")
    private val MEANINGLESS_PATTERNS = listOf(
        Regex("^(阿巴)+$"),
        Regex("^[啊呀哈呵嘿哎呃嗯]{3,}$")
    )
    private val EMOTION_KEYWORDS = listOf(
        "崩了",
        "完了",
        "忍不住",
        "后悔",
        "焦虑",
        "好烦",
        "白减了",
        "破防",
        "控制不住",
        "罪恶感"
    )

    val MEAL_KEYWORDS = listOf(
        "早餐", "早饭", "早上", "上午",
        "午餐", "午饭", "中午", "午间",
        "晚餐", "晚饭", "晚上",
        "加餐", "零食", "夜宵", "宵夜", "下午"
    )

    private val FOOD_VERBS = listOf(
        "吃了",
        "吃",
        "喝了",
        "喝",
        "一碗",
        "一份",
        "一个",
        "一根",
        "一杯",
        "一盘"
    )
    private val COMMON_FOODS = listOf(
        "苹果",
        "香蕉",
        "鸡蛋",
        "牛奶",
        "面包",
        "米饭",
        "包子",
        "肠粉",
        "猪肉肠粉",
        "炸鸡",
        "奶茶",
        "蛋糕",
        "零食",
        "烧烤",
        "火锅",
        "汉堡",
        "薯条",
        "粥",
        "面",
        "粉"
    )

    fun detectSignals(text: String): LocalIntentSignals {
        val textLower = text.lowercase()
        return LocalIntentSignals(
            emotionDetected = EMOTION_KEYWORDS.any { textLower.contains(it) },
            foodDetected = FOOD_VERBS.any { textLower.contains(it) } ||
                COMMON_FOODS.any { textLower.contains(it) },
            mealTimeDetected = MEAL_KEYWORDS.any { textLower.contains(it) }
        )
    }

    fun isMeaninglessText(text: String): Boolean {
        val compact = text.trim().replace("\\s+".toRegex(), "")
        if (compact.isBlank()) return true
        if (MEANINGLESS_PATTERNS.any { it.matches(compact) }) return true
        return compact.length <= 2 && GENERAL_CHAT_KEYWORDS.none { compact.lowercase().contains(it) }
    }

    fun classify(text: String): LocalUserIntent {
        val textLower = text.lowercase()
        val signals = detectSignals(text)
        // 1. App Commands (Clear Data)
        if (CLEAR_DATA_KEYWORDS.any { textLower.contains(it) }) {
            return LocalUserIntent.AppCommandClearData(text)
        }

        // 2. Weight Logging (High Confidence)
        if (WEIGHT_KEYWORDS.any { textLower.contains(it) }) {
            val weight = extractWeight(text)
            if (weight != null) {
                if (weight in 20.0..250.0) {
                    return LocalUserIntent.WeightLogging(weight, text)
                }
                return LocalUserIntent.InvalidWeight(weight, text)
            }
        }

        // 3. Very Explicit Food Logging with Meal Time (High Confidence)
        // Only classify as ClearFoodLogging if it's a very standard statement without query or emotion
        if (signals.mealTimeDetected && signals.foodDetected && 
            !signals.emotionDetected && !text.contains("?") && !text.contains("吗")) {
            return LocalUserIntent.ClearFoodLogging(text)
        }

        // 4. Everything else goes to AI
        return LocalUserIntent.Unknown(text)
    }

    private fun detectEmotionType(textLower: String): EmotionType? {
        return when {
            listOf("忍不住", "控制不住").any { textLower.contains(it) } -> EmotionType.LossOfControl
            listOf("后悔", "罪恶感").any { textLower.contains(it) } -> EmotionType.Regret
            listOf("焦虑", "好烦", "破防").any { textLower.contains(it) } -> EmotionType.Anxiety
            listOf("崩了", "完了", "白减了").any { textLower.contains(it) } -> EmotionType.Frustration
            else -> null
        }
    }

    private fun extractWeight(text: String): Double? {
        val regex = "(\\d+(?:\\.\\d+)?)\\s*(kg|公斤|千克|斤)?".toRegex(RegexOption.IGNORE_CASE)
        val match = regex.find(text) ?: return null
        val value = match.groupValues[1].toBigDecimalOrNull() ?: return null
        val unit = match.groupValues.getOrNull(2).orEmpty().lowercase()
        val kgValue = if (unit == "斤") value.divide(BigDecimal("2")) else value
        return kgValue.toDouble()
    }
}
