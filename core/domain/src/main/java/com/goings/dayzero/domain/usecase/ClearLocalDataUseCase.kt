package com.goings.dayzero.domain.usecase

import com.goings.dayzero.domain.repository.AiDraftRepository
import com.goings.dayzero.domain.repository.RecordRepository

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
