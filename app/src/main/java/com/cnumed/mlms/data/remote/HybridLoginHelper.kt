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
 * 백그라운드(숨겨진) WebView로 로그인.
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

                webView.webViewClient = object : WebViewClient() {
                    private var injected = false

                    override fun onPageFinished(view: WebView, url: String) {
                        Log.d(TAG, "onPageFinished: $url")
                        when {
                            // 로그인 페이지 → JS로 자격증명 주입
                            url.contains("/login", ignoreCase = true) && !injected -> {
                                injected = true
                                mainHandler.postDelayed({
                                    view.evaluateJavascript(buildInjectScript(id, pwd)) { r ->
                                        Log.d(TAG, "JS inject result: $r")
                                    }
                                }, 800)
                            }
                            // 로그인 성공: cnu.u-lms.com 이면서 /login 이 아닌 페이지
                            !url.contains("/login", ignoreCase = true) &&
                            url.startsWith("https://cnu.u-lms.com") &&
                            !settled -> {
                                settled = true
                                mainHandler.removeCallbacks(timeoutRunnable)

                                // WebView 쿠키 → SessionCookieStore 저장
                                val cookies = CookieManager.getInstance()
                                    .getCookie("https://cnu.u-lms.com") ?: ""
                                Log.d(TAG, "Login success, cookies: $cookies")
                                cookieStore.rawCookies = cookies

                                webView.destroy()
                                cont.resume(LoginResult.Success)
                            }
                        }
                    }
                }

                webView.loadUrl(LmsApi.LOGIN_URL)
            }
        }

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
            idEl.value = '$safeId';
            pwEl.value = '$safePwd';
            ['input','change'].forEach(function(ev) {
                idEl.dispatchEvent(new Event(ev, {bubbles:true}));
                pwEl.dispatchEvent(new Event(ev, {bubbles:true}));
            });
            var btn = document.querySelector('button[type="submit"]')
                   || document.querySelector('input[type="submit"]')
                   || document.querySelector('button.btn-login')
                   || document.querySelector('a.btn-login')
                   || document.querySelector('button');
            if (btn) { btn.click(); return 'clicked: ' + (btn.textContent || btn.value).trim(); }
            var form = document.querySelector('form');
            if (form) { form.submit(); return 'form_submitted'; }
            return 'no_submit_element';
        })();
        """.trimIndent()
    }

    companion object {
        private const val TAG = "HybridLoginHelper"
    }
}
