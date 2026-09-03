package com.hellobutler.app

import android.app.Application
import com.hellobutler.app.core.AppContainer

class ButlerApplication : Application() {
    val container by lazy { AppContainer(this) }
}
