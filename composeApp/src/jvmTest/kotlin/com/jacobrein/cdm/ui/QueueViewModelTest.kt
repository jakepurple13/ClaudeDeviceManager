package com.jacobrein.cdm.ui

import com.jacobrein.cdm.core.allocator.AllocatorSnapshot
import com.jacobrein.cdm.core.model.HolderId
import com.jacobrein.cdm.ui.queue.QueueViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class QueueViewModelTest {
    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun dragCallsService() {
        val queue = listOf(HolderId("a"), HolderId("b"), HolderId("c"))
        val manager = RecordingManager(MutableStateFlow(AllocatorSnapshot(emptyList(), emptyMap(), queue, emptyMap())))
        val vm = QueueViewModel(manager, testSources(manager))

        vm.onMove(from = 2, to = 0)

        assertEquals(listOf(HolderId("c") to 0), manager.moves)
    }
}
