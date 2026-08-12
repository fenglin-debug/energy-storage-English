package com.bess.salestrainer

import android.app.Application
import com.bess.salestrainer.startup.AppStartupCoordinator
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class BessApp : Application() {

    @Inject lateinit var startupCoordinator: AppStartupCoordinator

    override fun onCreate() {
        super.onCreate()
        startupCoordinator.start()
    }
}
