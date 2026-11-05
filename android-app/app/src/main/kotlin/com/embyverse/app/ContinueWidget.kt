package com.embyverse.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.embyverse.app.data.repo.TvHome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/** Widget del celular: «Continuar viendo» con la imagen de lo último que quedó a medias. */
class ContinueWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val app = context.applicationContext as? EmbyVerseApp ?: return
        app.appScope.launch { update(context) }
    }

    companion object {
        /** Se llama al salir de la app (y cuando Android lo pide). */
        suspend fun update(context: Context) = withContext(Dispatchers.IO) {
            runCatching {
                val mgr = AppWidgetManager.getInstance(context)
                val ids = mgr.getAppWidgetIds(ComponentName(context, ContinueWidget::class.java))
                if (ids.isEmpty()) return@runCatching
                val c = (context.applicationContext as EmbyVerseApp).container
                val card = c.library.continueWatchingSmart(1).first().firstOrNull()
                val views = RemoteViews(context.packageName, R.layout.widget_continue)
                val intent = if (card != null) Intent(Intent.ACTION_VIEW, TvHome.playUri(card.ref.kind.name, card.ref.sourceId, card.ref.id)).setClass(context, MainActivity::class.java)
                    else Intent(context, MainActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, 501, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                if (card != null) {
                    views.setTextViewText(R.id.widget_title, card.title)
                    (card.backdropUrl ?: card.imageUrl)?.let { url ->
                        val req = coil3.request.ImageRequest.Builder(context).data(url).size(600, 340).build()
                        val img = c.imageLoader.execute(req).image
                        (img as? coil3.BitmapImage)?.bitmap?.let { b -> views.setImageViewBitmap(R.id.widget_image, b) }
                    }
                }
                mgr.updateAppWidget(ids, views)
            }
        }
    }
}
