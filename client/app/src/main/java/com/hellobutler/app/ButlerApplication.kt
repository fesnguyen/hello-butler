package com.hellobutler.app

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.hellobutler.app.core.AppContainer
import com.hellobutler.app.execution.NightlyPlanSyncWorker
import com.hellobutler.app.execution.ScheduleRestoreWorker
import com.hellobutler.app.sync.DailySyncWorker
import com.hellobutler.app.sync.PushRegistrationWorker

class ButlerApplication : Application() {
    val container by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        if (
            BuildConfig.FIREBASE_APPLICATION_ID.isNotBlank() &&
            BuildConfig.FIREBASE_API_KEY.isNotBlank() &&
            BuildConfig.FIREBASE_PROJECT_ID.isNotBlank() &&
            BuildConfig.FIREBASE_SENDER_ID.isNotBlank()
        ) {
            FirebaseApp.initializeApp(
                this,
                FirebaseOptions.Builder()
                    .setApplicationId(BuildConfig.FIREBASE_APPLICATION_ID)
                    .setApiKey(BuildConfig.FIREBASE_API_KEY)
                    .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                    .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                    .build(),
            )
        }
        ScheduleRestoreWorker.enqueue(this)
        NightlyPlanSyncWorker.scheduleNext(this)
        DailySyncWorker.schedulePeriodic(this)
        if (container.authRepository.hasSession()) PushRegistrationWorker.enqueue(this)
    }
}
