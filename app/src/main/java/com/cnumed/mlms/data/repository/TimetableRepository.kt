package com.cnumed.mlms.data.repository

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.cnumed.mlms.data.local.dao.ClassDao
import com.cnumed.mlms.data.local.entity.ClassEntity
import com.cnumed.mlms.data.remote.LmsApi
import com.cnumed.mlms.data.remote.LmsParser
import com.cnumed.mlms.data.remote.SessionManager
import com.cnumed.mlms.data.remote.TimetableWebLoader
import com.cnumed.mlms.domain.model.ClassItem
import com.cnumed.mlms.domain.model.LessonDetail
import com.cnumed.mlms.domain.model.LessonFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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
    private val httpClient: OkHttpClient
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
     * OkHttp(세션 쿠키 포함)로 파일 다운로드 → MediaStore를 통해 Downloads 폴더에 저장.
     */
    suspend fun downloadFile(file: LessonFile): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            // 1. 서버에 읽음 처리 요청 (이걸 먼저 해야 다운로드 허용됨)
            if (file.attachSeq.isNotEmpty() && file.dataSeq.isNotEmpty()) {
                val readResult = api.post(
                    "${LmsApi.BASE_URL}/ajax/st/lesson/lessonData/read",
                    mapOf(
                        "lesson_attach_seq" to file.attachSeq,
                        "lesson_data_seq" to file.dataSeq
                    ),
                    LmsApi.BASE_URL
                )
            }

            // 2. 파일 다운로드
            val request = Request.Builder()
                .url(file.downloadUrl)
                .header("Referer", LmsApi.BASE_URL)
                .header("User-Agent",
                    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP ${response.code}"))
            }

            val body = response.body ?: return@withContext Result.failure(Exception("빈 응답"))

            // 3. MediaStore로 Downloads 폴더에 저장
            val mimeType = response.header("Content-Type") ?: "application/octet-stream"
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType.substringBefore(";"))
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                ?: return@withContext Result.failure(Exception("MediaStore insert 실패"))

            resolver.openOutputStream(uri)?.use { output ->
                body.byteStream().use { input ->
                    input.copyTo(output)
                }
            }

            contentValues.clear()
            contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)

            Result.success(uri)
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
