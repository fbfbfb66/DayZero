package com.goings.dayzero

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.goings.dayzero.data.network.AndroidNetworkAvailabilityProvider
import com.goings.dayzero.di.DayZeroHiltModule
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DayZeroNetworkBindingTest {

    @Test
    fun `production module provides real Android network provider`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val provider = DayZeroHiltModule.provideNetworkAvailabilityProvider(context)

        assertTrue(provider is AndroidNetworkAvailabilityProvider)
    }
}
