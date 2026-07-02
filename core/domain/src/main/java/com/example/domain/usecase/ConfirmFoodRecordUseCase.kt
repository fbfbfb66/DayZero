package com.example.domain.usecase

import com.example.domain.model.DailyRecord
import com.example.domain.model.RecordStatus
import com.example.domain.model.ai.assistant.PayloadSummary
import com.example.domain.repository.RecordRepository
import java.time.LocalDate

class ConfirmFoodRecordUseCase(
    private val recordRepository: RecordRepository
) {
    suspend operator fun invoke(currentDate: LocalDate, payloadSummary: PayloadSummary?): DailyRecord {
        val currentRecord = recordRepository.getRecordByDateAndStatus(currentDate, RecordStatus.Confirmed)
        val updatedRecord = ConfirmFoodRecordMerger.merge(
            currentRecord = currentRecord,
            recordDate = currentDate,
            payloadSummary = payloadSummary
        )
        recordRepository.upsertRecord(updatedRecord)
        return updatedRecord
    }
}
