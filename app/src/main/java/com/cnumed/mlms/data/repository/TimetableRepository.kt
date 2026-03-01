package com.cnumed.mlms.data.repository

import android.util.Log
import com.cnumed.mlms.data.local.dao.ClassDao
import com.cnumed.mlms.data.local.entity.ClassEntity
import com.cnumed.mlms.data.remote.LmsParser
import com.cnumed.mlms.data.remote.SessionManager
import com.cnumed.mlms.data.remote.TimetableWebLoader
import com.cnumed.mlms.domain.model.ClassItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TimetableRepository @Inject constructor(
    private val webLoader: TimetableWebLoader,
    private val parser: LmsParser,
    private val classDao: ClassDao,
    private val sessionManager: SessionManager
) {
    fun getClassesForWeek(weekStart: LocalDate): Flow<List<ClassItem>> =
        classDao.getClassesByWeek(weekStart.toString())
            .map { list -> list.map { it.toDomain() } }

    fun getClassesForToday(): Flow<List<ClassItem>> =
        classDao.getClassesByDate(LocalDate.now().toString())
            .map { list -> list.map { it.toDomain() } }

    suspend fun fetchWeek(weekStart: LocalDate): Result<List<ClassItem>> {
        return try {
            if (!sessionManager.ensureSession()) {
                return Result.failure(Exception("로그인이 필요합니다"))
            }

            // WebView로 시간표 로드 → FullCalendar JS API로 이벤트 추출
            val json = webLoader.loadWeekEvents(weekStart)
            Log.d(TAG, "WebView events len=${json.length} | preview=${json.take(300)}")

            val classes = parser.parseTimetable(json, weekStart)
            Log.d(TAG, "Parsed ${classes.size} classes")

            if (classes.isEmpty()) {
                // 빈 결과 = 해당 주에 수업이 없음 (네트워크는 성공)
                // DB는 건드리지 않고 success(emptyList) 반환 → 위젯은 캐시 저장, 앱은 기존 DB 유지
                Log.d(TAG, "Empty result for $weekStart — no classes this week")
                return Result.success(emptyList())
            }

            classDao.deleteByWeek(weekStart.toString())
            classDao.insertAll(classes.map { ClassEntity.fromDomain(it) })

            Result.success(classes)
        } catch (e: Exception) {
            Log.e(TAG, "fetchWeek failed", e)
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "TimetableRepo"
    }
}
