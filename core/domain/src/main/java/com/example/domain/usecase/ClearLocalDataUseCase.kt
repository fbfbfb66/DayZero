package com.example.domain.usecase

import com.example.domain.repository.AiDraftRepository
import com.example.domain.repository.RecordRepository

enum class ClearLocalDataAction {
    ChatOnly,
    LocalRecordsOnly,
    AllLocal
}

class ClearLocalDataUseCase(
    private val recordRepository: RecordRepository,
    private val aiDraftRepository: AiDraftRepository
) {
    suspend operator fun invoke(action: ClearLocalDataAction) {
        when (action) {
            ClearLocalDataAction.ChatOnly -> aiDraftRepository.clearChatMessages()
            ClearLocalDataAction.LocalRecordsOnly -> recordRepository.clearAllRecords()
            ClearLocalDataAction.AllLocal -> {
                aiDraftRepository.clearChatMessages()
                recordRepository.clearAllRecords()
            }
        }
    }
}
