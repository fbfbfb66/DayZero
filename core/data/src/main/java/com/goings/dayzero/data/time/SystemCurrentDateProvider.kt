package com.goings.dayzero.data.time

import com.goings.dayzero.domain.time.CurrentDateProvider
import java.time.LocalDate

class SystemCurrentDateProvider : CurrentDateProvider {
    override fun currentDate(): LocalDate = LocalDate.now()
}
