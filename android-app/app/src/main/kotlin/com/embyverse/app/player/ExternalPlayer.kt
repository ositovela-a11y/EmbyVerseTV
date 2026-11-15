package com.embyverse.app.player

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.embyverse.app.R
import com.embyverse.app.data.net.Network

/** Abre un video en otro reproductor instalado (VLC, MX Player, Just Player, Kodi…). */
object ExternalPlayer {
    fun intent(context: Context, np: NowPlaying): Intent {
        val ua = np.userAgent ?: Network.USER_AGENT
        val headers = buildList { add("User-Agent"); add(ua); np.referrer?.let { add("Referer"); add(it) } }.toTypedArray()
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(np.url), "video/*")
            .putExtra("title", np.title)                       // VLC, MX Player, Just Player
            .putExtra(Intent.EXTRA_TITLE, np.title)
            .putExtra("headers", headers)                      // MX Player
            .putExtra("http-user-agent", ua)                  // VLC
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return Intent.createChooser(i, context.getString(R.string.player_external_choose)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Devuelve false si no hay ningún otro reproductor instalado. */
    fun open(context: Context, np: NowPlaying): Boolean {
        val probe = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(np.url), "video/*")
        val others = context.packageManager.queryIntentActivities(probe, 0).filter { it.activityInfo.packageName != context.packageName }
        if (others.isEmpty()) return false
        return try { context.startActivity(intent(context, np)); true } catch (_: ActivityNotFoundException) { false }
    }
}
