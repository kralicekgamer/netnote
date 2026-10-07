package cz.netdenik.export

import android.content.Context
import android.database.Cursor
import android.net.Uri
import cz.netdenik.data.AppDatabase
import cz.netdenik.logic.csvLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object CsvExporter {
    /** Sloupce s časem v ms; ke každému se přidá čitelný místní čas. */
    private val TIME_COLUMNS = setOf("ts", "startMs", "endMs")
    private val ISO: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** Zapíše ZIP s jedním CSV na tabulku. Vrací počet řádků v každé tabulce. */
    suspend fun exportZip(context: Context, target: Uri): Map<String, Int> = withContext(Dispatchers.IO) {
        val db = AppDatabase.get(context)
        val counts = linkedMapOf<String, Int>()
        val stream = context.contentResolver.openOutputStream(target)
            ?: throw IOException("Soubor nejde otevřít pro zápis")
        ZipOutputStream(stream.buffered()).use { zip ->
            for (table in AppDatabase.TABLES) {
                zip.putNextEntry(ZipEntry("$table.csv"))
                val out = zip.writer(Charsets.UTF_8)
                db.query("SELECT * FROM $table ORDER BY id", null).use { cursor ->
                    counts[table] = writeTable(cursor, out)
                }
                out.flush()
                zip.closeEntry()
            }
        }
        counts
    }

    private fun writeTable(c: Cursor, out: Appendable): Int {
        val names = c.columnNames
        out.append(csvLine(names.flatMap { if (it in TIME_COLUMNS) listOf(it, "${it}_local") else listOf(it) }))
        out.append('\n')
        var rows = 0
        while (c.moveToNext()) {
            val values = ArrayList<String?>(names.size + 2)
            for (i in names.indices) {
                val value = if (c.isNull(i)) null else c.getString(i)
                values += value
                if (names[i] in TIME_COLUMNS) {
                    values += value?.toLongOrNull()?.let {
                        ISO.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
                    }
                }
            }
            out.append(csvLine(values))
            out.append('\n')
            rows++
        }
        return rows
    }
}
