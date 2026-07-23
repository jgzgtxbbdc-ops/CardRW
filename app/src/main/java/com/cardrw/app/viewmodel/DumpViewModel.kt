package com.cardrw.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cardrw.app.data.repository.DumpListItem
import com.cardrw.app.data.repository.DumpRepository
import com.cardrw.desfire.dump.CardDumpBuilder
import com.cardrw.desfire.dump.DumpRestorePlanner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class DumpUiState(
    val items: List<DumpListItem> = emptyList(),
    val selectedJson: String? = null,
    val selectedName: String? = null,
    val statusLine: String? = null,
    /** Dry-run plan lines for selected dump. */
    val planLines: List<String> = emptyList(),
    val planWarnings: List<String> = emptyList(),
)

@HiltViewModel
class DumpViewModel @Inject constructor(
    private val dumps: DumpRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(DumpUiState())
    val ui: StateFlow<DumpUiState> = _ui.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) { dumps.list() }
            _ui.update { it.copy(items = items) }
        }
    }

    fun open(fileName: String) {
        viewModelScope.launch {
            val json = withContext(Dispatchers.IO) { dumps.readJson(fileName) }
            val plan = if (json != null) {
                try {
                    DumpRestorePlanner.plan(CardDumpBuilder.parseJson(json))
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
            _ui.update {
                it.copy(
                    selectedName = fileName,
                    selectedJson = json,
                    statusLine = if (json == null) "Fichier introuvable" else null,
                    planLines = plan?.steps?.map { s -> s.label }.orEmpty(),
                    planWarnings = plan?.warnings.orEmpty(),
                )
            }
        }
    }

    fun clearSelection() {
        _ui.update {
            it.copy(
                selectedJson = null,
                selectedName = null,
                planLines = emptyList(),
                planWarnings = emptyList(),
            )
        }
    }

    fun delete(fileName: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { dumps.delete(fileName) }
            if (_ui.value.selectedName == fileName) clearSelection()
            refresh()
        }
    }
}
