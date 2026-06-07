package com.example.domain.repository

import com.example.domain.model.ai.AiDraftRequest
import com.example.domain.model.ai.CheckinDraft

interface AiDraftRepository {
    suspend fun generateDraft(request: AiDraftRequest): CheckinDraft
}
