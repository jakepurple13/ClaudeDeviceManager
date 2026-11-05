package com.jacobrein.cdm.ui

import androidx.lifecycle.viewModelScope
import com.jacobrein.cdm.core.allocator.AllocatorSnapshot
import com.jacobrein.cdm.core.model.HolderId
import com.jacobrein.cdm.ui.queue.QueueDetailViewModel
import com.jacobrein.cdm.ui.queue.QueueViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class QueueViewModelTest {
    private val a = HolderId("a")
    private val b = HolderId("b")
    private val c = HolderId("c")
    private val manager = RecordingManager(MutableStateFlow(AllocatorSnapshot(emptyList(), emptyMap(), listOf(a, b, c), emptyMap())))

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun vm() = QueueViewModel(testActions(manager), testSources(manager))

    @Test
    fun dragCallsServiceOncePerDrag() {
        val vm = vm()
        // c dragged up past b, then past a: two steps, one service call when the drag ends.
        vm.onMove(from = c, to = b)
        vm.onMove(from = c, to = a)
        assertEquals(emptyList(), manager.moves)
        vm.onDragEnd()
        assertEquals(listOf(c to 0), manager.moves)
    }

    @Test
    fun moveResolvesByKeyAgainstRenderedOrderWhileSnapshotLags() {
        val vm = vm()
        vm.onMove(from = c, to = a)
        vm.onDragEnd()
        // The fake's snapshot still says [a, b, c]; the UI shows [c, a, b]. Drag b (index 2 on screen) to the top.
        vm.onMove(from = b, to = c)
        vm.onDragEnd()
        assertEquals(listOf(c to 0, b to 0), manager.moves)
    }

    @Test
    fun actionSurvivesViewModelScopeCancellation() {
        // Both the VM's Main and the app scope queue work until the scheduler runs it.
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        val vm = QueueDetailViewModel(b, testActions(manager, CoroutineScope(dispatcher)), testSources(manager))

        vm.onRemove()
        vm.viewModelScope.cancel() // the entry popped (onClose) before the action ran
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(b), manager.removed)
    }
}
