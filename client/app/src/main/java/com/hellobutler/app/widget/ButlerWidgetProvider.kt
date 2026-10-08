package com.hellobutler.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.net.Uri
import android.util.Log
import android.widget.RemoteViews
import com.hellobutler.app.ButlerApplication
import com.hellobutler.app.MainActivity
import com.hellobutler.app.R
import com.hellobutler.app.data.local.ConversationMessageEntity
import com.hellobutler.app.execution.ButlerAudioPlaybackService
import com.hellobutler.app.execution.ButlerPlayback
import com.hellobutler.app.execution.ButlerPlaybackState
import com.hellobutler.app.execution.PlaybackPhase
import com.hellobutler.app.execution.playbackPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ButlerWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = refresh(context)

    private fun refresh(context: Context) {
        val pending = goAsync()
        val app = context.applicationContext as ButlerApplication
        app.applicationScope.launch {
            try {
                renderLatest(context)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                Log.e("ButlerWidget", "Could not refresh widget", error)
            } finally { pending.finish() }
        }
    }

    companion object {
        private val renderMutex = Mutex()
        private val renderedText = mutableMapOf<Int, String?>()

        private suspend fun renderLatest(context: Context) = renderMutex.withLock {
            if (AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, ButlerWidgetProvider::class.java)).isEmpty()) return@withLock
            val app = context.applicationContext as ButlerApplication
            // Read under the same lock: a delayed resize must not overwrite newer text/playback.
            render(context, app.container.butlerRepository.observeLatestResponse().first(), ButlerPlayback.state.value)
        }

        fun observe(app: ButlerApplication) {
            app.applicationScope.launch {
                combine(app.container.butlerRepository.observeLatestResponse().distinctUntilChanged(), ButlerPlayback.state) { _, _ -> Unit }
                    .collect {
                        try { renderLatest(app) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { Log.e("ButlerWidget", "Could not render widget", error) }
                    }
            }
        }

        private fun navigation(context: Context, action: String): PendingIntent = PendingIntent.getActivity(
            context, action.hashCode(), Intent(context, MainActivity::class.java)
                .setAction("com.hellobutler.app.widget.$action")
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_WIDGET_ACTION, action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        internal fun render(context: Context, message: ConversationMessageEntity?, playback: ButlerPlaybackState) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ButlerWidgetProvider::class.java))
            if (ids.isEmpty()) return
            ids.forEach { widgetId ->
                val views = RemoteViews(context.packageName, R.layout.butler_widget)
                views.setEmptyView(R.id.widget_response, R.id.widget_empty)
                if (Build.VERSION.SDK_INT >= 31) {
                    val items = RemoteViews.RemoteCollectionItems.Builder().setViewTypeCount(1).setHasStableIds(true)
                    responseRows(message?.text.orEmpty()).forEachIndexed { index, text ->
                        items.addItem((text.hashCode().toLong() shl 32) xor index.toLong(), responseRow(context.packageName, text))
                    }
                    views.setRemoteAdapter(R.id.widget_response, items.build())
                } else {
                    views.setRemoteAdapter(R.id.widget_response, Intent(context, WidgetResponseService::class.java)
                        .setData(Uri.parse("butler-widget://response/$widgetId")))
                }
                views.setOnClickPendingIntent(R.id.widget_home, navigation(context, "home"))
                views.setOnClickPendingIntent(R.id.widget_open, navigation(context, "open"))
                views.setOnClickPendingIntent(R.id.widget_talk, navigation(context, "talk"))
                views.setOnClickPendingIntent(R.id.widget_note, PendingIntent.getActivity(context, 410,
                    Intent(context, WidgetNoteActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                listOf(R.id.widget_aloud to false, R.id.widget_call to true).forEach { (id, private) ->
                    val phase = message?.let { playbackPhase(it.requestId, private, it.audioCacheState, playback) } ?: PlaybackPhase.READY
                    val unavailable = message == null || message.audioCacheState in setOf("none", "unavailable")
                    val active = message != null && playback.requestId == message.requestId && playback.privateRoute == private && playback.phase != PlaybackPhase.READY
                    val label = when {
                        active && phase == PlaybackPhase.LOADING -> R.string.widget_loading_stop
                        active -> R.string.widget_stop
                        phase == PlaybackPhase.LOADING -> R.string.widget_loading
                        unavailable && message != null -> R.string.widget_no_audio
                        private -> R.string.widget_call
                        else -> R.string.widget_aloud
                    }
                    views.setTextViewText(id, context.getString(label))
                    views.setBoolean(id, "setEnabled", !unavailable && (phase != PlaybackPhase.LOADING || active))
                    if (message != null) views.setOnClickPendingIntent(id, if (active) ButlerAudioPlaybackService.stopIntent(context)
                        else ButlerAudioPlaybackService.intent(context, message.requestId, private))
                }
                manager.updateAppWidget(widgetId, views)
                if (Build.VERSION.SDK_INT < 31 && (!renderedText.containsKey(widgetId) || renderedText[widgetId] != message?.text)) {
                    manager.notifyAppWidgetViewDataChanged(widgetId, R.id.widget_response)
                }
                renderedText[widgetId] = message?.text
            }
            renderedText.keys.retainAll(ids.toSet())
        }
    }
}
