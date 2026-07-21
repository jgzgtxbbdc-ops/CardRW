package com.cardrw.app.data.repository

import com.cardrw.desfire.log.AnnotationLevel
import com.cardrw.desfire.log.ApduJournal
import com.cardrw.desfire.log.ApduLogEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Journal APDU global de session (CDC §7 / entrée principale « Journal »).
 * Thread-safe via StateFlow ; le [ApduJournal] sous-jacent est remplacé par copie.
 */
class ApduJournalRepository {
    private val journal = ApduJournal()
    private val _entries = MutableStateFlow<List<ApduLogEntry>>(emptyList())
    val entries: StateFlow<List<ApduLogEntry>> = _entries.asStateFlow()

    @Synchronized
    fun replaceFrom(source: ApduJournal) {
        journal.clear()
        source.all.forEach { journal.append(it) }
        _entries.value = journal.all
    }

    @Synchronized
    fun appendAll(entries: List<ApduLogEntry>) {
        entries.forEach { journal.append(it) }
        _entries.update { journal.all }
    }

    @Synchronized
    fun clear() {
        journal.clear()
        _entries.value = emptyList()
    }

    fun exportText(level: AnnotationLevel = AnnotationLevel.DETAILED): String =
        journal.exportText(level)
}
