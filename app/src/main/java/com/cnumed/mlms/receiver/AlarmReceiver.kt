package com.cnumed.mlms.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.cnumed.mlms.worker.DailyAlarmScheduler
import com.cnumed.mlms.worker.DailyCheckWorker

/** AlarmManager가 오전 8시에 호출 → 체크 Worker 즉시 실행 + 다음날 재스케줄 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 체크 작업 즉시 실행 (네트워크 연결 시)
        WorkManager.getInstance(context).enqueueUniqueWork(
            DailyCheckWorker.WORK_NAME,
            androidx.work.ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<DailyCheckWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
        )
        // 다음날 오전 8시 재스케줄
        DailyAlarmScheduler.schedule(context)
    }
}
