package com.cardrw.app.viewmodel

import androidx.lifecycle.ViewModel
import com.cardrw.app.data.repository.ApduJournalRepository
import com.cardrw.desfire.log.AnnotationLevel
import com.cardrw.desfire.log.ApduLogEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class JournalViewModel @Inject constructor(
    private val journalRepository: ApduJournalRepository,
) : ViewModel() {

    val entries: StateFlow<List<ApduLogEntry>> = journalRepository.entries

    private val _level = MutableStateFlow(AnnotationLevel.DETAILED)
    val level: StateFlow<AnnotationLevel> = _level.asStateFlow()

    fun setLevel(level: AnnotationLevel) {
        _level.value = level
    }

    fun clear() = journalRepository.clear()

    fun exportText(): String = journalRepository.exportText(_level.value)
}
