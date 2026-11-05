package com.jacobrein.cdm.di

import java.nio.file.Path
import kotlin.test.Test
import org.koin.test.verify.verify

class KoinGraphTest {
    @Test
    fun verifyGraph() {
        appModules().forEach { it.verify(extraTypes = listOf(Path::class, Set::class)) }
    }
}
