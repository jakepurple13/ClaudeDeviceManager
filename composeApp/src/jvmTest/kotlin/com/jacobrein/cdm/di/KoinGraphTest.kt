package com.jacobrein.cdm.di

import kotlin.test.Test
import org.koin.test.verify.verify

class KoinGraphTest {
    @Test
    fun verifyGraph() {
        appModules().forEach { it.verify() }
    }
}
