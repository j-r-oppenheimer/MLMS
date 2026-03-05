package com.cnumed.mlms.widget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.cnumed.mlms.data.repository.TimetableRepository
import com.cnumed.mlms.domain.model.ClassItem
import com.cnumed.mlms.util.DateUtils
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

@HiltWorker
class TimetableWidgetUpdateWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: TimetableRepository
) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val channelId = "widget_update"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "위젯 업데이트", NotificationManager.IMPORTANCE_MIN)
            applicationContext.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle("시간표 불러오는 중")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        return ForegroundInfo(9001, notification)
    }

    override suspend fun doWork(): Result {
        return try {
            // 위젯 내비게이션용: weekStart 입력이 있으면 그 주, 없으면 이번 주 (정기 업무)
            val weekStartStr = inputData.getString("weekStart")
            val weekStart = if (weekStartStr != null) LocalDate.parse(weekStartStr)
                            else DateUtils.getWeekStart()

            val result = repository.fetchWeek(weekStart)
            if (result.isSuccess) {
                val classes = result.getOrDefault(emptyList())
                val prefs = applicationContext.getSharedPreferences(
                    TimetableWidget.PREFS_WIDGET, Context.MODE_PRIVATE
                )
                saveWeekJson(prefs, weekStart, classes)
            }

            // 위젯 갱신
            val awm = AppWidgetManager.getInstance(applicationContext)
            val ids = awm.getAppWidgetIds(ComponentName(applicationContext, TimetableWidget::class.java))
            for (id in ids) TimetableWidget.updateWidget(applicationContext, awm, id)

            // 정기 업무인 경우만 다음 스케줄 등록
            if (weekStartStr == null) schedule(applicationContext)
            Result.success()
        } catch (e: Exception) {
            android.util.Log.e("WidgetUpdateWorker", "doWork failed", e)
            Result.retry()
        }
    }

    private fun saveWeekJson(prefs: SharedPreferences, weekStart: LocalDate, classes: List<ClassItem>) {
        val arr = JSONArray()
        classes.forEach { cls ->
            arr.put(JSONObject().apply {
                put("title", cls.title)
                put("professor", cls.professor)
                put("dayOfWeek", cls.dayOfWeek)
                put("date", cls.date.toString())
                put("startTime", cls.startTime.toString())
                put("endTime", cls.endTime.toString())
            })
        }
        // 주별 독립 키로 저장 (TimetableWidget.loadClasses와 동일한 키 형식)
        prefs.edit().putString("week_classes_$weekStart", arr.toString()).apply()
    }

    companion object {
        const val WORK_NAME = "widget_daily_update"

        /** Fragment 등 앱 내부에서 위젯 캐시를 즉시 갱신할 때 사용 */
        fun saveClassesToCache(context: Context, weekStart: LocalDate, classes: List<ClassItem>) {
            val prefs = context.getSharedPreferences(TimetableWidget.PREFS_WIDGET, Context.MODE_PRIVATE)
            val arr = JSONArray()
            classes.forEach { cls ->
                arr.put(JSONObject().apply {
                    put("title", cls.title)
                    put("professor", cls.professor)
                    put("dayOfWeek", cls.dayOfWeek)
                    put("date", cls.date.toString())
                    put("startTime", cls.startTime.toString())
                    put("endTime", cls.endTime.toString())
                })
            }
            prefs.edit().putString("week_classes_$weekStart", arr.toString()).apply()
        }

        fun schedule(context: Context) {
            val now = LocalDateTime.now()
            val target = now.toLocalDate().atTime(LocalTime.of(6, 0))
            val nextRun = if (now.isBefore(target)) target else target.plusDays(1)
            val delayMs = java.time.Duration.between(now, nextRun).toMillis()

            val request = OneTimeWorkRequestBuilder<TimetableWidgetUpdateWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
