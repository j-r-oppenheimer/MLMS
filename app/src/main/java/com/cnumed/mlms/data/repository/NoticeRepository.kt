package com.cnumed.mlms.data.repository

import com.cnumed.mlms.data.local.dao.NoticeDao
import com.cnumed.mlms.data.local.entity.NoticeEntity
import com.cnumed.mlms.data.remote.LmsApi
import com.cnumed.mlms.data.remote.LmsParser
import com.cnumed.mlms.data.remote.SessionManager
import com.cnumed.mlms.domain.model.Notice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NoticeRepository @Inject constructor(
    private val api: LmsApi,
    private val parser: LmsParser,
    private val noticeDao: NoticeDao,
    private val sessionManager: SessionManager
) {
    fun getAllNotices(): Flow<List<Notice>> =
        noticeDao.getAllNotices().map { list -> list.map { it.toDomain() } }

    suspend fun fetchNotices(): Result<List<Notice>> {
        return try {
            if (!sessionManager.ensureSession()) {
                return Result.failure(Exception("로그인이 필요합니다"))
            }
            // AJAX 엔드포인트로 직접 요청 (JS가 이 URL로 데이터를 가져옴)
            val ajaxHtml = api.getAjax(LmsApi.NOTICE_AJAX_URL, referer = LmsApi.NOTICE_URL)
            var notices = parser.parseNotices(ajaxHtml)
            android.util.Log.d("NoticeRepository", "AJAX: ${notices.size} notices")

            // 폴백: 페이지 HTML 직접 파싱 시도
            if (notices.isEmpty()) {
                android.util.Log.d("NoticeRepository", "AJAX empty, trying page HTML")
                val html = api.get(LmsApi.NOTICE_URL)
                notices = parser.parseNotices(html)
            }

            // 파싱 결과가 있을 때만 DB 갱신 (빈 결과면 캐시 유지)
            if (notices.isNotEmpty()) {
                // 기존 읽음 상태 보존 후 DB 교체
                val readIds = noticeDao.getReadNoticeIds().toSet()
                noticeDao.deleteAll()
                noticeDao.insertAll(notices.map { notice ->
                    NoticeEntity.fromDomain(notice).copy(isRead = notice.id in readIds)
                })
            }

            Result.success(notices)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 현재 DB에 저장된 공지 ID 집합 (새 공지 감지용) */
    suspend fun getNoticeIds(): Set<Long> = noticeDao.getAllNoticeIds().toSet()

    suspend fun markAsRead(id: Long) = noticeDao.markAsRead(id)
}
