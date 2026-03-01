package com.cnumed.mlms.data.remote

import javax.inject.Inject
import javax.inject.Singleton

/**
 * WebView 로그인 후 얻은 쿠키 문자열을 보관.
 * OkHttp 인터셉터가 이 값을 읽어 Cookie 헤더로 주입함.
 */
@Singleton
class SessionCookieStore @Inject constructor() {
    @Volatile
    var rawCookies: String = ""
}
