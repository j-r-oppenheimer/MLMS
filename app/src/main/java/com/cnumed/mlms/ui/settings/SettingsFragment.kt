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
import android.text.Editable
import android.text.TextWatcher
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.cnumed.mlms.convert.ConvertPrefs
import com.cnumed.mlms.convert.DownloadMeta
import com.cnumed.mlms.convert.FileNameFormatter
import com.cnumed.mlms.convert.PptxFeature
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import androidx.fragment.app.Fragment
import com.cnumed.mlms.R
import com.cnumed.mlms.data.remote.SessionManager
import com.cnumed.mlms.databinding.FragmentSettingsBinding
import com.cnumed.mlms.ui.login.LoginActivity
import com.cnumed.mlms.util.SecurePrefs
import com.cnumed.mlms.util.ThemeColorManager
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

    /** 변환용 글꼴 파일 선택 (여러 개) */
    private val pickFonts = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val added = ArrayList<String>(); val errors = ArrayList<String>()
            withContext(Dispatchers.IO) {
                for (u in uris) {
                    try { added += PptxFeature.importFont(ctx, u) } catch (e: Exception) { errors += (e.message ?: "읽을 수 없는 파일") }
                }
            }
            val msg = buildString {
                if (added.isNotEmpty()) append("글꼴 추가: ${added.joinToString(", ")}")
                if (errors.isNotEmpty()) { if (isNotEmpty()) append("\n"); append(errors.distinct().joinToString("\n")) }
            }
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            refreshFontList()
        }
    }

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

        applyThemeColor()

        binding.switchAutoLogin.isChecked = securePrefs.isAutoLoginEnabled()
        binding.switchAutoLogin.setOnCheckedChangeListener { _, checked ->
            securePrefs.setAutoLogin(checked)
            if (!checked) securePrefs.clearCredentials()
        }

        val classPrefs = requireContext().getSharedPreferences("class_settings", Context.MODE_PRIVATE)

        binding.switchExamHighlight.isChecked = classPrefs.getBoolean("exam_highlight", true)
        binding.switchExamHighlight.setOnCheckedChangeListener { _, checked ->
            classPrefs.edit().putBoolean("exam_highlight", checked).apply()
        }

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
                onThemeColorChanged()
                updateWidget()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "올바른 색상 코드를 입력해주세요. (예: #7787A1)", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnPickColor.setOnClickListener {
            val cur = try {
                val t = binding.etColorHex.text.toString().trim().let { if (it.startsWith("#")) it else "#$it" }
                Color.parseColor(t)
            } catch (e: Exception) { Color.parseColor("#7787A1") }

            com.cnumed.mlms.ui.settings.color.ColorPickerDialog.show(requireContext(), cur) { picked ->
                val hex = String.format("#%06X", 0xFFFFFF and picked)
                binding.etColorHex.setText(hex)
                classPrefs.edit().putString("block_color", hex).apply()
                Toast.makeText(requireContext(), "색상이 적용되었습니다.", Toast.LENGTH_SHORT).show()
                onThemeColorChanged()
                updateWidget()
            }
        }

        setupConvertSettings()
        setupNameSettings()

        binding.tvAppVersion.text = "MLMS v${com.cnumed.mlms.BuildConfig.VERSION_NAME} — 충남대 의대 학습관리시스템"

        binding.btnLogout.setOnClickListener {
            securePrefs.clearCredentials()
            sessionManager.logout()
            startActivity(Intent(requireContext(), LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
        }
    }

    // ───────── PPT → PDF 변환 ─────────
    private fun setupConvertSettings() {
        val ctx = requireContext()
        // 라이트 버전에는 변환 기능이 없다
        if (!PptxFeature.AVAILABLE) { binding.layoutPptxSection.visibility = View.GONE; return }
        val prefs = ConvertPrefs.prefs(ctx)
        binding.switchPptxPdf.isChecked = ConvertPrefs.pptxToPdf(ctx)
        binding.switchPptxOcr.isChecked = ConvertPrefs.ocr(ctx)
        binding.switchFetchFonts.isChecked = ConvertPrefs.fetchFonts(ctx)
        fun syncEnabled() {
            binding.layoutPptxOptions.visibility = if (binding.switchPptxPdf.isChecked) View.VISIBLE else View.GONE
        }
        syncEnabled()
        binding.switchPptxPdf.setOnCheckedChangeListener { _, c ->
            prefs.edit().putBoolean(ConvertPrefs.KEY_PPTX_PDF, c).apply(); syncEnabled()
        }
        binding.switchPptxOcr.setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean(ConvertPrefs.KEY_OCR, c).apply() }
        binding.switchFetchFonts.setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean(ConvertPrefs.KEY_FETCH_FONTS, c).apply() }
        binding.btnAddFont.setOnClickListener {
            pickFonts.launch(arrayOf("font/ttf", "font/collection", "font/otf", "font/sfnt", "application/x-font-ttf",
                "application/x-font-truetype", "application/font-sfnt", "application/vnd.ms-opentype", "application/octet-stream"))
        }
        binding.btnClearFonts.setOnClickListener {
            MaterialAlertDialogBuilder(ctx)
                .setTitle("추가 글꼴 지우기")
                .setMessage("직접 추가한 글꼴과 자동으로 받은 글꼴을 모두 지울까요?")
                .setPositiveButton("지우기") { _, _ ->
                    PptxFeature.clearFonts(ctx); refreshFontList()
                }
                .setNegativeButton("취소", null)
                .show()
        }
        refreshFontList()
    }

    private fun refreshFontList() {
        val b = _binding ?: return
        val ctx = requireContext()
        val mine = PptxFeature.importedFamilies(ctx)
        val google = PptxFeature.googleFamilies(ctx)
        b.tvUserFonts.text = buildString {
            append("추가한 글꼴: ").append(if (mine.isEmpty()) "없음" else mine.joinToString(", "))
            if (google.isNotEmpty()) append("\n자동으로 받은 글꼴: ").append(google.joinToString(", "))
        }
    }

    // ───────── 다운로드 파일 이름 ─────────
    private fun setupNameSettings() {
        val ctx = requireContext()
        // 라이트 버전은 다운로드 부가 기능(파일 이름 자동 지정)도 넣지 않는다
        if (!PptxFeature.AVAILABLE) { binding.layoutNameSection.visibility = View.GONE; return }
        val prefs = ConvertPrefs.prefs(ctx)
        binding.switchNameTemplate.isChecked = ConvertPrefs.nameEnabled(ctx)
        binding.etNameTemplate.setText(ConvertPrefs.nameTemplate(ctx))
        fun syncEnabled() {
            binding.layoutNameOptions.visibility = if (binding.switchNameTemplate.isChecked) View.VISIBLE else View.GONE
        }
        binding.switchNameTemplate.setOnCheckedChangeListener { _, c ->
            prefs.edit().putBoolean(ConvertPrefs.KEY_NAME_ENABLED, c).apply(); syncEnabled()
        }

        // 항목 버튼: 커서 위치에 삽입
        val color = ThemeColorManager.getColor(ctx)
        FileNameFormatter.TOKENS.forEach { (label, token) ->
            binding.chipsNameTokens.addView(Chip(ctx).apply {
                text = label
                isCheckable = false
                chipStrokeWidth = 1f * resources.displayMetrics.density
                chipStrokeColor = android.content.res.ColorStateList.valueOf(color)
                setTextColor(color)
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
                setOnClickListener {
                    val et = binding.etNameTemplate
                    val st = minOf(et.selectionStart, et.selectionEnd).coerceAtLeast(0)
                    val en = maxOf(et.selectionStart, et.selectionEnd).coerceAtLeast(0)
                    et.text.replace(st, en, token)
                    et.setSelection(if (token == "()") st + 1 else st + token.length)
                    et.requestFocus()
                }
            })
        }

        // 날짜 형식
        val fmts = FileNameFormatter.DATE_FORMATS
        val labels = fmts.map { "${FileNameFormatter.dateExample(it)}  ($it)" }
        binding.spinnerDateFormat.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, labels)
        binding.spinnerDateFormat.setSelection(fmts.indexOf(ConvertPrefs.dateFormat(ctx)).coerceAtLeast(0))
        binding.spinnerDateFormat.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.edit().putString(ConvertPrefs.KEY_NAME_DATE_FMT, fmts[pos]).apply(); updateNamePreview()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        binding.etNameTemplate.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                prefs.edit().putString(ConvertPrefs.KEY_NAME_TEMPLATE, s?.toString() ?: "").apply(); updateNamePreview()
            }
        })
        binding.btnNameReset.setOnClickListener {
            binding.etNameTemplate.setText(FileNameFormatter.DEFAULT_TEMPLATE)
            binding.spinnerDateFormat.setSelection(0)
        }
        syncEnabled()
        updateNamePreview()
    }

    private fun updateNamePreview() {
        val b = _binding ?: return
        val ctx = requireContext()
        val meta = FileNameFormatter.metaFor(LocalDate.of(2026, 4, 18), "파인만의 물리학 강의", "1강 (리처드 파인만)", "")
        val name = FileNameFormatter.format(b.etNameTemplate.text.toString(), ConvertPrefs.dateFormat(ctx), meta, "1강")
        b.tvNamePreview.text = "미리보기: $name.pdf"
    }

    /** 설정 화면의 컨트롤들을 현재 테마 색으로 틴팅한다. */
    private fun applyThemeColor() {
        val color = ThemeColorManager.getColor(requireContext())
        ThemeColorManager.tintOutlinedButton(binding.btnAlarmTime, color)
        ThemeColorManager.tintOutlinedButton(binding.btnPickColor, color)
        ThemeColorManager.tintFilledButton(binding.btnApplyColor, color)
        ThemeColorManager.tintOutlinedButton(binding.btnLogout, color)
        ThemeColorManager.tintOutlinedButton(binding.btnAddFont, color)
        ThemeColorManager.tintOutlinedButton(binding.btnClearFonts, color)
        ThemeColorManager.tintOutlinedButton(binding.btnNameReset, color)
        ThemeColorManager.tintSwitch(binding.switchPptxPdf, color)
        ThemeColorManager.tintSwitch(binding.switchPptxOcr, color)
        ThemeColorManager.tintSwitch(binding.switchFetchFonts, color)
        ThemeColorManager.tintSwitch(binding.switchNameTemplate, color)
        ThemeColorManager.tintSwitch(binding.switchExamHighlight, color)
        ThemeColorManager.tintSwitch(binding.switchAutoLogin, color)
        ThemeColorManager.tintCompoundButton(binding.radioClassAll, color)
        ThemeColorManager.tintCompoundButton(binding.radioClassA, color)
        ThemeColorManager.tintCompoundButton(binding.radioClassB, color)
    }

    /** 테마 색이 바뀐 뒤 화면 전체(설정 컨트롤 + 상태바 + 하단 네비)를 갱신한다. */
    private fun onThemeColorChanged() {
        applyThemeColor()
        (activity as? com.cnumed.mlms.ui.main.MainActivity)?.applyThemeColors()
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
        com.cnumed.mlms.widget.CountdownWidget.refreshAll(context)
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