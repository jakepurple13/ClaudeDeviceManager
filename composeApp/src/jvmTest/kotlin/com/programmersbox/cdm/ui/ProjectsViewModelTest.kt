package com.programmersbox.cdm.ui

import com.programmersbox.cdm.core.allocator.AllocatorSnapshot
import com.programmersbox.cdm.core.model.Category
import com.programmersbox.cdm.data.ProjectRepository
import com.programmersbox.cdm.ui.projects.ProjectChoice
import com.programmersbox.cdm.ui.projects.ProjectsViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectsViewModelTest {
    private val projects = ProjectRepository(openedInMemoryDatabase().projectDao())
    private val manager = RecordingManager(MutableStateFlow(AllocatorSnapshot(emptyList(), emptyMap(), emptyList(), emptyMap())))
    private lateinit var vm: ProjectsViewModel

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() {
        vm.closeForTest()
        Dispatchers.resetMain()
    }

    @Test
    fun unknownCategoryShowsRawIdAndAnyCanBePicked() = runBlocking {
        projects.setDefault("/w/tab-app", Category("tablet"))
        projects.setDefault("/w/both", Category("watch,phone"))
        vm = ProjectsViewModel(testActions(manager), projects)

        val rows = withTimeout(5_000) { vm.rows.mapNotNull { it }.first { it.size == 2 } }
        val tab = rows.single { it.path == "/w/tab-app" }
        assertEquals("tablet", tab.label)
        assertNull(tab.choice)
        assertEquals(ProjectChoice.PHONE_WATCH, rows.single { it.path == "/w/both" }.choice)

        vm.onSelect("/w/tab-app", ProjectChoice.ANY)
        withTimeout(5_000) { while (projects.all.first().single { it.path == "/w/tab-app" }.defaultCategory != Category.ANY) delay(10) }
    }
}
