package com.example

import com.example.data.remote.api.AiDraftApiService
import com.example.data.remote.dto.AiDraftRequestDto
import com.example.data.remote.dto.AiDraftResponseDto
import com.example.data.remote.dto.AiSummaryRequestDto
import com.example.data.remote.dto.AiSummaryResponseDto
import com.example.data.remote.dto.IntentClassificationResultDto
import com.example.data.remote.dto.IntentClassifierRequestDto
import com.example.data.remote.dto.assistant.AiAssistantRequestDto
import com.example.data.remote.dto.assistant.AiAssistantTurnDto
import com.example.data.remote.dto.assistant.AssistantActionDto
import com.example.data.remote.dto.assistant.AssistantActionPayloadDto
import com.example.data.remote.dto.assistant.AssistantActionOptionDto
import com.example.data.remote.dto.assistant.AssistantTurnV2ResponseDto
import com.example.data.repository.RemoteAiAssistantRepository
import com.example.domain.model.ai.assistant.AiAssistantRequest
import com.example.domain.model.ai.assistant.ProtocolException
import com.example.domain.model.ai.assistant.DebugChoiceCardPayload
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import retrofit2.Response
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class DayZeroProtocolValidationTest {

    private fun createRepository(
        onSend: suspend (AiAssistantRequestDto) -> Response<AssistantTurnV2ResponseDto>
    ): RemoteAiAssistantRepository {
        val fakeService = object : AiDraftApiService {
            override suspend fun generateDraft(request: AiDraftRequestDto): AiDraftResponseDto = TODO()
            override suspend fun generateDailySummary(request: AiSummaryRequestDto): AiSummaryResponseDto = TODO()
            override suspend fun sendMessage(request: AiAssistantRequestDto): AiAssistantTurnDto = TODO()
            override suspend fun sendMessageWithResponse(request: AiAssistantRequestDto): Response<AiAssistantTurnDto> = TODO()
            override suspend fun classifyUserIntent(request: IntentClassifierRequestDto): IntentClassificationResultDto = TODO()
            
            override suspend fun sendAssistantTurnV2WithResponse(
                request: AiAssistantRequestDto
            ): Response<AssistantTurnV2ResponseDto> {
                return onSend(request)
            }
        }
        return RemoteAiAssistantRepository(fakeService)
    }

    @Test
    fun testValidResponseSuccess() = runTest {
        val repository = createRepository {
            Response.success(
                AssistantTurnV2ResponseDto(
                    reply = "Hello there",
                    actions = emptyList()
                )
            )
        }

        val request = AiAssistantRequest(
            date = LocalDate.now(),
            userText = "Hi",
            todayRecord = null,
            pendingDraft = null,
            recentMessages = emptyList()
        )

        val turn = repository.sendMessage(request)
        assertEquals("Hello there", turn.replyText)
    }

    @Test
    fun testEmptyReplyThrowsProtocolException() = runTest {
        val repository = createRepository {
            Response.success(
                AssistantTurnV2ResponseDto(
                    reply = "  ",
                    actions = emptyList()
                )
            )
        }

        val request = AiAssistantRequest(
            date = LocalDate.now(),
            userText = "Hi",
            todayRecord = null,
            pendingDraft = null,
            recentMessages = emptyList()
        )

        assertThrows(ProtocolException::class.java) {
            runTest { repository.sendMessage(request) }
        }
    }

    @Test
    fun testNullReplyThrowsProtocolException() = runTest {
        val repository = createRepository {
            Response.success(
                AssistantTurnV2ResponseDto(
                    reply = null,
                    actions = emptyList()
                )
            )
        }

        val request = AiAssistantRequest(
            date = LocalDate.now(),
            userText = "Hi",
            todayRecord = null,
            pendingDraft = null,
            recentMessages = emptyList()
        )

        assertThrows(ProtocolException::class.java) {
            runTest { repository.sendMessage(request) }
        }
    }

    @Test
    fun testMissingActionsThrowsProtocolException() = runTest {
        val repository = createRepository {
            Response.success(
                AssistantTurnV2ResponseDto(
                    reply = "Valid reply",
                    actions = null
                )
            )
        }

        val request = AiAssistantRequest(
            date = LocalDate.now(),
            userText = "Hi",
            todayRecord = null,
            pendingDraft = null,
            recentMessages = emptyList()
        )

        assertThrows(ProtocolException::class.java) {
            runTest { repository.sendMessage(request) }
        }
    }

    @Test
    fun testInvalidActionThrowsProtocolException() = runTest {
        val repository = createRepository {
            Response.success(
                AssistantTurnV2ResponseDto(
                    reply = "Valid reply",
                    actions = listOf(AssistantActionDto(type = "some_action"))
                )
            )
        }

        val request = AiAssistantRequest(
            date = LocalDate.now(),
            userText = "Hi",
            todayRecord = null,
            pendingDraft = null,
            recentMessages = emptyList()
        )

        assertThrows(ProtocolException::class.java) {
            runTest { repository.sendMessage(request) }
        }
    }

    @Test
    fun testDebugShowChoiceCardActionSuccess() = runTest {
        val repository = createRepository {
            Response.success(
                AssistantTurnV2ResponseDto(
                    reply = "Valid reply",
                    actions = listOf(
                        AssistantActionDto(
                            type = "debug_show_choice_card",
                            interactionId = "debug_123",
                            payload = AssistantActionPayloadDto(
                                title = "Test Title",
                                message = "Test Message",
                                options = listOf(
                                    AssistantActionOptionDto(id = "opt_a", label = "Option A")
                                )
                            )
                        )
                    )
                )
            )
        }

        val request = AiAssistantRequest(
            date = LocalDate.now(),
            userText = "Hi",
            todayRecord = null,
            pendingDraft = null,
            recentMessages = emptyList()
        )

        val turn = repository.sendMessage(request)
        assertEquals("Valid reply", turn.replyText)
        assertEquals(1, turn.cards.size)
        val card = turn.cards[0] as DebugChoiceCardPayload
        assertEquals("debug_123", card.id)
        assertEquals("Test Title", card.title)
        assertEquals("Test Message", card.message)
        assertEquals(1, card.options.size)
        assertEquals("opt_a", card.options[0].id)
        assertEquals("Option A", card.options[0].label)
    }
}
