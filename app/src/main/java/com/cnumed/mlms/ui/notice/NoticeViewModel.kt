package com.cnumed.mlms.ui.notice

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cnumed.mlms.data.repository.NoticeRepository
import com.cnumed.mlms.domain.model.Notice
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NoticeUiState(
    val notices: List<Notice> = emptyList(),
    val isLoading: Boolean = false,
    val isOffline: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class NoticeViewModel @Inject constructor(
    private val repository: NoticeRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(NoticeUiState())
    val uiState: StateFlow<NoticeUiState> = _uiState.asStateFlow()

    // 세션 내 캐시: 앱 실행 중 한번만 로드
    private var hasFetched = false

    init {
        observeLocal()
        fetchNotices()
    }

    private fun observeLocal() {
        viewModelScope.launch {
            repository.getAllNotices().collect { notices ->
                _uiState.value = _uiState.value.copy(notices = notices)
            }
        }
    }

    fun fetchNotices() {
        // 이미 fetch한 경우 스킵 (명시적 새로고침은 제외)
        if (hasFetched) return

        viewModelScope.launch {
            Log.d(TAG, "Fetching notices...")
            _uiState.value = _uiState.value.copy(isLoading = true)
            val result = repository.fetchNotices()

            // 성공 시에만 캐시 플래그 설정
            if (result.isSuccess) {
                hasFetched = true
                Log.d(TAG, "Notices fetched: ${result.getOrDefault(emptyList()).size}")
            } else {
                Log.w(TAG, "Notices fetch failed: ${result.exceptionOrNull()?.message}")
            }

            _uiState.value = _uiState.value.copy(
                isLoading = false,
                isOffline = result.isFailure,
                error = result.exceptionOrNull()?.message
            )
        }
    }

    // SwipeRefresh 등 명시적 새로고침용 메서드 추가
    fun forceRefresh() {
        hasFetched = false
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val result = repository.fetchNotices()

            if (result.isSuccess) {
                hasFetched = true
            }

            _uiState.value = _uiState.value.copy(
                isLoading = false,
                isOffline = result.isFailure,
                error = result.exceptionOrNull()?.message
            )
        }
    }

    fun markAsRead(id: Long) {
        viewModelScope.launch { repository.markAsRead(id) }
    }

    companion object {
        private const val TAG = "NoticeViewModel"
    }
}