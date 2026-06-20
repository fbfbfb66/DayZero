package com.example.data.repository

import android.util.Log
import com.example.domain.model.DailyRecord
import com.example.domain.model.ai.AiChatMessage
import com.example.domain.model.ai.ChatRole
import com.example.domain.model.ai.HybridIntent
import com.example.domain.model.ai.IntentClassificationResult
import com.example.domain.model.ai.assistant.AiAssistantRequest
import com.example.domain.repository.AiAssistantRepository
import com.example.domain.repository.AiCompanionReplyRepository
import java.time.LocalDate

class RemoteAiCompanionReplyRepository(
    private val aiAssistantRepository: AiAssistantRepository,
    private val fallbackRepository: AiCompanionReplyRepository = LocalAiCompanionReplyRepository()
) : AiCompanionReplyRepository {

    override suspend fun generateReply(
        userText: String,
        todayRecord: DailyRecord?,
        recentMessages: List<AiChatMessage>,
        semanticResult: IntentClassificationResult?
    ): String {
        return try {
            val request = AiAssistantRequest(
                date = todayRecord?.date ?: LocalDate.now(),
                userText = buildCompanionPrompt(userText, semanticResult, recentMessages),
                todayRecord = todayRecord,
                pendingDraft = null,
                recentMessages = recentMessages.takeLast(10),
                primaryIntent = semanticResult?.primaryIntent?.name,
                speechAct = semanticResult?.speechAct,
                consumptionStatus = semanticResult?.consumptionStatus,
                shouldCreateDraft = semanticResult?.shouldCreateDraft,
                shouldAskMealTime = semanticResult?.shouldAskMealTime,
                extractedFoodText = semanticResult?.extractedFoodText
            )
            val reply = aiAssistantRepository.sendMessage(request).replyText
                .lineSequence()
                .firstOrNull { it.isNotBlank() }
                ?.trim()
                .orEmpty()

            val sanitized = reply
                .replace("```json", "")
                .replace("```", "")
                .trim()
                .take(180) // A bit more room for 140 words limit

            sanitized.ifBlank {
                fallbackRepository.generateReply(userText, todayRecord, recentMessages, semanticResult)
            }
        } catch (e: Exception) {
            Log.e("DayZeroLocalRouter", "Companion reply failed; using local fallback", e)
            fallbackRepository.generateReply(userText, todayRecord, recentMessages, semanticResult)
        }
    }

    private fun buildCompanionPrompt(
        userText: String, 
        semanticResult: IntentClassificationResult?,
        recentMessages: List<AiChatMessage>
    ): String {
        val lastAssistantMessage = recentMessages.lastOrNull { it.role == ChatRole.Assistant }?.text ?: "无"
        val isFollowUp = semanticResult?.isFollowUp == true

        return """
            你是 DayZero 的 AI 营养师助手。请根据用户的话，生成一段温柔、专业、自然且有陪伴感的回复。
            
            核心原则：
            1. 风格：专业、温柔、自然，拒绝“健康公众号”式的空泛模板（如“多吃蔬菜、适量运动”）。
            2. 回复必须直接、具体地回应用户的话。
            3. 语气简短有力（60-140字），但要有温度。
            4. 不制造焦虑，不鼓励极端节食，不进行医疗诊断。
            
            上下文承接：
            - 如果当前是追问（isFollowUp=true），必须承接上一轮回复内容。
            - 上一轮你的回复是："$lastAssistantMessage"
            - 针对追问（如“意思是...”），不要泛泛回答，要给出具体的、有策略的、低压力的执行方案。
            
            特殊场景处理：
            - 想吃但还没吃（craving_support）：
              1. 先共情用户的食欲。
              2. 给出 2-3 个具体策略（如：少量解馋、当作正餐、别配含糖饮料、下一餐清淡点）。
              3. 强调：真吃了也不代表计划崩盘，记录下来继续调整即可。
            
            - 追问“意思是我可以吃XX吗”：
              - 表达：可以吃，但不是无节制。给出具体的吃法策略，让用户感受到“可控”且“不焦虑”。
            
            输出要求：
            - 纯中文。
            - 60-140 字。
            - 禁止输出 JSON 或 Markdown。
            - 不生成任何交互卡片。
            
            当前意图数据：
            - 主意图: ${semanticResult?.primaryIntent?.name ?: "Unknown"}
            - 言语行为: ${semanticResult?.speechAct ?: "Unknown"}
            - 进食状态: ${semanticResult?.consumptionStatus ?: "Unknown"}
            - 是否为追问: $isFollowUp
            - 提取食物: ${semanticResult?.extractedFoodText ?: "无"}
            
            用户当前输入：$userText
        """.trimIndent()
    }
}
