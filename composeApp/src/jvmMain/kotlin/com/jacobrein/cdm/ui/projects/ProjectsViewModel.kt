package com.jacobrein.cdm.ui.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jacobrein.cdm.core.model.Category
import com.jacobrein.cdm.data.ProjectRepository
import com.jacobrein.cdm.ui.UiActions
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The Projects dropdown's choices. [category] is what's stored; Phone+Watch is the composite `phone,watch`. */
enum class ProjectChoice(val label: String, val category: Category) {
    ANY("Any", Category.ANY),
    PHONE("Phone", Category.PHONE),
    WATCH("Watch", Category.WATCH),
    PHONE_WATCH("Phone+Watch", Category.PHONE_WATCH),
    ;

    companion object {
        /** Matches by member set, so "watch,phone" reads as Phone+Watch; null for anything else (e.g. "tablet"). */
        fun of(c: Category): ProjectChoice? {
            val m = c.members()
            return entries.firstOrNull { it.category.members() == m }
        }
    }
}

/** [choice] is null when the stored category isn't one of the menu's; [label] then shows its raw id. */
data class ProjectRow(val path: String, val name: String, val choice: ProjectChoice?, val label: String)

class ProjectsViewModel(private val actions: UiActions, private val projects: ProjectRepository) : ViewModel() {
    /** Null until the first emission, so the screen doesn't flash its empty state. */
    val rows: StateFlow<List<ProjectRow>?> = projects.all
        .map { list ->
            list.sortedBy { it.path.lowercase() }.map { p ->
                val choice = ProjectChoice.of(p.defaultCategory)
                ProjectRow(p.path, p.path.trimEnd('/').substringAfterLast('/').ifBlank { p.path }, choice, choice?.label ?: p.defaultCategory.id)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun onSelect(path: String, choice: ProjectChoice) = actions.launch { projects.setDefault(path, choice.category) }
}
