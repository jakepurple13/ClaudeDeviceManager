package com.programmersbox.cdm.ui

import androidx.lifecycle.viewModelScope
import com.programmersbox.cdm.core.allocator.AllocatorSnapshot
import com.programmersbox.cdm.core.allocator.HolderStatus
import com.programmersbox.cdm.core.allocator.Wants
import com.programmersbox.cdm.core.model.Category
import com.programmersbox.cdm.core.model.HolderId
import com.programmersbox.cdm.core.model.Platform
import com.programmersbox.cdm.ui.queue.QueueDetailViewModel
import com.programmersbox.cdm.ui.queue.QueueViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
    fun dragEndingWhereItStartedSendsNothing() {
        val vm = vm()
        vm.onMove(from = c, to = b) // [a, c, b]
        vm.onMove(from = c, to = b) // back to [a, b, c]
        vm.onDragEnd()
        assertEquals(emptyList(), manager.moves)
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

    @Test
    fun queueAndDetailShowLimits() = runBlocking {
        val snap = AllocatorSnapshot(
            emptyList(), emptyMap(), listOf(a, b),
            mapOf(
                a to HolderStatus.Queued(1, Wants(setOf(Category.PHONE), Platform.IOS, virtual = false)),
                b to HolderStatus.Queued(2, Wants(setOf(Category.WATCH), Platform.ANDROID, virtual = true)),
            ),
        )
        val m = RecordingManager(MutableStateFlow(snap))
        val queue = QueueViewModel(testActions(m), testSources(m))
        val detail = QueueDetailViewModel(a, testActions(m), testSources(m))
        val detailB = QueueDetailViewModel(b, testActions(m), testSources(m))

        val rows = withTimeout(5_000) { queue.rows.first { it != null } }!!
        assertEquals(listOf("phone", "iOS", "real only"), rows[0].chipLabels)
        assertEquals(listOf("watch", "Android", "virtual only"), rows[1].chipLabels)
        assertEquals(
            listOf(WantChipKind.CATEGORY, WantChipKind.PLATFORM, WantChipKind.REAL_ONLY),
            rows[0].chips.map { it.kind },
        )
        assertEquals(WantChipKind.VIRTUAL_ONLY, rows[1].chips.last().kind)
        assertEquals("phone, iOS, real only", withTimeout(5_000) { detail.uiState.first { it.loaded } }.wantsText)
        assertEquals("watch, Android, virtual only", withTimeout(5_000) { detailB.uiState.first { it.loaded } }.wantsText)
        queue.closeForTest()
        detail.closeForTest()
        detailB.closeForTest()
    }
}
