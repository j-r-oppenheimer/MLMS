package com.cnumed.mlms.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LmsApi @Inject constructor(private val client: OkHttpClient) {

    companion object {
        const val BASE_URL = "https://cnu.u-lms.com"
        const val LOGIN_URL = "$BASE_URL/login"
        const val MAIN_URL = "$BASE_URL/st/main"
        const val NOTICE_URL = "$BASE_URL/common/SLife/notice/list"
        const val NOTICE_AJAX_URL = "$BASE_URL/ajax/common/SLife/notice/list"
        const val TIMETABLE_URL = "$BASE_URL/aca/MYscheduleMST"
        const val SCHEDULE_SHOW_URL = "$BASE_URL/st/lesson/scheduleShow"
    }

    suspend fun get(url: String): String = getWithFinalUrl(url).second

    /** AJAX GET: X-Requested-With 헤더 포함 — 서버가 JSON/HTML fragment 반환할 수 있음 */
    suspend fun getAjax(url: String, referer: String? = null): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            .header("Accept", "text/html, */*; q=0.01")
            .header("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8")
            .header("X-Requested-With", "XMLHttpRequest")
        referer?.let { builder.header("Referer", it) }
        client.newCall(builder.build()).execute().use { response ->
            response.body?.string() ?: ""
        }
    }

    /** 본문과 함께 리다이렉트 후 최종 URL을 반환 — 세션 유효성 체크에 사용 */
    suspend fun getWithFinalUrl(url: String): Pair<String, String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8")
            .build()

        client.newCall(request).execute().use { response ->
            val finalUrl = response.request.url.toString()
            val body = response.body?.string() ?: ""
            Pair(finalUrl, body)
        }
    }

    suspend fun post(url: String, params: Map<String, String>, referer: String? = null): Pair<String, String> = withContext(Dispatchers.IO) {
        val formBody = FormBody.Builder().apply {
            params.forEach { (k, v) -> add(k, v) }
        }.build()

        val requestBuilder = Request.Builder()
            .url(url)
            .post(formBody)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            .header("Content-Type", "application/x-www-form-urlencoded")

        referer?.let { requestBuilder.header("Referer", it) }
        requestBuilder.header("X-Requested-With", "XMLHttpRequest")
        requestBuilder.header("Accept", "application/json, text/javascript, */*; q=0.01")

        client.newCall(requestBuilder.build()).execute().use { response ->
            val finalUrl = response.request.url.toString()
            val body = response.body?.string() ?: ""
            Pair(finalUrl, body)
        }
    }
}
