package com.example.domain.time

import java.time.LocalDate

interface CurrentDateProvider {
    fun currentDate(): LocalDate
}
