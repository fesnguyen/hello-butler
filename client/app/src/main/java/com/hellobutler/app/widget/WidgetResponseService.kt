package com.hellobutler.app.widget

import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.hellobutler.app.ButlerApplication
import com.hellobutler.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

internal fun responseRow(packageName: String, text: String) = RemoteViews(packageName, R.layout.widget_response_row).apply {
    setTextViewText(R.id.widget_response_text, text)
}

// Android 26–30 require a collection service. It reads the same Room projection as the app.
class WidgetResponseService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = object : RemoteViewsFactory {
        private var rows = emptyList<String>()
        override fun onCreate() = Unit
        override fun onDataSetChanged() {
            val app = applicationContext as ButlerApplication
            rows = runBlocking(Dispatchers.IO) {
                responseRows(app.container.butlerRepository.observeLatestResponse().first()?.text.orEmpty())
            }
        }
        override fun onDestroy() { rows = emptyList() }
        override fun getCount() = rows.size
        override fun getViewAt(position: Int) = responseRow(packageName, rows.getOrElse(position) { "" })
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = false
    }
}
