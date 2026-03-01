package com.cnumed.mlms.ui.settings

import android.app.TimePickerDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.cnumed.mlms.R
import com.cnumed.mlms.data.remote.SessionManager
import com.cnumed.mlms.databinding.FragmentSettingsBinding
import com.cnumed.mlms.ui.login.LoginActivity
import com.cnumed.mlms.util.SecurePrefs
import com.cnumed.mlms.widget.TimetableWidget
import com.cnumed.mlms.worker.DailyAlarmScheduler
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    @Inject lateinit var securePrefs: SecurePrefs
    @Inject lateinit var sessionManager: SessionManager

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.switchAutoLogin.isChecked = securePrefs.isAutoLoginEnabled()
        binding.switchAutoLogin.setOnCheckedChangeListener { _, checked ->
            securePrefs.setAutoLogin(checked)
            if (!checked) securePrefs.clearCredentials()
        }

        val classPrefs = requireContext().getSharedPreferences("class_settings", Context.MODE_PRIVATE)

        // 알림 시간 설정
        val alarmHour   = classPrefs.getInt("alarm_hour", 8)
        val alarmMinute = classPrefs.getInt("alarm_minute", 0)
        binding.btnAlarmTime.text = formatAlarmTime(alarmHour, alarmMinute)
        binding.btnAlarmTime.setOnClickListener {
            val curHour   = classPrefs.getInt("alarm_hour", 8)
            val curMinute = classPrefs.getInt("alarm_minute", 0)
            TimePickerDialog(requireContext(), { _, h, m ->
                classPrefs.edit().putInt("alarm_hour", h).putInt("alarm_minute", m).apply()
                binding.btnAlarmTime.text = formatAlarmTime(h, m)
                DailyAlarmScheduler.schedule(requireContext())
                Toast.makeText(requireContext(), "${formatAlarmTime(h, m)}으로 설정되었습니다.", Toast.LENGTH_SHORT).show()
            }, curHour, curMinute, false).show()
        }

        // 반 선택 설정 불러오기 (기본값: ALL)
        val currentClass = classPrefs.getString("selected_class", "ALL") ?: "ALL"
        binding.radioGroupClass.check(
            when (currentClass) {
                "A"  -> R.id.radio_class_a
                "B"  -> R.id.radio_class_b
                else -> R.id.radio_class_all
            }
        )

        binding.radioGroupClass.setOnCheckedChangeListener { _, checkedId ->
            val selectedClass = when (checkedId) {
                R.id.radio_class_a -> "A"
                R.id.radio_class_b -> "B"
                else               -> "ALL"
            }
            classPrefs.edit().putString("selected_class", selectedClass).apply()
            updateWidget()
        }

        // 색상 설정 로드 및 적용
        val currentColor = classPrefs.getString("block_color", "#7787A1") ?: "#7787A1"
        binding.etColorHex.setText(currentColor)

        binding.btnApplyColor.setOnClickListener {
            var inputColor = binding.etColorHex.text.toString().trim()
            if (!inputColor.startsWith("#")) {
                inputColor = "#$inputColor"
            }
            try {
                // 올바른 HEX 값인지 파싱 테스트
                Color.parseColor(inputColor)
                classPrefs.edit().putString("block_color", inputColor).apply()
                Toast.makeText(requireContext(), "색상이 적용되었습니다.", Toast.LENGTH_SHORT).show()
                updateWidget()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "올바른 색상 코드를 입력해주세요. (예: #7787A1)", Toast.LENGTH_SHORT).show()
            }
        }

        binding.tvAppVersion.text = "MLMS v1.0 — 충남대 의대 학습관리시스템"

        binding.btnLogout.setOnClickListener {
            securePrefs.clearCredentials()
            sessionManager.logout()
            startActivity(Intent(requireContext(), LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
        }
    }

    private fun updateWidget() {
        val context = requireContext()
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val ids = appWidgetManager.getAppWidgetIds(
            ComponentName(context, TimetableWidget::class.java)
        )
        for (id in ids) {
            TimetableWidget.updateWidget(context, appWidgetManager, id)
        }
    }

    private fun formatAlarmTime(hour: Int, minute: Int): String {
        val ampm  = if (hour < 12) "오전" else "오후"
        val h12   = when {
            hour == 0  -> 12
            hour <= 12 -> hour
            else       -> hour - 12
        }
        return "%s %d:%02d".format(ampm, h12, minute)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}