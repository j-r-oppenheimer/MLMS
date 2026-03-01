package com.cnumed.mlms.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cnumed.mlms.widget.TimetableWidgetUpdateWorker
import com.cnumed.mlms.worker.DailyAlarmScheduler

/** 재부팅 후 스케줄링 복원 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            TimetableWidgetUpdateWorker.schedule(context)
            DailyAlarmScheduler.schedule(context)
        }
    }
}
