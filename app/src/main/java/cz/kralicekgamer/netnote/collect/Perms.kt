package cz.kralicekgamer.netnote.collect

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object Perms {
    private fun has(ctx: Context, permission: String) =
        ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun fineLocation(ctx: Context) = has(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
    fun backgroundLocation(ctx: Context) = has(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    fun phoneState(ctx: Context) = has(ctx, Manifest.permission.READ_PHONE_STATE)
    fun notifications(ctx: Context) =
        Build.VERSION.SDK_INT < 33 || has(ctx, Manifest.permission.POST_NOTIFICATIONS)

    /** Bez těchhle dvou služba nemá co sbírat (a na Androidu 14+ by start služby typu location spadl). */
    fun canStart(ctx: Context) = fineLocation(ctx) && phoneState(ctx)

    /** Od Androidu 14 nejde službu typu location spustit z pozadí bez polohy „Povolit vždy“. */
    fun canStartFromBackground(ctx: Context) =
        canStart(ctx) && (Build.VERSION.SDK_INT < 34 || backgroundLocation(ctx))

    /** Co si vyžádat při prvním spuštění. Poloha na pozadí se žádá zvlášť, až potom. */
    fun initialRequest(): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
}
