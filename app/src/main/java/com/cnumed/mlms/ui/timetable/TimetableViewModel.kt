package com.cnumed.mlms.ui.timetable

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cnumed.mlms.data.repository.TimetableRepository
import com.cnumed.mlms.domain.model.ClassItem
import com.cnumed.mlms.domain.model.LessonDetail
import com.cnumed.mlms.util.DateUtils
import com.cnumed.mlms.widget.TimetableWidget
import com.cnumed.mlms.widget.TimetableWidgetUpdateWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class TimetableUiState(
    val classes: List<ClassItem> = emptyList(),
    val weekStart: LocalDate = DateUtils.getWeekStart(),
    val isLoading: Boolean = false,
    val isOffline: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class TimetableViewModel @Inject constructor(
    private val repository: TimetableRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(TimetableUiState())
    val uiState: StateFlow<TimetableUiState> = _uiState.asStateFlow()

    private var observeJob: Job? = null

    // 세션 내 캐시: 한번 로드된 주는 앱이 살아있는 동안 재로드하지 않음
    private val sessionCache = mutableMapOf<LocalDate, Boolean>()

    init {
        loadWeek(DateUtils.getWeekStart())
    }

    private fun filterClasses(classes: List<ClassItem>): List<ClassItem> {
        val prefs = context.getSharedPreferences("class_settings", Context.MODE_PRIVATE)
        val selectedClass = prefs.getString("selected_class", "ALL") ?: "ALL"

        if (selectedClass == "ALL") return classes

        return classes.filter { cls ->
            val title = cls.title.uppercase()
            when {
                "AB" in title || "AB 반" in title || "A/B" in title -> true
                "A반" in title || "A 반" in title -> selectedClass == "A"
                "B반" in title || "B 반" in title -> selectedClass == "B"
                else -> true
            }
        }
    }

    fun loadWeek(weekStart: LocalDate) {
        // 세션 캐시에 없을 때만 로딩 표시
        val needsFetch = !sessionCache.containsKey(weekStart)
        Log.d(TAG, "loadWeek($weekStart) needsFetch=$needsFetch")
        _uiState.value = _uiState.value.copy(
            weekStart = weekStart,
            isLoading = needsFetch,
            error = null
        )

        // 로컬 DB 실시간 관찰 (여기서 필터 적용)
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            repository.getClassesForWeek(weekStart).collect { classes ->
                _uiState.value = _uiState.value.copy(classes = filterClasses(classes))
            }
        }

        // 네트워크 패치: 세션 캐시에 없을 때만 실행
        if (needsFetch) {
            viewModelScope.launch {
                val result = repository.fetchWeek(weekStart)

                if (result.isSuccess) {
                    val newClasses = result.getOrDefault(emptyList())
                    Log.d(TAG, "fetchWeek result: ${newClasses.size} classes")
                    if (newClasses.isNotEmpty()) {
                        // 실제 수업이 있을 때만 캐시 등록 + 위젯 갱신
                        // 빈 결과는 네트워크 불안정으로 인한 오인식일 수 있으므로 재시도 허용
                        sessionCache[weekStart] = true
                        syncWidget(weekStart, newClasses)
                    }
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isOffline = result.isFailure,
                    error = result.exceptionOrNull()?.message
                )
            }
        }
    }

    private fun syncWidget(weekStart: LocalDate, classes: List<ClassItem>) {
        TimetableWidgetUpdateWorker.saveClassesToCache(context, weekStart, classes)
        val awm = AppWidgetManager.getInstance(context)
        val ids = awm.getAppWidgetIds(ComponentName(context, TimetableWidget::class.java))
        for (id in ids) TimetableWidget.updateWidget(context, awm, id)
    }

    /** 설정(반 선택 등) 변경 후 재방문 시 DB 재조회 없이 필터만 재적용 */
    fun refilter() {
        viewModelScope.launch {
            val classes = repository.getClassesForWeek(_uiState.value.weekStart).first()
            _uiState.value = _uiState.value.copy(classes = filterClasses(classes))
        }
    }

    suspend fun fetchLessonDetail(lpSeq: Int, currSeq: Int, acaSeq: Int): Result<LessonDetail> {
        return repository.fetchLessonDetail(lpSeq, currSeq, acaSeq)
    }

    suspend fun downloadFile(file: com.cnumed.mlms.domain.model.LessonFile): Result<Long> = repository.downloadFile(file)

    fun nextWeek() = loadWeek(_uiState.value.weekStart.plusWeeks(1))
    fun previousWeek() = loadWeek(_uiState.value.weekStart.minusWeeks(1))

    // refresh()는 명시적 새로고침이므로 캐시 무시
    fun refresh() {
        sessionCache.remove(_uiState.value.weekStart)
        loadWeek(_uiState.value.weekStart)
    }

    companion object {
        private const val TAG = "TimetableViewModel"
    }
}