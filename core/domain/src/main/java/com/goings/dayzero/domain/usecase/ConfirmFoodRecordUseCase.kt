package com.goings.dayzero.domain.usecase

import com.goings.dayzero.domain.model.DailyRecord
import com.goings.dayzero.domain.model.RecordStatus
import com.goings.dayzero.domain.model.ai.assistant.PayloadSummary
import com.goings.dayzero.domain.repository.RecordRepository
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
