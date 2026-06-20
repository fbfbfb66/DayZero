package com.example.data.time

import com.example.domain.time.CurrentDateProvider
import java.time.LocalDate

class SystemCurrentDateProvider : CurrentDateProvider {
    override fun currentDate(): LocalDate = LocalDate.now()
}
