package com.hellobutler.app.widget

import android.os.Bundle
import android.content.Intent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.hellobutler.app.ButlerApplication
import com.hellobutler.app.R
import com.hellobutler.app.ui.settings.UserSettingsViewModel
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.roundToInt

/** A focused native editor sharing the widget chrome; only User Settings owns note mutations. */
class WidgetNoteActivity : ComponentActivity() {
    private lateinit var noteId: String
    private lateinit var editor: EditText
    private lateinit var viewModel: UserSettingsViewModel
    private var keyboardRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        noteId = savedInstanceState?.getString("noteId") ?: UUID.randomUUID().toString()
        val container = (application as ButlerApplication).container
        if (!container.authRepository.hasSession()) {
            startActivity(ButlerWidgetProvider.mainIntent(this, "note").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        viewModel = ViewModelProvider(this, UserSettingsViewModel.factory(container.userSettingsRepository, container.soundVoice))[UserSettingsViewModel::class.java]
        enableEdgeToEdge()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContentView(R.layout.widget_note_overlay)
        editor = findViewById(R.id.widget_note_editor)
        val root = findViewById<View>(R.id.widget_note_overlay)
        val panel = findViewById<View>(R.id.widget_note_panel)
        val density = resources.displayMetrics.density
        fun fitPanel() {
            val width = root.width - root.paddingLeft - root.paddingRight
            val height = root.height - root.paddingTop - root.paddingBottom
            if (width <= 0 || height <= 0) return
            val requestedWidth = intent.getFloatExtra(EXTRA_WIDTH, 320f).coerceAtLeast(250f)
            val requestedHeight = intent.getFloatExtra(EXTRA_HEIGHT, 320f).coerceAtLeast(320f)
            val fittedWidth = (requestedWidth * density).roundToInt().coerceAtMost(width)
            val fittedHeight = (requestedHeight * density).roundToInt().coerceAtMost(height)
            if (panel.layoutParams.width != fittedWidth || panel.layoutParams.height != fittedHeight) {
                panel.layoutParams = panel.layoutParams.apply { this.width = fittedWidth; this.height = fittedHeight }
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val margin = (12 * density).roundToInt()
            view.setPadding(bars.left + margin, bars.top + margin, bars.right + margin, maxOf(bars.bottom, ime.bottom) + margin)
            view.post { fitPanel() }
            insets
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitPanel() }
        ViewCompat.requestApplyInsets(root)
        val save = findViewById<Button>(R.id.widget_note_save)
        val exit = findViewById<Button>(R.id.widget_note_exit)
        val count = findViewById<TextView>(R.id.widget_note_count)
        val error = findViewById<TextView>(R.id.widget_note_error)
        fun updateSave() {
            save.isEnabled = !viewModel.state.value.savingItem && editor.text.isNotBlank()
            save.alpha = if (save.isEnabled) 1f else 0.5f
            count.text = getString(R.string.widget_note_count, editor.text.length)
        }
        editor.doAfterTextChanged { updateSave() }
        updateSave()
        save.setOnClickListener { viewModel.saveItem(noteId, editor.text.toString(), false, 0) { finish() } }
        exit.setOnClickListener { finish() }
        listOf(R.id.widget_home to "home", R.id.widget_open to "open", R.id.widget_talk to "talk").forEach { (id, action) ->
            findViewById<View>(id).setOnClickListener {
                startActivity(ButlerWidgetProvider.mainIntent(this, action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
            }
        }
        findViewById<View>(R.id.widget_note).setOnClickListener { focusEditor() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (!viewModel.state.value.savingItem) finish() }
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    editor.isEnabled = !state.savingItem
                    exit.isEnabled = !state.savingItem
                    listOf(R.id.widget_home, R.id.widget_open, R.id.widget_talk, R.id.widget_note).forEach {
                        findViewById<View>(it).isEnabled = !state.savingItem
                    }
                    save.text = getString(if (state.savingItem) R.string.widget_note_saving else R.string.widget_note_save)
                    error.text = state.error
                    error.visibility = if (state.error == null) View.GONE else View.VISIBLE
                    updateSave()
                }
            }
        }
    }

    private fun focusEditor() {
        editor.requestFocus()
        editor.post { getSystemService(InputMethodManager::class.java).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::editor.isInitialized && !keyboardRequested) {
            keyboardRequested = true
            focusEditor()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("noteId", noteId)
        super.onSaveInstanceState(outState) // EditText view state retains the unsaved draft through rotation.
    }

    companion object {
        const val EXTRA_WIDTH = "widget_width_dp"
        const val EXTRA_HEIGHT = "widget_height_dp"
    }
}
