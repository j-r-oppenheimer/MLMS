package com.cnumed.mlms.data.repository

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import android.util.Log
import com.cnumed.mlms.data.local.dao.ClassDao
import com.cnumed.mlms.data.local.entity.ClassEntity
import com.cnumed.mlms.data.remote.LmsApi
import com.cnumed.mlms.data.remote.LmsParser
import com.cnumed.mlms.data.remote.SessionCookieStore
import com.cnumed.mlms.data.remote.SessionManager
import com.cnumed.mlms.data.remote.TimetableWebLoader
import com.cnumed.mlms.domain.model.ClassItem
import com.cnumed.mlms.domain.model.LessonDetail
import com.cnumed.mlms.domain.model.LessonFile
import com.cnumed.mlms.widget.TimetableWidgetUpdateWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import android.net.Uri
import org.json.JSONObject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TimetableRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val webLoader: TimetableWebLoader,
    private val api: LmsApi,
    private val parser: LmsParser,
    private val classDao: ClassDao,
    private val sessionManager: SessionManager,
    private val httpClient: OkHttpClient,
    private val cookieStore: SessionCookieStore
) {
    // 벌크 로드된 주차 범위 캐시 (min ~ max 사이의 모든 주가 커버됨)
    private var cachedRangeMin: LocalDate? = null
    private var cachedRangeMax: LocalDate? = null

    fun getClassesForWeek(weekStart: LocalDate): Flow<List<ClassItem>> =
        classDao.getClassesByWeek(weekStart.toString())
            .map { list -> list.map { it.toDomain() } }

    fun getClassesForToday(): Flow<List<ClassItem>> =
        classDao.getClassesByDate(LocalDate.now().toString())
            .map { list -> list.map { it.toDomain() } }

    /** 캐시 범위 안이면 true (해당 주에 수업이 0개여도 이미 확인된 것) */
    fun isWeekCached(weekStart: LocalDate): Boolean {
        val min = cachedRangeMin ?: return false
        val max = cachedRangeMax ?: return false
        return weekStart in min..max
    }

    /** 캐시 무효화 (새로고침용) */
    fun invalidateCache() {
        cachedRangeMin = null
        cachedRangeMax = null
    }

    suspend fun fetchWeek(weekStart: LocalDate): Result<List<ClassItem>> {
        return try {
            // 캐시 범위 안이면 DB에서 바로 반환
            if (isWeekCached(weekStart)) {
                Log.d(TAG, "Week $weekStart in cached range — using DB")
                val cached = classDao.getClassesByWeekOnce(weekStart.toString())
                    .map { it.toDomain() }
                return Result.success(cached)
            }

            if (!sessionManager.ensureSession()) {
                return Result.failure(Exception("로그인이 필요합니다"))
            }

            // WebView에서 FullCalendar 전체 이벤트 한 번에 추출
            val json = webLoader.loadAllEvents()
            Log.d(TAG, "WebView all events len=${json.length} | preview=${json.take(300)}")

            val allClasses = parser.parseAllTimetableJson(json)
            Log.d(TAG, "Parsed ${allClasses.size} total classes across all weeks")

            if (allClasses.isEmpty()) {
                Log.d(TAG, "Empty result — no classes loaded from calendar")
                return Result.success(emptyList())
            }

            // 주차별 그룹핑 → DB + 위젯 SharedPreferences에 일괄 저장
            val byWeek = allClasses.groupBy { it.weekStart }
            for ((week, classes) in byWeek) {
                classDao.deleteByWeek(week.toString())
                classDao.insertAll(classes.map { ClassEntity.fromDomain(it) })
                TimetableWidgetUpdateWorker.saveClassesToCache(context, week, classes)
            }

            // 캐시 범위 갱신: 이벤트가 존재하는 최소~최대 주차
            val weeks = byWeek.keys.sorted()
            cachedRangeMin = weeks.first()
            cachedRangeMax = weeks.last()
            Log.d(TAG, "Cached range: $cachedRangeMin ~ $cachedRangeMax (${byWeek.size} weeks)")

            Result.success(byWeek[weekStart] ?: emptyList())
        } catch (e: Exception) {
            Log.e(TAG, "fetchWeek failed", e)
            Result.failure(e)
        }
    }

    suspend fun fetchLessonDetail(lpSeq: Int, currSeq: Int, acaSeq: Int): Result<LessonDetail> {
        return try {
            if (!sessionManager.ensureSession()) {
                return Result.failure(Exception("로그인이 필요합니다"))
            }
            val url = "${LmsApi.SCHEDULE_SHOW_URL}?lp_seq=$lpSeq&curr_seq=$currSeq&aca_seq=$acaSeq"
            val json = webLoader.loadLessonDetail(url)
            val obj = JSONObject(json)

            val subject = obj.optString("subject", "")
            val room = obj.optString("room", "")
            val filesArr = obj.optJSONArray("files")

            val files = mutableListOf<LessonFile>()
            if (filesArr != null) {
                for (i in 0 until filesArr.length()) {
                    val f = filesArr.getJSONObject(i)
                    val path = f.optString("path")
                    val name = f.optString("name")
                    val attachSeq = f.optString("attachSeq", "")
                    val dataSeq = f.optString("dataSeq", "")
                    if (path.isNotEmpty() && name.isNotEmpty()) {
                        val fullPath = if (path.startsWith("/ubladv_res")) path else "/ubladv_res$path"
                        val downloadUrl = Uri.Builder()
                            .scheme("https")
                            .authority("cnu.u-lms.com")
                            .path("/file/download")
                            .appendQueryParameter("file_path", fullPath)
                            .appendQueryParameter("file_name", name)
                            .build()
                            .toString()
                        files.add(LessonFile(name, downloadUrl, attachSeq, dataSeq))
                    }
                }
            }

            Result.success(LessonDetail(subject = subject, room = room, files = files))
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "fetchLessonDetail failed", e)
            Result.failure(e)
        }
    }

    /**
     * DownloadManager를 사용하여 파일 다운로드.
     * 시스템 알림바에 진행률이 자동으로 표시됩니다.
     */
    suspend fun downloadFile(file: LessonFile): Result<Long> = withContext(Dispatchers.IO) {
        try {
            // 1. 서버에 읽음 처리 요청 (이걸 먼저 해야 다운로드 허용됨)
            if (file.attachSeq.isNotEmpty() && file.dataSeq.isNotEmpty()) {
                api.post(
                    "${LmsApi.BASE_URL}/ajax/st/lesson/lessonData/read",
                    mapOf(
                        "lesson_attach_seq" to file.attachSeq,
                        "lesson_data_seq" to file.dataSeq
                    ),
                    LmsApi.BASE_URL
                )
            }

            // 2. DownloadManager로 다운로드 요청
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(file.downloadUrl)).apply {
                addRequestHeader("Referer", LmsApi.BASE_URL)
                addRequestHeader("Cookie", cookieStore.rawCookies)
                addRequestHeader("User-Agent",
                    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                setTitle(file.fileName)
                setDescription("MLMS 파일 다운로드")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, file.fileName)
            }

            val downloadId = dm.enqueue(request)
            Log.d(TAG, "Download enqueued: id=$downloadId, file=${file.fileName}")

            Result.success(downloadId)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "downloadFile failed", e)
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "TimetableRepo"
    }
}
