package com.cardrw.app.data.repository

import android.content.Context
import com.cardrw.desfire.dump.CardDumpBuilder
import com.cardrw.desfire.dump.CardDumpDocument
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

data class DumpListItem(
    val fileName: String,
    val absolutePath: String,
    val sizeBytes: Long,
    val modifiedEpochMs: Long,
)

/**
 * Persistance locale des dumps JSON (filesDir/dumps) — **pas de secrets**.
 */
@Singleton
class DumpRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dumpsDir: File
        get() = File(context.filesDir, "dumps").also { it.mkdirs() }

    private val fileNameTime: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneId.systemDefault())

    fun save(doc: CardDumpDocument, uidHint: String? = null): DumpListItem {
        val stamp = fileNameTime.format(Instant.now())
        val uidPart = (uidHint ?: "nouid")
            .replace(Regex("[^0-9A-Fa-f]"), "")
            .take(14)
            .ifEmpty { "nouid" }
        val name = "cardrw_dump_${stamp}_$uidPart.json"
        val file = File(dumpsDir, name)
        file.writeText(CardDumpBuilder.toPrettyJson(doc), Charsets.UTF_8)
        return DumpListItem(
            fileName = name,
            absolutePath = file.absolutePath,
            sizeBytes = file.length(),
            modifiedEpochMs = file.lastModified(),
        )
    }

    fun list(): List<DumpListItem> =
        dumpsDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?.map {
                DumpListItem(
                    fileName = it.name,
                    absolutePath = it.absolutePath,
                    sizeBytes = it.length(),
                    modifiedEpochMs = it.lastModified(),
                )
            }
            .orEmpty()

    fun readJson(fileName: String): String? {
        val file = File(dumpsDir, fileName)
        if (!file.isFile || !file.canonicalPath.startsWith(dumpsDir.canonicalPath)) return null
        return file.readText(Charsets.UTF_8)
    }

    fun delete(fileName: String): Boolean {
        val file = File(dumpsDir, fileName)
        if (!file.isFile || !file.canonicalPath.startsWith(dumpsDir.canonicalPath)) return false
        return file.delete()
    }
}
