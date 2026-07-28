package com.goings.dayzero.domain.time

import java.time.LocalDate

interface CurrentDateProvider {
    fun currentDate(): LocalDate
}
