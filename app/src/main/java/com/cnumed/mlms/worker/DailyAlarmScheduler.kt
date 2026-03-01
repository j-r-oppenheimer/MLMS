package com.cnumed.mlms.worker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.cnumed.mlms.receiver.AlarmReceiver
import java.time.LocalDateTime
import java.time.ZoneId

object DailyAlarmScheduler {

    private const val REQUEST_CODE = 200

    fun schedule(context: Context) {
        val prefs  = context.getSharedPreferences("class_settings", Context.MODE_PRIVATE)
        val hour   = prefs.getInt("alarm_hour", 8)
        val minute = prefs.getInt("alarm_minute", 0)

        val am = context.getSystemService(AlarmManager::class.java)
        val pi = pendingIntent(context)
        val triggerMs = nextAlarmMillis(hour, minute)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            // 정확한 알람 권한 없을 때: Doze에서도 실행되는 근사 알람으로 폴백
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
        }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        am.cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context) = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, AlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun nextAlarmMillis(hour: Int, minute: Int): Long {
        val now = LocalDateTime.now()
        val target = now.toLocalDate().atTime(hour, minute)
        val nextRun = if (now.isBefore(target)) target else target.plusDays(1)
        return nextRun.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
