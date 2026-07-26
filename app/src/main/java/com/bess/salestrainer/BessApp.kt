package com.bess.salestrainer

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class BessApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Gate 1 (TASK-M-03): initialize WorkManager + detect empty DB + trigger bundled corpus import.
    }
}
