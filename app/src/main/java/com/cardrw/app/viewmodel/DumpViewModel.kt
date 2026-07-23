package com.cardrw.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cardrw.app.data.repository.DumpListItem
import com.cardrw.app.data.repository.DumpRepository
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
            _ui.update {
                it.copy(
                    selectedName = fileName,
                    selectedJson = json,
                    statusLine = if (json == null) "Fichier introuvable" else null,
                )
            }
        }
    }

    fun clearSelection() {
        _ui.update { it.copy(selectedJson = null, selectedName = null) }
    }

    fun delete(fileName: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { dumps.delete(fileName) }
            if (_ui.value.selectedName == fileName) clearSelection()
            refresh()
        }
    }
}
