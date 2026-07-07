package com.cnumed.mlms.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.button.MaterialButton
import com.cnumed.mlms.R

/**
 * [NextExamWidget] / [SemesterEndWidget] 공용 설정 화면.
 * 설정은 배치된 위젯 인스턴스마다 `countdown_{appWidgetId}_*` 키로 저장한다.
 */
class CountdownWidgetConfigActivity : AppCompatActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContentView(R.layout.activity_countdown_widget_config)

        val prefs = getSharedPreferences(CountdownWidget.PREFS_WIDGET, Context.MODE_PRIVATE)
        val kAlpha = "countdown_${appWidgetId}_bg_alpha"
        val kDark  = "countdown_${appWidgetId}_dark"
        val kText  = "countdown_${appWidgetId}_text_size"

        val savedAlpha = prefs.getInt(kAlpha, 235)
        val savedDark = prefs.getBoolean(kDark, false)
        val savedText = prefs.getInt(kText, 100)

        val seekbarAlpha = findViewById<SeekBar>(R.id.seekbar_alpha)
        val tvAlphaValue = findViewById<TextView>(R.id.tv_alpha_value)
        val seekbarText  = findViewById<SeekBar>(R.id.seekbar_text_size)
        val tvTextValue  = findViewById<TextView>(R.id.tv_text_size_value)
        val switchDark   = findViewById<SwitchCompat>(R.id.switch_dark_mode)
        val btnApply     = findViewById<MaterialButton>(R.id.btn_apply)

        seekbarAlpha.max = 255
        seekbarAlpha.progress = savedAlpha
        tvAlphaValue.text = "${savedAlpha * 100 / 255}%"

        seekbarText.max = 100
        seekbarText.progress = savedText - 50
        tvTextValue.text = "$savedText%"

        switchDark.isChecked = savedDark

        seekbarAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                tvAlphaValue.text = "${progress * 100 / 255}%"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        seekbarText.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                tvTextValue.text = "${progress + 50}%"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        btnApply.setOnClickListener {
            prefs.edit()
                .putInt(kAlpha, seekbarAlpha.progress)
                .putBoolean(kDark, switchDark.isChecked)
                .putInt(kText, seekbarText.progress + 50)
                .apply()

            val awm = AppWidgetManager.getInstance(this)
            CountdownWidget.updateWidget(this, awm, appWidgetId)

            val resultIntent = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            setResult(RESULT_OK, resultIntent)
            finish()
        }
    }
}
