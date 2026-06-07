package com.example.data.repository

import com.example.domain.model.MealType
import com.example.domain.model.ai.assistant.*
import com.example.domain.repository.AiAssistantRepository
import java.util.UUID

class FakeAiAssistantRepository : AiAssistantRepository {

    override suspend fun sendMessage(request: AiAssistantRequest): AiAssistantTurn {
        val text = request.userText
        val id = UUID.randomUUID().toString()

        return when {
            listOf("怎么样", "建议", "今天吃得").any { text.contains(it) } -> {
                val summary = if (request.todayRecord != null) {
                    "你今天已经摄入了 ${request.todayRecord.totalCalories} kcal。早午餐搭配很合理，继续保持哦！"
                } else {
                    "今天还没开始记录呢，快告诉我你吃了什么吧。"
                }
                AiAssistantTurn(
                    id = id,
                    intent = AiIntent.DailyAdvice,
                    replyText = summary,
                    cards = listOf(
                        SummaryCardPayload(
                            id = "card_$id",
                            title = "今日小结",
                            totalCalories = request.todayRecord?.totalCalories,
                            recordedMeals = request.todayRecord?.meals?.filter { it.foods.isNotEmpty() }?.map { it.mealType } ?: emptyList(),
                            summary = summary
                        )
                    )
                )
            }

            listOf("烦", "完了", "白减了", "崩了", "吃多了").any { text.contains(it) } -> {
                AiAssistantTurn(
                    id = id,
                    intent = AiIntent.Motivation,
                    replyText = "抱抱你，一次的小放纵完全没关系的。减脂是一场马拉松，暂时的波动不代表失败。我们明天稍微清淡一点就好，不要给自己太大压力哦。"
                )
            }

            listOf("体重", "kg").any { text.contains(it) } -> {
                val weight = "\\d+(\\.\\d+)?".toRegex().find(text)?.value?.toDouble() ?: 0.0
                AiAssistantTurn(
                    id = id,
                    intent = AiIntent.WeightLogging,
                    replyText = "好的，帮你准备好了体重记录卡，确认一下数值对吗？",
                    cards = listOf(
                        WeightCardPayload(
                            id = "weight_$id",
                            date = request.date,
                            weightKg = weight
                        )
                    )
                )
            }

            listOf("删", "删除", "不要午餐").any { text.contains(it) } -> {
                AiAssistantTurn(
                    id = id,
                    intent = AiIntent.FoodDelete,
                    replyText = "确定要删除这条记录吗？",
                    cards = listOf(
                        DeleteConfirmCardPayload(
                            id = "del_$id",
                            title = "确认删除",
                            message = "确定要删除这条记录吗？",
                            targetRecordId = request.todayRecord?.id,
                            targetMealType = null
                        )
                    )
                )
            }

            listOf("不是", "改成", "修改").any { text.contains(it) } -> {
                AiAssistantTurn(
                    id = id,
                    intent = AiIntent.FoodEdit,
                    replyText = "好的，请问你想修改哪一项呢？",
                    cards = listOf(
                        EditConfirmCardPayload(
                            id = "edit_$id",
                            title = "确认修改",
                            message = "你想把记录改成什么呢？",
                            targetRecordId = request.todayRecord?.id,
                            targetMealType = null,
                            targetFoodId = null
                        )
                    )
                )
            }

            text.contains("苹果") && !listOf("早", "中", "午", "晚").any { text.contains(it) } -> {
                AiAssistantTurn(
                    id = id,
                    intent = AiIntent.MealTimeClarification,
                    replyText = "记录好啦！不过这个苹果是在哪一餐吃的呀？",
                    cards = listOf(
                        ChoiceCardPayload(
                            id = "choice_$id",
                            title = "确认餐次",
                            message = "这个苹果是在哪一餐吃的呀？",
                            options = listOf(
                                AiChoiceOption("1", "早餐", AiChoiceAction.SetMealTypeBreakfast),
                                AiChoiceOption("2", "午餐", AiChoiceAction.SetMealTypeLunch),
                                AiChoiceOption("3", "晚餐", AiChoiceAction.SetMealTypeDinner),
                                AiChoiceOption("4", "加餐", AiChoiceAction.SetMealTypeSnack)
                            )
                        )
                    )
                )
            }

            else -> {
                AiAssistantTurn(
                    id = id,
                    intent = AiIntent.FoodLogging,
                    replyText = "我先帮你估一版，你可以检查草稿卡片后再确认。"
                )
            }
        }
    }
}
