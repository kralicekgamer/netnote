package cz.netdenik.data

import android.content.Context
import cz.netdenik.logic.retentionCutoff
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId

/** Promazávání záznamů starších, než je nastavená doba uchovávání. */
object Retention {
    /** @return počet smazaných řádků; 0 i při uchovávání „neomezeně“ */
    suspend fun prune(context: Context): Int = withContext(Dispatchers.IO) {
        val prefs = Prefs(context)
        val now = System.currentTimeMillis()
        prefs.lastPruneAt = now
        val cutoff = retentionCutoff(now, prefs.retentionDays, ZoneId.systemDefault()) ?: return@withContext 0
        AppDatabase.get(context).dao().deleteBefore(cutoff)
    }
}
