package com.cnumed.mlms.data.remote

import android.util.Log
import com.cnumed.mlms.util.SecurePrefs
import kotlinx.coroutines.Dispatchers
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

    /** 백그라운드 WebView로 로그인 → 성공 시 SessionCookieStore에 쿠키 저장 */
    suspend fun login(id: String, pwd: String): LoginResult {
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
            Log.d(TAG, "Session valid=$valid | finalUrl=$finalUrl | bodyLen=${body.length}")
            valid
        } catch (e: Exception) {
            Log.e(TAG, "Session check failed", e)
            false
        }
    }

    /** 세션 만료 시 저장된 자격증명으로 자동 재로그인 */
    suspend fun ensureSession(): Boolean {
        if (isSessionValid()) return true
        val id  = securePrefs.getId()      ?: return false
        val pwd = securePrefs.getPassword() ?: return false
        Log.d(TAG, "Session expired — auto re-login")
        return login(id, pwd) is LoginResult.Success
    }

    fun logout() {
        cookieStore.rawCookies = ""
        isLoggedIn = false
    }

    companion object {
        private const val TAG = "SessionManager"
    }
}
