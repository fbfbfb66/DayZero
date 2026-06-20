package com.example.data.repository

import com.example.domain.model.DailyRecord
import com.example.domain.model.ai.AiChatMessage
import com.example.domain.model.ai.IntentClassificationResult
import com.example.domain.repository.AiCompanionReplyRepository
import java.util.concurrent.atomic.AtomicInteger

class LocalAiCompanionReplyRepository : AiCompanionReplyRepository {
    private val replyIndex = AtomicInteger(0)

    override suspend fun generateReply(
        userText: String,
        todayRecord: DailyRecord?,
        recentMessages: List<AiChatMessage>,
        semanticResult: IntentClassificationResult?
    ): String {
        val variants = when {
            hasEmotionalFoodTone(userText) -> emotionalFoodReplies
            isIdentityQuestion(userText) -> identityReplies
            else -> generalReplies
        }
        return variants[replyIndex.getAndIncrement().floorMod(variants.size)]
    }

    private fun isIdentityQuestion(text: String): Boolean {
        val lower = text.lowercase()
        return listOf("你是谁", "介绍", "能做什么", "你好", "hello", "hi").any { lower.contains(it) }
    }

    private fun hasEmotionalFoodTone(text: String): Boolean {
        val lower = text.lowercase()
        return listOf("崩了", "完了", "忍不住", "后悔", "焦虑", "好烦", "白减了", "破防", "控制不住", "罪恶感")
            .any { lower.contains(it) }
    }

    private fun Int.floorMod(divisor: Int): Int = Math.floorMod(this, divisor)

    private companion object {
        val identityReplies = listOf(
            "我是 DayZero 的 AI 营养师助手，会陪你记录饮食、估算热量，也会帮你把节奏稳住。",
            "你好，我是 DayZero 的 AI 助手。你可以直接告诉我吃了什么，我会帮你整理成记录。",
            "我是 DayZero 里的记录搭子，负责帮你记饮食、看热量，也在吃多时陪你稳一稳。"
        )

        val emotionalFoodReplies = listOf(
            "没关系啦，一顿吃多不会让计划崩掉。你愿意记下来，其实已经在把节奏拉回来了。",
            "先别急着责怪自己，这只是一餐。把它记下来，我们就能继续往前走。",
            "这不代表失败，只是今天的一次波动。能诚实记录下来，已经很棒了。"
        )

        val generalReplies = listOf(
            "我在呢。你可以告诉我今天吃了什么，或者问问今天的摄入情况。",
            "可以呀，我会尽量用简单的话陪你记录饮食和体重。",
            "收到。你想记录饮食、体重，还是看看今天的整体情况？"
        )
    }
}
