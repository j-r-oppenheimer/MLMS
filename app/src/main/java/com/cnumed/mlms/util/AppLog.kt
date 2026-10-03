package com.cnumed.mlms.util

import android.util.Log
import com.cnumed.mlms.BuildConfig

/**
 * 앱 전역 로그 래퍼. 디버그 빌드에서만 찍는다.
 *
 * release에서는 [BuildConfig.DEBUG]가 false로 상수 폴딩되어 본문이 비고,
 * R8이 그 호출과 인자로 만들던 문자열 연결까지 함께 제거한다.
 * (proguard-rules.pro의 -assumenosideeffects android.util.Log 규칙과 이중 안전장치)
 *
 * 에러는 release에서도 남긴다 — 실기기 문제 추적용.
 */
object AppLog {

    // release 빌드에서도 로그를 봐야 할 때만 임시로 true.
    // (true인 동안에는 release APK의 logcat에도 앱 내부 정보가 그대로 찍히므로 배포 전 반드시 false)
    // 참고: minify가 켜져 있으면 -assumenosideeffects가 Log 호출을 지우므로
    //       이 값을 true로 바꿔도 로그는 안 나온다 — 진단할 땐 isMinifyEnabled도 같이 꺼야 한다.
    private const val FORCE_LOG = false

    private val enabled: Boolean get() = BuildConfig.DEBUG || FORCE_LOG

    fun v(tag: String, msg: String) {
        if (enabled) Log.v(tag, msg)
    }

    fun d(tag: String, msg: String) {
        if (enabled) Log.d(tag, msg)
    }

    fun i(tag: String, msg: String) {
        if (enabled) Log.i(tag, msg)
    }

    fun w(tag: String, msg: String, t: Throwable? = null) {
        if (enabled) {
            if (t != null) Log.w(tag, msg, t) else Log.w(tag, msg)
        }
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        if (t != null) Log.e(tag, msg, t) else Log.e(tag, msg)
    }
}
