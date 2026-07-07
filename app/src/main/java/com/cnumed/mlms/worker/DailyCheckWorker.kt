package com.cnumed.mlms.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.cnumed.mlms.data.repository.NoticeRepository
import com.cnumed.mlms.data.repository.TimetableRepository
import com.cnumed.mlms.domain.model.ClassItem
import com.cnumed.mlms.util.DateUtils
import com.cnumed.mlms.widget.TimetableWidget
import com.cnumed.mlms.widget.TimetableWidgetUpdateWorker
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

@HiltWorker
class DailyCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val timetableRepository: TimetableRepository,
    private val noticeRepository: NoticeRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            ensureChannels()
            checkTimetable()
            checkNotices()
            Result.success()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "DailyCheckWorker failed", e)
            Result.failure()
        }
    }

    // ── 시간표 변경 감지 ────────────────────────────────────────────────────

    private suspend fun checkTimetable() {
        val weekStart = DateUtils.getWeekStart()

        // fetch 전 DB 스냅샷
        val oldClasses = timetableRepository.getClassesForWeek(weekStart).first()

        // 캐시 무효화 — 프로세스가 살아있을 때 in-memory 캐시로 인해 네트워크 fetch가
        // 생략되면 변경을 영영 감지하지 못하므로, 백그라운드 체크는 항상 새로 가져온다.
        timetableRepository.invalidateCache()
        val result = timetableRepository.fetchWeek(weekStart)
        if (result.isSuccess) {
            val newClasses = result.getOrDefault(emptyList())
            // 빈 결과는 캐시에 쓰지 않음 — 로드 실패/세션 만료 시 기존 시간표 보존
            if (newClasses.isNotEmpty()) {
                TimetableWidgetUpdateWorker.saveClassesToCache(applicationContext, weekStart, newClasses)
                val awm = AppWidgetManager.getInstance(applicationContext)
                val ids = awm.getAppWidgetIds(ComponentName(applicationContext, TimetableWidget::class.java))
                for (id in ids) TimetableWidget.updateWidget(applicationContext, awm, id)
                com.cnumed.mlms.widget.CountdownWidget.refreshAll(applicationContext)

                if (hasChanges(oldClasses, newClasses)) {
                    sendTimetableNotification(oldClasses, newClasses)
                }
            }
        }
    }

    private fun hasChanges(old: List<ClassItem>, new: List<ClassItem>): Boolean {
        val fp = { c: ClassItem -> "${c.title}|${c.dayOfWeek}|${c.startTime}|${c.endTime}" }
        return old.map(fp).toSet() != new.map(fp).toSet()
    }

    private fun sendTimetableNotification(old: List<ClassItem>, new: List<ClassItem>) {
        val diff = new.size - old.size
        val body = when {
            diff > 0  -> "수업 ${diff}개가 추가되었습니다."
            diff < 0  -> "수업 ${-diff}개가 취소되었습니다."
            else      -> "수업 시간이 변경되었습니다."
        }
        notify(NOTIF_ID_TIMETABLE, CHANNEL_TIMETABLE, "이번 주 시간표 변경", body)
    }

    // ── 새 공지 감지 ────────────────────────────────────────────────────────

    private suspend fun checkNotices() {
        // fetch 전 기존 ID 집합
        val oldIds = noticeRepository.getNoticeIds()

        val result = noticeRepository.fetchNotices()
        if (result.isSuccess) {
            result.getOrDefault(emptyList())
                .filter { it.id !in oldIds }
                .forEachIndexed { idx, notice ->
                    notify(
                        NOTIF_ID_NOTICE_BASE + idx,
                        CHANNEL_NOTICE,
                        "새 공지사항",
                        notice.title
                    )
                }
        }
    }

    // ── 알림 전송 ────────────────────────────────────────────────────────────

    private fun notify(id: Int, channel: String, title: String, body: String) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(applicationContext, channel)
            .setSmallIcon(com.cnumed.mlms.R.drawable.ic_notification)
            .setColor(0xFF7787A1.toInt())
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        nm.notify(id, notification)
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            listOf(
                NotificationChannel(CHANNEL_TIMETABLE, "시간표 변경", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "시간표에 변경 사항이 있을 때 알림" },
                NotificationChannel(CHANNEL_NOTICE, "새 공지사항", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "새 공지사항이 등록될 때 알림" }
            ).forEach { nm.createNotificationChannel(it) }
        }
    }

    companion object {
        private const val TAG = "DailyCheckWorker"
        const val WORK_NAME = "daily_check"
        const val PERIODIC_WORK_NAME = "periodic_check"

        private const val CHANNEL_TIMETABLE    = "timetable_change"
        private const val CHANNEL_NOTICE       = "new_notice"
        private const val NOTIF_ID_TIMETABLE   = 1001
        private const val NOTIF_ID_NOTICE_BASE = 2000

        /**
         * 앱 실행 여부와 무관하게 주기적으로 시간표/공지 변경을 체크한다.
         * 단발 AlarmManager 알람(DailyAlarmScheduler)은 Doze·제조사 배터리 최적화로
         * 누락되면 다음 재스케줄까지 끊기므로, WorkManager 주기 작업으로 보강한다.
         * WorkManager는 재부팅 후에도 자동 복원된다.
         */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<DailyCheckWorker>(3, java.util.concurrent.TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
