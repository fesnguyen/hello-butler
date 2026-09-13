package com.hellobutler.app

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.hellobutler.app.ui.HelloButlerApp
import com.hellobutler.app.ui.theme.HelloButlerTheme
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
        if (intent?.getBooleanExtra(EXTRA_OPEN_CONVERSATION, false) == true) {
            ButlerNavigation.openConversation()
        }
    }

    companion object { const val EXTRA_OPEN_CONVERSATION = "open_butler_conversation" }
}
