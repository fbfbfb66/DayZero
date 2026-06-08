package com.example.data.local.mapper

import com.example.data.local.entity.DailyRecordEntity
import com.example.domain.model.DailyRecord
import com.example.domain.model.MealEntry
import com.example.domain.model.RecordStatus
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.time.LocalDate

class DailyRecordMapper {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val listType = Types.newParameterizedType(List::class.java, MealEntry::class.java)
    private val adapter = moshi.adapter<List<MealEntry>>(listType)

    fun toDomain(entity: DailyRecordEntity): DailyRecord {
        val meals = adapter.fromJson(entity.mealsJson) ?: emptyList()
        val status = try {
            RecordStatus.valueOf(entity.status)
        } catch (e: Exception) {
            RecordStatus.Draft
        }

        return DailyRecord(
            id = entity.id,
            date = LocalDate.parse(entity.date),
            status = status,
            meals = com.example.domain.model.MealSortPolicy.sortMeals(meals),
            weightKg = entity.weightKg,
            aiSummary = entity.aiSummary ?: ""
        )
    }

    fun toEntity(domain: DailyRecord): DailyRecordEntity {
        val sortedMeals = com.example.domain.model.MealSortPolicy.sortMeals(domain.meals)
        val mealsJson = adapter.toJson(sortedMeals)
        val now = System.currentTimeMillis()
        return DailyRecordEntity(
            id = domain.id,
            date = domain.date.toString(),
            status = domain.status.name,
            mealsJson = mealsJson,
            weightKg = domain.weightKg,
            aiSummary = domain.aiSummary,
            createdAt = now, // In a real app, we might want to preserve this
            updatedAt = now
        )
    }
}
