# Jsoup
-keep public class org.jsoup.** { public *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.**

# Hilt
-keepnames @dagger.hilt.android.lifecycle.HiltViewModel class * extends androidx.lifecycle.ViewModel

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}

# ──────────────────────────────────────────────
# 로그 제거 (release 전용)
# ──────────────────────────────────────────────
# R8이 "부작용 없음"으로 간주해 호출을 지우고, 인자로 만들던 문자열 연결도 함께 죽는다.
# Log.e는 남긴다 — 실기기 크래시 원인 추적용.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static boolean isLoggable(...);
}

# ──────────────────────────────────────────────
# 난독화·축소 대비 keep 규칙
# ──────────────────────────────────────────────
# WorkManager: 워커 클래스는 이름으로 인스턴스화된다(HiltWorkerFactory 포함)
-keep class * extends androidx.work.ListenableWorker { <init>(...); }

# 위젯 프로바이더/서비스는 매니페스트 참조라 AGP가 keep 규칙을 자동 생성하지만 명시해 둔다
-keep class * extends android.appwidget.AppWidgetProvider
-keep class * extends android.widget.RemoteViewsService

# Room 엔티티: 생성 코드가 필드에 접근
-keep class com.cnumed.mlms.data.local.entity.** { *; }

# Tink (EncryptedSharedPreferences) — 리플렉션으로 키 매니저를 등록
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# JSpecify 애노테이션은 컴파일 타임 전용 — 런타임에 없어도 된다(Tink가 참조)
-dontwarn org.jspecify.annotations.**
