package com.kai.oplusrecorder

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    // 色彩标准选项：显示文本 <-> MediaFormat color-standard 值
    private val colorStandardLabels = arrayOf("BT.709", "BT.601", "BT.2020")
    private val colorStandardValues = intArrayOf(1, 2, 6)

    // 色彩范围选项：显示文本 <-> MediaFormat color-range 值
    private val colorRangeLabels = arrayOf("Full（0-255）", "Limited（16-235）")
    private val colorRangeValues = intArrayOf(1, 2)

    private lateinit var videoEdit: EditText
    private lateinit var audioEdit: EditText
    private lateinit var colorEnabledCheck: CheckBox
    private lateinit var colorStandardSpinner: Spinner
    private lateinit var colorRangeSpinner: Spinner

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        setContentView(
            R.layout.activity_main
        )

        videoEdit = findViewById(R.id.videoBitrate)
        audioEdit = findViewById(R.id.audioBitrate)
        colorEnabledCheck = findViewById(R.id.colorEnabled)
        colorStandardSpinner = findViewById(R.id.colorStandard)
        colorRangeSpinner = findViewById(R.id.colorRange)

        // 初始化色彩下拉框
        colorStandardSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            colorStandardLabels
        )
        colorRangeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            colorRangeLabels
        )

        // 配置就绪后再回填 UI：RemoteSettings.prefs 由 XposedService 异步绑定后才有值，
        // 直接在 onCreate 读取会因未绑定拿到 null，导致只显示默认值（30Mbps/288kbps）。
        RemoteSettings.runWhenReady {
            loadSettings()
        }

        findViewById<Button>(
            R.id.saveButton
        ).setOnClickListener {
            saveSettings()
        }

        // 重启图标：强制停止目标录屏应用，使修改后的码率生效
        findViewById<ImageView>(
            R.id.iv_reset
        ).setOnClickListener {
            forceStopRecorder()
        }
    }

    /** 从 RemoteSettings 读取已保存的配置并回填到 UI。 */
    private fun loadSettings() {
        val prefs = RemoteSettings.prefs ?: return

        videoEdit.setText(
            (prefs.getInt("video_bitrate", 30_000_000) / 1_000_000.0).toString()
        )
        audioEdit.setText(
            prefs.getInt("audio_bitrate", 288_000).div(1000).toString()
        )
        colorEnabledCheck.isChecked = prefs.getBoolean("color_enabled", true)
        colorStandardSpinner.setSelection(
            indexOfValue(colorStandardValues, prefs.getInt("color_standard", 1))
        )
        colorRangeSpinner.setSelection(
            indexOfValue(colorRangeValues, prefs.getInt("color_range", 1))
        )
    }

    /** 校验并保存 UI 中的配置到 RemoteSettings。 */
    private fun saveSettings() {
        val videoMbps = videoEdit.text.toString().toDoubleOrNull()
        val audioKbps = audioEdit.text.toString().toIntOrNull()

        if (videoMbps == null || audioKbps == null) {
            Toast.makeText(this, "请输入有效数字", Toast.LENGTH_SHORT).show()
            return
        }

        val editor = RemoteSettings.prefs?.edit()
        if (editor == null) {
            Toast.makeText(this, "配置服务未就绪，请稍后重试", Toast.LENGTH_SHORT).show()
            return
        }

        val videoBps = (videoMbps * 1_000_000).toInt()
        val audioBps = audioKbps * 1000
        val standard = colorStandardValues[colorStandardSpinner.selectedItemPosition]
        val range = colorRangeValues[colorRangeSpinner.selectedItemPosition]

        editor.putInt("video_bitrate", videoBps)
            .putInt("audio_bitrate", audioBps)
            .putBoolean("color_enabled", colorEnabledCheck.isChecked)
            .putInt("color_standard", standard)
            .putInt("color_range", range)
            .apply()

        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
    }

    /** 根据值查找对应下拉框位置，找不到时回退到 0。 */
    private fun indexOfValue(values: IntArray, value: Int): Int {
        val i = values.indexOf(value)
        return if (i >= 0) i else 0
    }

    /**
     * 强制停止 OPlus 录屏（com.oplus.screenrecorder）。
     *
     * 目标应用的码率配置在进程启动时（HookModule.onPackageReady）一次性读取，
     * 因此修改码率后必须杀掉其进程，下次启动才会重新加载新配置。
     * 这里只做 force-stop，不负责重新拉起录屏 App。
     *
     * 依赖设备 Root 权限（LSPosed 环境已具备），通过 su 执行 am force-stop。
     */
    private fun forceStopRecorder() {
        Thread {
            var success = false
            var error: String? = null
            try {
                val proc = Runtime.getRuntime().exec(
                    arrayOf(
                        "su", "-c",
                        "am force-stop com.oplus.screenrecorder"
                    )
                )
                proc.waitFor()
                success = proc.exitValue() == 0
            } catch (e: Exception) {
                error = e.message
            }

            runOnUiThread {
                if (success) {
                    Toast.makeText(
                        this,
                        "已强制停止录屏应用，新码率将在下次录屏时生效",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        this,
                        "重启失败，请确认已授予 Root 权限" +
                                (error?.let { "（$it）" } ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }
}