package com.cnumed.mlms.data.remote

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.cnumed.mlms.util.AppLog
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * 백그라운드(숨겨진) WebView로 로그인.
 * 성공 판정은 /ajax/login 응답의 status로 한다.
 * 성공 시 WebView 쿠키를 SessionCookieStore에 저장 → OkHttp 인터셉터가 Cookie 헤더로 주입.
 */
@Singleton
class HybridLoginHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cookieStore: SessionCookieStore
) {
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun login(id: String, pwd: String): LoginResult =
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
                CookieManager.getInstance().setAcceptCookie(true)

                val mainHandler = Handler(Looper.getMainLooper())
                val timeoutRunnable = Runnable {
                    if (!settled) {
                        settled = true
                        webView.destroy()
                        cont.resume(LoginResult.Failure("로그인 시간 초과 (30초). 다시 시도해 주세요."))
                    }
                }
                mainHandler.postDelayed(timeoutRunnable, 30_000)

                cont.invokeOnCancellation {
                    mainHandler.removeCallbacks(timeoutRunnable)
                    Handler(Looper.getMainLooper()).post { webView.destroy() }
                }

                fun settle(result: LoginResult) {
                    if (settled) return
                    settled = true
                    mainHandler.removeCallbacks(timeoutRunnable)
                    webView.destroy()
                    cont.resume(result)
                }

                fun onLoginResponse(raw: String) {
                    if (raw.startsWith("neterr:")) {
                        AppLog.w(TAG, "Login request network error: ${raw.drop(7)}")
                        settle(LoginResult.Failure("로그인 요청 실패 (네트워크 연결을 확인해주세요)"))
                        return
                    }
                    val status = runCatching {
                        JSONObject(raw.removePrefix("ok:")).optString("status")
                    }.getOrNull()
                    when (status) {
                        "200" -> {
                            val cookies = CookieManager.getInstance().getCookie(LmsApi.BASE_URL) ?: ""
                            AppLog.d(TAG, "Login success, cookies: $cookies")
                            cookieStore.rawCookies = cookies
                            settle(LoginResult.Success)
                        }
                        "102" -> settle(LoginResult.Failure("로그인 실패 (계정 상태를 확인해주세요)"))
                        null -> {
                            AppLog.w(TAG, "Unparseable login response: ${raw.take(200)}")
                            settle(LoginResult.Failure("로그인 실패 (서버가 예상치 못한 응답을 반환했습니다)"))
                        }
                        else -> settle(LoginResult.Failure("로그인 실패 (아이디 또는 비밀번호를 확인해주세요)"))
                    }
                }

                // AJAX 응답(window.__lms_login)을 300ms 간격으로 폴링
                fun pollLoginResult(view: WebView, attempt: Int) {
                    mainHandler.postDelayed({
                        if (settled) return@postDelayed
                        view.evaluateJavascript("window.__lms_login || 'pending'") { r ->
                            val result = decodeJsString(r)
                            if (result != "pending") onLoginResponse(result)
                            else if (attempt < 40) pollLoginResult(view, attempt + 1)
                            else settle(LoginResult.Failure("로그인 응답 시간 초과 (네트워크를 확인해주세요)"))
                        }
                    }, 300)
                }

                fun injectCredentials(view: WebView, attempt: Int) {
                    mainHandler.postDelayed({
                        if (settled) return@postDelayed
                        view.evaluateJavascript(buildInjectScript(id, pwd)) { r ->
                            val result = decodeJsString(r)
                            AppLog.d(TAG, "JS inject result: $result")
                            when {
                                result == "submitted" -> pollLoginResult(view, 0)
                                attempt < 5 -> injectCredentials(view, attempt + 1)
                                else -> settle(LoginResult.Failure("로그인 폼을 찾지 못했습니다 (페이지 구조 변경 가능성)"))
                            }
                        }
                    }, 800)
                }

                webView.webViewClient = object : WebViewClient() {
                    private var injected = false

                    override fun onPageFinished(view: WebView, url: String) {
                        AppLog.d(TAG, "onPageFinished: $url")
                        if (settled || injected || !url.contains("/login", ignoreCase = true)) return
                        injected = true
                        injectCredentials(view, 0)
                    }
                }

                // 이전 계정의 세션 쿠키가 남아 있으면 그 세션으로 로그인되므로 비우고 시작
                CookieManager.getInstance().removeAllCookies {
                    if (!settled) webView.loadUrl(LmsApi.LOGIN_URL)
                }
            }
        }

    /** evaluateJavascript 결과(JSON 리터럴)를 문자열로 디코딩 */
    private fun decodeJsString(r: String?): String =
        runCatching { JSONArray("[$r]").optString(0, "") }.getOrDefault("")

    private fun buildInjectScript(id: String, pwd: String): String {
        val safeId  = id.replace("\\", "\\\\").replace("'", "\\'")
        val safePwd = pwd.replace("\\", "\\\\").replace("'", "\\'")
        return """
        (function() {
            var idEl = document.querySelector('input[name="id"]')
                    || document.querySelector('input[name="username"]')
                    || document.querySelectorAll('input[type="text"]')[0];
            var pwEl = document.querySelector('input[name="pwd"]')
                    || document.querySelector('input[name="password"]')
                    || document.querySelector('input[type="password"]');
            if (!idEl || !pwEl) return 'fields_not_found';
            var ID = '$safeId';
            var PW = '$safePwd';
            idEl.value = ID;
            pwEl.value = PW;
            // 이 로그인 페이지는 AJAX(POST /ajax/login) 방식 — 직접 요청하고 JSON 응답의 status로 판정
            window.__lms_login = 'pending';
            fetch('/ajax/login', {
                method: 'POST',
                headers: {'Content-Type': 'application/x-www-form-urlencoded'},
                body: 'id=' + encodeURIComponent(ID) + '&pwd=' + encodeURIComponent(PW),
                credentials: 'same-origin'
            }).then(function(r) { return r.text(); })
              .then(function(t) { window.__lms_login = 'ok:' + t; })
              .catch(function(e) { window.__lms_login = 'neterr:' + e; });
            return 'submitted';
        })();
        """.trimIndent()
    }

    companion object {
        private const val TAG = "HybridLoginHelper"
    }
}
