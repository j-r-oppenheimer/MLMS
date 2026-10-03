package com.cnumed.mlms.di

import android.content.Context
import androidx.room.Room
import com.cnumed.mlms.BuildConfig
import com.cnumed.mlms.data.local.AppDatabase
import com.cnumed.mlms.data.local.dao.ClassDao
import com.cnumed.mlms.data.local.dao.NoticeDao
import com.cnumed.mlms.data.remote.SessionCookieStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(cookieStore: SessionCookieStore): OkHttpClient {
        // OkHttp 로거는 android.util.Log가 아니라 Platform.log를 타므로
        // proguard의 -assumenosideeffects로는 안 지워진다. 빌드 타입으로 직접 끈다.
        // (HEADERS 레벨은 세션 쿠키가 그대로 찍히므로 release에서 절대 켜면 안 된다.)
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.HEADERS
                    else HttpLoggingInterceptor.Level.NONE
        }
        return OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // WebView 쿠키를 Cookie 헤더로 직접 주입 (JavaNetCookieJar 대신)
            .addInterceptor { chain ->
                val cookies = cookieStore.rawCookies
                val request = if (cookies.isNotEmpty()) {
                    chain.request().newBuilder()
                        .header("Cookie", cookies)
                        .build()
                } else {
                    chain.request()
                }
                chain.proceed(request)
            }
            .addInterceptor(logging)
            .build()
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "mlms_database")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideClassDao(db: AppDatabase): ClassDao = db.classDao()

    @Provides
    fun provideNoticeDao(db: AppDatabase): NoticeDao = db.noticeDao()
}
