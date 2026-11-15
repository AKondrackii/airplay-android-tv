package com.airplaytv.app

import android.app.Application
import com.airplaytv.core.AppLog

class AirPlayApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        AppLog.minLevel = if (BuildConfig.DEBUG) AppLog.Level.DEBUG else AppLog.Level.INFO
        graph = AppGraph(this)
    }
}
