package com.hellobutler.app

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.hellobutler.app.ui.HelloButlerApp
import com.hellobutler.app.ui.theme.HelloButlerTheme
import com.hellobutler.app.core.ButlerDestination
import com.hellobutler.app.core.ButlerNavigation

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        consumeNavigation(intent)
        val container = (application as ButlerApplication).container
        setContent { HelloButlerTheme { HelloButlerApp(container) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeNavigation(intent)
    }

    private fun consumeNavigation(intent: Intent?) {
        intent?.getStringExtra(EXTRA_WIDGET_ACTION)?.let { action ->
            val destination = when (action) {
                "home" -> ButlerDestination.HOME
                "open" -> ButlerDestination.CONVERSATION
                "talk" -> ButlerDestination.TALK
                "note" -> ButlerDestination.NOTE
                else -> null
            }
            destination?.let(ButlerNavigation::navigate)
            intent.removeExtra(EXTRA_WIDGET_ACTION) // Do not replay a handoff after rotation.
        }

        if (intent?.getBooleanExtra(EXTRA_OPEN_CONVERSATION, false) == true) {
            ButlerNavigation.openConversation()
            intent.removeExtra(EXTRA_OPEN_CONVERSATION)
        }
    }

    companion object {
        const val EXTRA_OPEN_CONVERSATION = "open_butler_conversation"
        const val EXTRA_WIDGET_ACTION = "butler_widget_action"
    }
}
