package com.hellobutler.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.net.Uri
import android.util.Log
import android.util.SizeF
import android.util.TypedValue
import android.view.View
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
                renderLatest(context, rebuild = true)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                Log.e("ButlerWidget", "Could not refresh widget", error)
            } finally { pending.finish() }
        }
    }

    companion object {
        private val renderMutex = Mutex()
        private val renderedText = mutableMapOf<Int, String?>()
        private val renderedSizes = mutableMapOf<Int, SizeF>()

        private suspend fun renderLatest(context: Context, rebuild: Boolean = false) = renderMutex.withLock {
            if (AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, ButlerWidgetProvider::class.java)).isEmpty()) return@withLock
            val app = context.applicationContext as ButlerApplication
            // Read under the same lock: a delayed resize must not overwrite newer text/playback.
            render(context, app.container.butlerRepository.observeLatestResponse().first(), ButlerPlayback.state.value, rebuild)
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

        internal fun mainIntent(context: Context, action: String) = Intent(context, MainActivity::class.java)
            .setAction("com.hellobutler.app.widget.$action")
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_WIDGET_ACTION, action)

        private fun navigation(context: Context, action: String): PendingIntent = PendingIntent.getActivity(
            context, action.hashCode(), mainIntent(context, action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        @Suppress("DEPRECATION")
        private fun widgetSize(context: Context, manager: AppWidgetManager, id: Int): SizeF {
            val options = manager.getAppWidgetOptions(id)
            val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val widthKey = if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH
            val heightKey = if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT
            val width = options.getInt(widthKey, options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)).coerceAtLeast(250).toFloat()
            val height = options.getInt(heightKey, options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 250)).coerceAtLeast(250).toFloat()
            if (Build.VERSION.SDK_INT >= 31) {
                options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                    ?.filter { it.width > 0 && it.height > 0 }
                    ?.minByOrNull { kotlin.math.abs(it.width - width) + kotlin.math.abs(it.height - height) }?.let { return it }
            }
            return SizeF(width, height)
        }

        internal fun render(context: Context, message: ConversationMessageEntity?, playback: ButlerPlaybackState, rebuild: Boolean = false) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ButlerWidgetProvider::class.java))
            if (ids.isEmpty()) return
            ids.forEach { widgetId ->
                val size = widgetSize(context, manager, widgetId)
                if (!rebuild && renderedText.containsKey(widgetId) && renderedText[widgetId] == message?.text && renderedSizes[widgetId] == size) {
                    // Playback/cache transitions must not rebuild the ListView or reset its scroll position.
                    val controls = RemoteViews(context.packageName, R.layout.butler_widget)
                    playbackControls(context, controls, message, playback)
                    manager.partiallyUpdateAppWidget(widgetId, controls)
                    return@forEach
                }
                manager.updateAppWidget(widgetId, createViews(context, widgetId, size, message, playback))
                if (Build.VERSION.SDK_INT < 31 && (!renderedText.containsKey(widgetId) || renderedText[widgetId] != message?.text)) {
                    manager.notifyAppWidgetViewDataChanged(widgetId, R.id.widget_response)
                }
                renderedText[widgetId] = message?.text
                renderedSizes[widgetId] = size
            }
            renderedText.keys.retainAll(ids.toSet())
            renderedSizes.keys.retainAll(ids.toSet())
        }

        internal fun createViews(context: Context, widgetId: Int, size: SizeF,
            message: ConversationMessageEntity?, playback: ButlerPlaybackState): RemoteViews {
            val text = message?.text?.takeIf { it.isNotBlank() } ?: context.getString(R.string.widget_empty)
            val layout = WidgetResponseLayout(context, size, text)
            val views = RemoteViews(context.packageName, R.layout.butler_widget)
            views.setViewPadding(R.id.widget_root, layout.dp(layout.rootPadding.toFloat()), layout.dp(layout.rootPadding.toFloat()),
                layout.dp(layout.rootPadding.toFloat()), layout.dp(layout.rootPadding.toFloat()))
            views.setViewPadding(R.id.widget_response_card, layout.dp(layout.cardPadding.toFloat()), layout.dp(layout.cardPadding.toFloat()),
                layout.dp(layout.cardPadding.toFloat()), layout.dp(layout.cardPadding.toFloat()))
            views.setViewPadding(R.id.widget_response_area, layout.dp(layout.readingPadding.toFloat()), layout.dp(layout.readingPadding.toFloat()),
                layout.dp(layout.readingPadding.toFloat()), layout.dp(layout.readingPadding.toFloat()))
            listOf(R.id.widget_open, R.id.widget_talk, R.id.widget_note).forEach {
                views.setInt(it, "setMinimumHeight", layout.dp(layout.actionHeight.toFloat()))
            }
            views.setViewVisibility(R.id.widget_response_compact, if (layout.presentation.scrolls) View.GONE else View.VISIBLE)
            views.setViewVisibility(R.id.widget_response_collection, if (layout.presentation.scrolls) View.VISIBLE else View.GONE)
            views.removeAllViews(R.id.widget_response_collection)
            if (!layout.presentation.scrolls) {
                views.setTextViewText(R.id.widget_response_compact, text)
            } else {
                views.addView(R.id.widget_response_collection, RemoteViews(context.packageName,
                    if (Build.VERSION.SDK_INT >= 31) R.layout.widget_response_128 else layout.legacyViewport))
                if (Build.VERSION.SDK_INT >= 31) {
                    views.setViewLayoutHeight(R.id.widget_response_viewport, layout.presentation.heightPx.toFloat(), TypedValue.COMPLEX_UNIT_PX)
                    val items = RemoteViews.RemoteCollectionItems.Builder().setViewTypeCount(1).setHasStableIds(true)
                    responseRows(text).forEachIndexed { index, row ->
                        items.addItem((row.hashCode().toLong() shl 32) xor index.toLong(), responseRow(context.packageName, row))
                    }
                    views.setRemoteAdapter(R.id.widget_response, items.build())
                } else {
                    views.setRemoteAdapter(R.id.widget_response, Intent(context, WidgetResponseService::class.java)
                        .setData(Uri.parse("butler-widget://response/$widgetId")))
                }
            }
            views.setOnClickPendingIntent(R.id.widget_home, navigation(context, "home"))
            views.setOnClickPendingIntent(R.id.widget_open, navigation(context, "open"))
            views.setOnClickPendingIntent(R.id.widget_talk, navigation(context, "talk"))
            views.setOnClickPendingIntent(R.id.widget_note, PendingIntent.getActivity(context, widgetId,
                Intent(context, WidgetNoteActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .setData(Uri.parse("butler-widget://note/$widgetId/${size.width}/${size.height}"))
                    .putExtra(WidgetNoteActivity.EXTRA_WIDTH, size.width).putExtra(WidgetNoteActivity.EXTRA_HEIGHT, size.height),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            playbackControls(context, views, message, playback)
            return views
        }

        private fun playbackControls(context: Context, views: RemoteViews,
            message: ConversationMessageEntity?, playback: ButlerPlaybackState) {
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
        }

    }
}
