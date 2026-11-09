package com.cnumed.mlms.data.repository

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import com.cnumed.mlms.util.AppLog
import com.cnumed.mlms.convert.ConvertPrefs
import com.cnumed.mlms.convert.PptxFeature
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
import com.cnumed.mlms.widget.CountdownWidget
import com.cnumed.mlms.widget.TimetableWidget
import com.cnumed.mlms.widget.TimetableWidgetUpdateWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    // 앱·위젯·워커의 동시 fetch가 서로의 DB 교체와 겹치지 않도록 직렬화
    private val fetchMutex = Mutex()

    fun getClassesForWeek(weekStart: LocalDate): Flow<List<ClassItem>> =
        classDao.getClassesByWeek(weekStart.toString())
            .map { list -> list.map { it.toDomain() } }

    fun getClassesForToday(): Flow<List<ClassItem>> =
        classDao.getClassesByDate(LocalDate.now().toString())
            .map { list -> list.map { it.toDomain() } }

    /**
     * 캐시 범위 안이거나 직전에 전체 달력을 성공적으로 받아왔으면 true
     * (해당 주에 수업이 0개여도 이미 확인된 것 — 범위 밖의 빈 주도 다시 받지 않는다)
     */
    fun isWeekCached(weekStart: LocalDate): Boolean {
        val prefs = context.getSharedPreferences(TimetableWidget.PREFS_WIDGET, Context.MODE_PRIVATE)
        if (TimetableWidget.isFullFetchFresh(prefs)) return true
        val min = cachedRangeMin ?: return false
        val max = cachedRangeMax ?: return false
        return weekStart in min..max
    }

    /** 캐시 무효화 (새로고침용) */
    fun invalidateCache() {
        cachedRangeMin = null
        cachedRangeMax = null
        TimetableWidget.clearFullFetch(context)
    }

    suspend fun fetchWeek(weekStart: LocalDate): Result<List<ClassItem>> = fetchMutex.withLock {
        try {
            // 캐시 범위 안이면 DB에서 바로 반환
            if (isWeekCached(weekStart)) {
                AppLog.d(TAG, "Week $weekStart in cached range — using DB")
                val cached = classDao.getClassesByWeekOnce(weekStart.toString())
                    .map { it.toDomain() }
                // 캐시 범위 안이면 수업이 0개여도 '확인된 빈 주(방학)' — 위젯 캐시에도
                // 빈 배열로 기록해 위젯이 로딩 상태로 남지 않게 한다.
                TimetableWidgetUpdateWorker.saveClassesToCache(context, weekStart, cached)
                return@withLock Result.success(cached)
            }

            if (!sessionManager.ensureSession()) {
                return@withLock Result.failure(Exception("로그인이 필요합니다"))
            }

            // WebView에서 FullCalendar 전체 이벤트 한 번에 추출
            val json = webLoader.loadAllEvents()
                ?: return@withLock Result.failure(Exception("시간표를 불러오지 못했습니다"))
            AppLog.d(TAG, "WebView all events len=${json.length} | preview=${json.take(300)}")

            val allClasses = parser.parseAllTimetableJson(json)
            AppLog.d(TAG, "Parsed ${allClasses.size} total classes across all weeks")

            if (allClasses.isEmpty()) {
                AppLog.d(TAG, "Empty result — no classes loaded from calendar")
                return@withLock Result.success(emptyList())
            }

            // 주차별 그룹핑 → DB + 위젯 SharedPreferences에 일괄 저장
            val byWeek = allClasses.groupBy { it.weekStart }
            // 이번에 수업이 없어진 주가 예전 시간표로 남지 않도록 위젯 주차 캐시를 비우고 새로 쓴다.
            TimetableWidget.clearWeekCaches(context)
            classDao.replaceAll(byWeek.entries.associate { (week, classes) ->
                week.toString() to classes.map { ClassEntity.fromDomain(it) }
            })
            for ((week, classes) in byWeek) {
                TimetableWidgetUpdateWorker.saveClassesToCache(context, week, classes)
            }

            // 캐시 범위 갱신: 이벤트가 존재하는 최소~최대 주차
            val weeks = byWeek.keys.sorted()
            cachedRangeMin = weeks.first()
            cachedRangeMax = weeks.last()
            AppLog.d(TAG, "Cached range: $cachedRangeMin ~ $cachedRangeMax (${byWeek.size} weeks)")

            // 로드는 성공했으나 요청한 주에 수업이 없으면(방학) 빈 배열을 위젯 캐시에 기록.
            // 위 byWeek 루프는 수업이 있는 주만 저장하므로, 빈 주는 여기서 명시적으로
            // 기록하지 않으면 위젯 캐시 키가 생기지 않아 위젯이 무한 로딩된다.
            val weekClasses = byWeek[weekStart] ?: emptyList()
            if (weekClasses.isEmpty()) {
                TimetableWidgetUpdateWorker.saveClassesToCache(context, weekStart, emptyList())
            }
            TimetableWidget.markFullFetch(context)

            // 위젯이 보고 있는 주가 이번에 요청한 주와 달라도 새 캐시로 다시 그린다
            // (앱에서 받아온 뒤에도 위젯이 로딩 화면에 남아 있지 않게).
            TimetableWidget.refreshAll(context)
            CountdownWidget.refreshAll(context)
            Result.success(weekClasses)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e(TAG, "fetchWeek failed", e)
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
            AppLog.e(TAG, "fetchLessonDetail failed", e)
            Result.failure(e)
        }
    }

    /**
     * 파일 다운로드.
     *  - 설정의 'PPT → PDF 변환'이 켜져 있고 PPTX면 백그라운드 작업으로 받아서 PDF로 변환해 저장
     *  - 그 밖에는 DownloadManager (시스템 알림바에 진행률 표시)
     * @return 사용자에게 보여줄 안내 문구
     */
    suspend fun downloadFile(file: LessonFile): Result<String> = withContext(Dispatchers.IO) {
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

            val ext = file.fileName.substringAfterLast('.', "").lowercase()
            val convert = PptxFeature.AVAILABLE && ConvertPrefs.pptxToPdf(context)
            if (convert && ext in setOf("pptx", "ppsx", "pptm", "potx")) {
                val pdfName = file.fileName.substringBeforeLast('.') + ".pdf"
                val origName = file.fileName
                val notifOn = PptxFeature.enqueueConversion(context, file.downloadUrl, pdfName, origName)
                return@withContext Result.success(
                    if (notifOn) "PDF로 변환해서 받는 중 — 알림에서 진행 상황을 볼 수 있어요"
                    else "PDF로 변환해서 받는 중 (앱 알림이 꺼져 있어 진행 상황은 안 보여요) — 끝나면 다운로드 폴더에 저장됩니다"
                )
            }
            val saveName = file.fileName

            // 2. DownloadManager로 다운로드 요청
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(file.downloadUrl)).apply {
                addRequestHeader("Referer", LmsApi.BASE_URL)
                addRequestHeader("Cookie", cookieStore.rawCookies)
                addRequestHeader("User-Agent",
                    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                setTitle(saveName)
                setDescription("MLMS 파일 다운로드")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, saveName)
            }

            val downloadId = dm.enqueue(request)
            AppLog.d(TAG, "Download enqueued: id=$downloadId, file=${file.fileName}")

            val note = if (convert && ext == "ppt") " (구형 .ppt는 변환할 수 없어 원본으로 받아요)" else ""
            Result.success("$saveName 다운로드 시작$note")
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e(TAG, "downloadFile failed", e)
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "TimetableRepo"
    }
}
