package cz.kralicekgamer.netnote.logic

/**
 * Kdy má přijít další tick (vše v elapsedRealtime).
 *
 * Drží minutový rytmus od minulého plánu. Když je plán ještě v budoucnosti, nechá ho být:
 * zbloudilý budík (třeba starý, doručený těsně po restartu služby) tak rytmus neposune.
 * Když je tick moc opožděný, začne se počítat od teď.
 */
fun nextTickAt(scheduledAt: Long, now: Long, intervalMs: Long, minLeadMs: Long = 5_000): Long = when {
    scheduledAt > now + minLeadMs -> scheduledAt
    scheduledAt > 0 && scheduledAt + intervalMs >= now + minLeadMs -> scheduledAt + intervalMs
    else -> now + intervalMs
}

/**
 * Jestli už uplynul interval měření, které se spouští jen při ticku.
 *
 * Ticky chodí po minutě s rozptylem, takže tick o vteřinu dřív by bez rezervy [slackMs] kolo
 * přeskočil a měření by se zpozdilo o celý další tick. Záporný čas (hodiny se posunuly zpět)
 * se bere jako „dávno“. Interval 0 znamená vypnuto.
 */
fun intervalElapsed(sinceMs: Long, intervalMs: Long, slackMs: Long): Boolean =
    intervalMs > 0 && (sinceMs < 0 || sinceMs >= intervalMs - slackMs)
