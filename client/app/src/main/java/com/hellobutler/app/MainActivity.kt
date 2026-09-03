package com.hellobutler.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.hellobutler.app.ui.HelloButlerApp
import com.hellobutler.app.ui.theme.HelloButlerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as ButlerApplication).container
        setContent { HelloButlerTheme { HelloButlerApp(container) } }
    }
}
