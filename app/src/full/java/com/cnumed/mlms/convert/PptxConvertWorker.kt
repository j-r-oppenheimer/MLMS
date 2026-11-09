package com.cnumed.mlms.convert

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.OutOfQuotaPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.cnumed.mlms.R
import com.cnumed.mlms.data.remote.LmsApi
import com.cnumed.mlms.data.remote.SessionManager
import com.cnumed.mlms.pdfconvert.GoogleFontsFetcher
import com.cnumed.mlms.pdfconvert.PptxPdfConverter
import com.cnumed.mlms.util.AppLog
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * PPTX 강의자료를 받아 PDF로 변환해 다운로드 폴더에 저장한다.
 * 변환에 실패하면 원본 PPTX를 대신 저장한다.
 */
@HiltWorker
class PptxConvertWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val httpClient: OkHttpClient,
    private val sessionManager: SessionManager,
) : CoroutineWorker(context, params) {

    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val notifId = inputData.getInt(KEY_NOTIF_ID, (System.currentTimeMillis() % 100000).toInt() + 5000)

    /** 긴급(expedited) 작업이 Android 11 이하에서 포그라운드로 돌 때 쓰는 알림 */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        ensureChannel(applicationContext)
        return ForegroundInfo(notifId, buildProgress(inputData.getString(KEY_PDF_NAME) ?: "PDF 변환", "준비 중…", 0, 0))
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url = inputData.getString(KEY_URL) ?: return@withContext Result.failure()
        val pdfName = inputData.getString(KEY_PDF_NAME) ?: "lecture.pdf"
        val origName = inputData.getString(KEY_ORIG_NAME) ?: pdfName.removeSuffix(".pdf") + ".pptx"
        ensureChannel(applicationContext)
        val work = File(applicationContext.cacheDir, "pdfconvert/${id}").apply { mkdirs() }
        val src = File(work, "source.pptx")
        val out = File(work, "out.pdf")
        try {
            progress(pdfName, "강의자료 받는 중…", 0, 0)
            sessionManager.ensureSession()
            download(url, src)

            val ctx = applicationContext
            val ocr = ConvertPrefs.ocr(ctx)
            val platform = AndroidConverterPlatform(ctx, ocr)
            val converter = PptxPdfConverter(platform)
            if (ConvertPrefs.fetchFonts(ctx)) {
                runCatching {
                    val missing = converter.missingFontFamilies(src)
                    if (missing.isNotEmpty()) {
                        progress(pdfName, "필요한 글꼴 받는 중…", 0, 0)
                        GoogleFontsFetcher(UserFontStore.googleDir(ctx)) { m, t -> AppLog.e(TAG, m, t) }.fetch(missing)
                    }
                }.onFailure { AppLog.e(TAG, "font fetch failed", it) }
            }
            val result = try {
                converter.convert(src, out, ocr = ocr,
                    onProgress = { done, total -> progress(pdfName, if (ocr) "PDF로 변환 중 (글자 인식 포함)" else "PDF로 변환 중", done, total) },
                    isCancelled = { isStopped })
            } finally {
                platform.close()
            }
            val uri = saveToDownloads(out, pdfName, "application/pdf")
            done(pdfName, "PDF 변환 완료 (${result.pages}쪽)" + if (result.skippedHidden > 0) " · 숨긴 슬라이드 ${result.skippedHidden}장 제외" else "", uri, "application/pdf")
            Result.success()
        } catch (e: Throwable) {
            AppLog.e(TAG, "convert failed", e)
            writeLog(applicationContext, "convert failed: $pdfName", e)
            if (isStopped) return@withContext Result.failure()
            // 실패 시 원본이라도 저장
            val saved = if (src.exists() && src.length() > 0) runCatching {
                saveToDownloads(src, origName, "application/vnd.openxmlformats-officedocument.presentationml.presentation")
            }.getOrNull() else null
            if (saved != null) done(origName, "PDF 변환 실패 — 원본 PPT를 저장했습니다", saved, "application/vnd.openxmlformats-officedocument.presentationml.presentation")
            else done(pdfName, "다운로드 실패: ${e.message ?: "알 수 없는 오류"}", null, null)
            Result.failure()
        } finally {
            work.deleteRecursively()
        }
    }

    private fun download(url: String, dest: File) {
        val req = Request.Builder().url(url)
            .header("Referer", LmsApi.BASE_URL)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            .build()
        httpClient.newBuilder().readTimeout(120, java.util.concurrent.TimeUnit.SECONDS).build().newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("서버 응답 ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("빈 응답")
            val total = body.contentLength()
            body.byteStream().use { input ->
                dest.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var sum = 0L; var last = 0L
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        o.write(buf, 0, n); sum += n
                        if (total > 0 && sum - last > total / 50) { last = sum; progress(inputData.getString(KEY_PDF_NAME) ?: "", "강의자료 받는 중…", (sum * 100 / total).toInt(), 100) }
                    }
                }
            }
        }
        // PPTX는 zip이어야 한다 (서버가 Content-Type을 엉뚱하게 줘도 내용으로 판단)
        val head = ByteArray(16)
        val n = dest.inputStream().use { it.read(head) }
        if (n < 2 || head[0] != 0x50.toByte() || head[1] != 0x4B.toByte()) {
            val text = String(head, 0, maxOf(0, n)).trimStart()
            throw IllegalStateException(if (text.startsWith("<")) "로그인이 만료됐거나 파일에 접근할 수 없습니다" else "PPTX 파일이 아닙니다")
        }
    }

    private fun saveToDownloads(file: File, name: String, mime: String): Uri? {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("저장 위치를 만들 수 없습니다")
            resolver.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } }
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        }
        // Android 9 이하: 공용 다운로드 폴더 (권한이 없으면 앱 전용 폴더)
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        @Suppress("DEPRECATION")
        val dir = if (granted) Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        else ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir
        dir.mkdirs()
        var target = File(dir, name)
        var n = 1
        while (target.exists()) { target = File(dir, name.substringBeforeLast('.') + " ($n)." + name.substringAfterLast('.')); n++ }
        file.copyTo(target)
        return FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", target)
    }

    private fun buildProgress(title: String, text: String, done: Int, total: Int) =
        NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(if (total > 0 && text.startsWith("PDF")) "$text ($done/$total)" else text)
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun progress(title: String, text: String, done: Int, total: Int) {
        runCatching { nm.notify(notifId, buildProgress(title, text, done, total)) }
    }

    private fun done(title: String, text: String, uri: Uri?, mime: String?) {
        nm.cancel(notifId)
        val b = NotificationCompat.Builder(applicationContext, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
        if (uri != null) {
            val open = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            b.setContentIntent(PendingIntent.getActivity(applicationContext, notifId, open,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        runCatching { nm.notify(notifId + 1, b.build()) }
    }

    companion object {
        private const val TAG = "PptxConvertWorker"
        private const val CHANNEL = "pptx_convert"
        private const val CHANNEL_DONE = "pptx_convert_done"
        const val KEY_URL = "url"
        const val KEY_PDF_NAME = "pdf_name"
        const val KEY_ORIG_NAME = "orig_name"
        const val KEY_NOTIF_ID = "notif_id"

        fun ensureChannel(c: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "PPT → PDF 변환", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "강의자료 PDF 변환 진행 상황"; setSound(null, null); enableVibration(false) })
                nm.createNotificationChannel(NotificationChannel(CHANNEL_DONE, "PDF 변환 완료", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "강의자료 PDF 변환 결과" })
            }
        }

        /** 오류를 Android/data/com.cnumed.mlms/files/convert_log.txt 에 남긴다 (문제 확인용) */
        fun writeLog(c: Context, msg: String, t: Throwable?) {
            runCatching {
                val f = File(c.getExternalFilesDir(null) ?: c.filesDir, "convert_log.txt")
                if (f.length() > 512 * 1024) f.delete()
                f.appendText("[${java.time.LocalDateTime.now()}] $msg\n" + (t?.stackTraceToString() ?: "") + "\n")
            }
        }

        /** @return 앱 알림이 켜져 있으면 true */
        fun enqueue(context: Context, url: String, pdfName: String, origName: String): Boolean {
            val notifId = (System.currentTimeMillis() % 100000).toInt() + 5000
            ensureChannel(context)
            // 작업이 시작되기 전에도 바로 보이도록 대기 알림을 먼저 띄운다
            runCatching {
                val n = NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(pdfName)
                    .setContentText("PDF 변환 대기 중…")
                    .setProgress(0, 0, true)
                    .build()
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(notifId, n)
            }
            val req = OneTimeWorkRequestBuilder<PptxConvertWorker>()
                .setInputData(workDataOf(KEY_URL to url, KEY_PDF_NAME to pdfName, KEY_ORIG_NAME to origName, KEY_NOTIF_ID to notifId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueue(req)
            return androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }
}
