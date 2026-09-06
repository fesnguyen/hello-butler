package com.hellobutler.app

import android.app.Application
import com.hellobutler.app.core.AppContainer
import com.hellobutler.app.execution.NightlyPlanSyncWorker

class ButlerApplication : Application() {
    val container by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        NightlyPlanSyncWorker.scheduleNext(this)
    }
}
