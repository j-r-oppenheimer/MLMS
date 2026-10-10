package com.cnumed.mlms.data.remote

import android.webkit.CookieManager
import com.cnumed.mlms.util.AppLog
import com.cnumed.mlms.util.SecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

sealed class LoginResult {
    object Success : LoginResult()
    data class Failure(val message: String) : LoginResult()
}

@Singleton
class SessionManager @Inject constructor(
    private val api: LmsApi,
    private val securePrefs: SecurePrefs,
    private val cookieStore: SessionCookieStore,
    private val hybridLoginHelper: HybridLoginHelper
) {
    @Volatile
    var isLoggedIn: Boolean = false
        private set

    // 앱 시작 시 여러 화면·워커가 동시에 세션을 확인하므로 로그인은 한 번에 하나만
    private val loginMutex = Mutex()

    /** 백그라운드 WebView로 로그인 → 성공 시 SessionCookieStore에 쿠키 저장 */
    suspend fun login(id: String, pwd: String): LoginResult = loginMutex.withLock { doLogin(id, pwd) }

    private suspend fun doLogin(id: String, pwd: String): LoginResult {
        val result = hybridLoginHelper.login(id, pwd)
        if (result is LoginResult.Success) isLoggedIn = true
        return result
    }

    /** 최종 리다이렉트 URL로 세션 유효 여부 판정 */
    suspend fun isSessionValid(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val (finalUrl, body) = api.getWithFinalUrl(LmsApi.MAIN_URL)
            val valid = !finalUrl.contains("/login", ignoreCase = true)
            isLoggedIn = valid
            AppLog.d(TAG, "Session valid=$valid | finalUrl=$finalUrl | bodyLen=${body.length}")
            valid
        } catch (e: Exception) {
            AppLog.e(TAG, "Session check failed", e)
            false
        }
    }

    /** 세션 만료 시 저장된 자격증명으로 자동 재로그인. 저장된 자격증명이 없으면 null */
    suspend fun restoreSession(): LoginResult? = loginMutex.withLock {
        if (isSessionValid()) return@withLock LoginResult.Success
        val id  = securePrefs.getId()      ?: return@withLock null
        val pwd = securePrefs.getPassword() ?: return@withLock null
        AppLog.d(TAG, "Session expired — auto re-login")
        doLogin(id, pwd)
    }

    suspend fun ensureSession(): Boolean = restoreSession() is LoginResult.Success

    fun logout() {
        cookieStore.rawCookies = ""
        CookieManager.getInstance().removeAllCookies(null)
        isLoggedIn = false
    }

    companion object {
        private const val TAG = "SessionManager"
    }
}
