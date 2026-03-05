package com.cnumed.mlms.data.remote

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * 백그라운드 WebView로 시간표 페이지를 로드한 뒤
 * FullCalendar JavaScript API로 이벤트를 추출.
 * (OkHttp는 JS를 실행할 수 없어 렌더링된 이벤트를 볼 수 없음)
 */
@Singleton
class TimetableWebLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cookieStore: SessionCookieStore
) {
    /**
     * WebView에서 FullCalendar가 보유한 **전체** clientEvents를 추출.
     * 날짜 필터 없이 모든 이벤트를 반환하므로 한 번 호출로 여러 주차 데이터를 얻을 수 있음.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun loadAllEvents(): String =
        suspendCancellableCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                var settled = false
                val webView = WebView(context)
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString =
                        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                }

                // SessionCookieStore 쿠키를 CookieManager에 동기화
                val rawCookies = cookieStore.rawCookies
                if (rawCookies.isNotEmpty()) {
                    rawCookies.split(";").forEach { cookie ->
                        CookieManager.getInstance().setCookie(LmsApi.BASE_URL, cookie.trim())
                    }
                }
                CookieManager.getInstance().setAcceptCookie(true)

                val mainHandler = Handler(Looper.getMainLooper())

                fun resolve(json: String) {
                    if (!settled) {
                        settled = true
                        webView.destroy()
                        cont.resume(json)
                    }
                }

                val timeoutRunnable = Runnable {
                    Log.w(TAG, "TimetableWebLoader timeout")
                    resolve("[]")
                }
                mainHandler.postDelayed(timeoutRunnable, 35_000)

                cont.invokeOnCancellation {
                    mainHandler.removeCallbacks(timeoutRunnable)
                    Handler(Looper.getMainLooper()).post { webView.destroy() }
                }

                fun extractAllEvents() {
                    mainHandler.removeCallbacks(timeoutRunnable)
                    // 날짜 필터 없이 전체 clientEvents 추출
                    val js = """
                    (function() {
                        try {
                            var fcEl = document.querySelector('.fc');
                            if (!fcEl) return [];
                            var jq = window.jQuery || window.${'$'};
                            if (!jq) return [];
                            var events = jq(fcEl).fullCalendar('clientEvents');
                            if (!events || events.length === 0) return [];
                            return events
                                .filter(function(e) { return !!e.start; })
                                .map(function(e) {
                                    return {
                                        title: e.title || '',
                                        start: e.start.format('YYYY-MM-DDTHH:mm:ss'),
                                        end:   e.end ? e.end.format('YYYY-MM-DDTHH:mm:ss') : '',
                                        url:   e.url || ''
                                    };
                                });
                        } catch(ex) {
                            return [{error: String(ex)}];
                        }
                    })()
                    """.trimIndent()

                    webView.evaluateJavascript(js) { result ->
                        Log.d(TAG, "All events JS result (${result?.length}): ${result?.take(300)}")
                        resolve(result ?: "[]")
                    }
                }

                // 이벤트 로드를 300ms 간격으로 폴링 (최대 10회 = 3초)
                fun pollForEvents(view: WebView, attempt: Int) {
                    if (settled) return
                    val checkJs = """
                    (function() {
                        try {
                            var jq = window.jQuery || window.${'$'};
                            var events = jq('.fc').fullCalendar('clientEvents');
                            return events ? events.length : 0;
                        } catch(e) { return -1; }
                    })()
                    """.trimIndent()
                    mainHandler.postDelayed({
                        if (settled) return@postDelayed
                        view.evaluateJavascript(checkJs) { r ->
                            val count = r?.trim()?.toIntOrNull() ?: -1
                            Log.d(TAG, "Poll attempt ${attempt + 1}: $count total events")
                            when {
                                count > 0 -> {
                                    Log.d(TAG, "Events found after ${(attempt + 1) * 300}ms")
                                    extractAllEvents()
                                }
                                attempt < 9 -> pollForEvents(view, attempt + 1)
                                else -> {
                                    Log.w(TAG, "Poll timeout — extracting anyway")
                                    extractAllEvents()
                                }
                            }
                        }
                    }, 300)
                }

                webView.webViewClient = object : WebViewClient() {
                    private var calendarLoaded = false

                    override fun onPageFinished(view: WebView, url: String) {
                        if (settled || calendarLoaded) return
                        Log.d(TAG, "onPageFinished: $url")
                        if (!url.contains("MYscheduleMST", ignoreCase = true)) return
                        calendarLoaded = true

                        // FullCalendar 초기화 + AJAX 로드 대기 후 전체 이벤트 추출
                        mainHandler.postDelayed({
                            if (settled) return@postDelayed
                            Log.d(TAG, "Polling for all events")
                            pollForEvents(view, 0)
                        }, 500)
                    }
                }

                Log.d(TAG, "Loading timetable via WebView")
                webView.loadUrl(LmsApi.TIMETABLE_URL)
            }
        }

    /**
     * scheduleShow 페이지를 WebView로 로드 후 JS 실행 완료된 DOM에서
     * 과목명, 강의실, 첨부파일 정보를 JSON으로 추출.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun loadLessonDetail(url: String): String =
        suspendCancellableCoroutine { cont ->
            Handler(Looper.getMainLooper()).post {
                var settled = false
                val webView = WebView(context)
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString =
                        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                }

                val rawCookies = cookieStore.rawCookies
                if (rawCookies.isNotEmpty()) {
                    rawCookies.split(";").forEach { cookie ->
                        CookieManager.getInstance().setCookie(LmsApi.BASE_URL, cookie.trim())
                    }
                }
                CookieManager.getInstance().setAcceptCookie(true)

                val mainHandler = Handler(Looper.getMainLooper())

                fun resolve(json: String) {
                    if (!settled) {
                        settled = true
                        webView.destroy()
                        cont.resume(json)
                    }
                }

                val timeoutRunnable = Runnable {
                    Log.w(TAG, "loadLessonDetail timeout")
                    resolve("{}")
                }
                mainHandler.postDelayed(timeoutRunnable, 15_000)

                cont.invokeOnCancellation {
                    mainHandler.removeCallbacks(timeoutRunnable)
                    Handler(Looper.getMainLooper()).post { webView.destroy() }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, loadedUrl: String) {
                        if (settled) return
                        mainHandler.postDelayed({
                            if (settled) return@postDelayed
                            val js = """
                            (function() {
                                var subjectEl = document.getElementById('subject');
                                var subject = '';
                                if (subjectEl) {
                                    for (var i = 0; i < subjectEl.childNodes.length; i++) {
                                        if (subjectEl.childNodes[i].nodeType === 3) {
                                            subject = subjectEl.childNodes[i].textContent.trim();
                                            if (subject) break;
                                        }
                                    }
                                }

                                var room = '';
                                var lis = document.querySelectorAll('ul.content-list li');
                                for (var i = 0; i < lis.length; i++) {
                                    var t = lis[i].textContent.trim();
                                    if (t && t.indexOf('교시') === -1 && t.indexOf('~') === -1
                                        && t !== '강의' && t !== '실습' && t !== '세미나' && t !== '시험') {
                                        room = t;
                                        break;
                                    }
                                }

                                var files = [];
                                var links = document.querySelectorAll('#lesson_plan_data a[onclick*="attachEvent"]');
                                for (var i = 0; i < links.length; i++) {
                                    var onclick = links[i].getAttribute('onclick') || '';
                                    var m = onclick.match(/attachEvent\s*\(\s*'[^']*'\s*,\s*'([^']+)'\s*,\s*'([^']+)'\s*,\s*'(\d+)'\s*,\s*'(\d+)'/);
                                    if (m) {
                                        files.push({path: m[1], name: m[2], attachSeq: m[3], dataSeq: m[4]});
                                    }
                                }

                                return JSON.stringify({subject: subject, room: room, files: files});
                            })()
                            """.trimIndent()

                            view.evaluateJavascript(js) { result ->
                                mainHandler.removeCallbacks(timeoutRunnable)
                                val cleaned = result
                                    ?.trim()
                                    ?.removeSurrounding("\"")
                                    ?.replace("\\\"", "\"")
                                    ?.replace("\\\\", "\\")
                                    ?: "{}"
                                resolve(cleaned)
                            }
                        }, 1500)
                    }
                }

                webView.loadUrl(url)
            }
        }

    companion object {
        private const val TAG = "TimetableWebLoader"
    }
}
