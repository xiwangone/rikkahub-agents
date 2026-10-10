package me.rerere.rikkahub.workflow.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.log.AppLog
import me.rerere.rikkahub.workflow.execution.WorkflowEngine
import me.rerere.rikkahub.workflow.model.WorkflowRun
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import me.rerere.rikkahub.workflow.repository.WorkflowRepository.Loaded

class WorkflowsViewModel(
    private val repository: WorkflowRepository,
    private val engine: WorkflowEngine,
) : ViewModel() {
    val workflows: StateFlow<List<Loaded>> =
        repository
            .observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setEnabled(
        id: String,
        enabled: Boolean,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            // A DB/trigger failure here would otherwise propagate to the coroutine's
            // uncaught handler and crash the app — the switch just no-ops visibly.
            runCatching { repository.setEnabled(id, enabled) }
                .onFailure { AppLog.e("WorkflowsViewModel", "setEnabled failed for $id", it) }
        }
    }

    fun delete(
        id: String,
        onDone: () -> Unit = {},
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.deleteCascading(id) }
                .onFailure { AppLog.e("WorkflowsViewModel", "delete failed for $id", it) }
            // Callers pass UI work (nav.popBackStack) — must not run on the IO dispatcher.
            withContext(Dispatchers.Main) { onDone() }
        }
    }

    suspend fun runNow(id: String): WorkflowEngine.FireOutcome = engine.fire(id)

    suspend fun history(
        id: String,
        limit: Int = 20,
    ): List<WorkflowRun> = repository.lastRuns(id, limit)

    suspend fun get(id: String): Loaded? = repository.getById(id)
}
