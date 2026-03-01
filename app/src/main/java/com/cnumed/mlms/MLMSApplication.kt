package com.cnumed.mlms

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.cnumed.mlms.widget.TimetableWidgetUpdateWorker
import com.cnumed.mlms.worker.DailyAlarmScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MLMSApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        TimetableWidgetUpdateWorker.schedule(this)
        DailyAlarmScheduler.schedule(this)
    }
}
