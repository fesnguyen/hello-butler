package com.hellobutler.app.widget

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hellobutler.app.ButlerApplication
import com.hellobutler.app.MainActivity
import com.hellobutler.app.data.local.SavedContextEntity
import com.hellobutler.app.ui.settings.SavedContextEditor
import com.hellobutler.app.ui.settings.UserSettingsViewModel
import com.hellobutler.app.ui.theme.HelloButlerTheme
import java.util.UUID

/** A real editor over the launcher; persistence and validation remain in User Settings. */
class WidgetNoteActivity : ComponentActivity() {
    private lateinit var noteId: String
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        noteId = savedInstanceState?.getString("noteId") ?: UUID.randomUUID().toString()
        val container = (application as ButlerApplication).container
        if (!container.authRepository.hasSession()) {
            startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_WIDGET_ACTION, "note"))
            finish()
            return
        }
        setContent {
            HelloButlerTheme {
                val viewModel: UserSettingsViewModel = viewModel(factory = UserSettingsViewModel.factory(container.userSettingsRepository))
                val item = remember { SavedContextEntity(noteId, "", false, 0) }
                SavedContextEditor(viewModel, item, noteOnly = true) { finish() }
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("noteId", noteId)
        super.onSaveInstanceState(outState)
    }
}
