package com.hellobutler.app.core

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object AppVisibility : Application.ActivityLifecycleCallbacks {
    @Volatile private var started = 0
    val isForeground: Boolean get() = started > 0
    override fun onActivityStarted(activity: Activity) { started++ }
    override fun onActivityStopped(activity: Activity) { started = (started - 1).coerceAtLeast(0) }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

enum class ButlerDestination { HOME, CONVERSATION, TALK, NOTE }

object ButlerNavigation {
    private val mutableDestination = MutableStateFlow<ButlerDestination?>(null)
    val destination = mutableDestination.asStateFlow()
    fun navigate(destination: ButlerDestination) { mutableDestination.value = destination }
    fun navigationConsumed() { mutableDestination.value = null }

    fun openConversation() = navigate(ButlerDestination.CONVERSATION)
}
