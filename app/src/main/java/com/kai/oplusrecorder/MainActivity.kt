package com.kai.oplusrecorder

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat

class MainActivity : AppCompatActivity() {

    // 色彩标准选项：显示文本 <-> MediaFormat color-standard 值
    private val colorStandardLabels = arrayOf("BT.709", "BT.601", "BT.2020")
    private val colorStandardValues = intArrayOf(1, 2, 6)

    // 色彩范围选项：显示文本 <-> MediaFormat color-range 值
    private val colorRangeLabels = arrayOf("Full（0-255）", "Limited（16-235）")
    private val colorRangeValues = intArrayOf(1, 2)

    // HDR 模式选项：显示文本 <-> hdr_mode 设置值（0=关闭, 1=HLG, 2=HDR10/PQ）
    private val hdrModeLabels = arrayOf("关闭（SDR 8-bit）", "HLG 10-bit（推荐）", "HDR10（PQ）10-bit")
    private val hdrModeValues = intArrayOf(0, 1, 2)

    private lateinit var videoEdit: EditText
    private lateinit var audioEdit: EditText
    private lateinit var resolutionWidthEdit: EditText
    private lateinit var resolutionHeightEdit: EditText
    private lateinit var resolutionHint: TextView
    private lateinit var colorEnabledCheck: CheckBox
    private lateinit var colorStandardSpinner: Spinner
    private lateinit var colorRangeSpinner: Spinner
    private lateinit var hdrModeSpinner: Spinner
    private lateinit var screenshotPngCheck: CheckBox

    /** 面板原生物理分辨率（竖屏基准），作为默认值与输入上限。 */
    private lateinit var nativeResolution: ScreenResolution.Size

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        setContentView(
            R.layout.activity_main
        )

        videoEdit = findViewById(R.id.videoBitrate)
        audioEdit = findViewById(R.id.audioBitrate)
        resolutionWidthEdit = findViewById(R.id.resolutionWidth)
        resolutionHeightEdit = findViewById(R.id.resolutionHeight)
        resolutionHint = findViewById(R.id.resolutionHint)
        colorEnabledCheck = findViewById(R.id.colorEnabled)
        colorStandardSpinner = findViewById(R.id.colorStandard)
        colorRangeSpinner = findViewById(R.id.colorRange)
        hdrModeSpinner = findViewById(R.id.hdrMode)
        screenshotPngCheck = findViewById(R.id.screenshotPng)

        // 分辨率默认值跟随屏幕物理分辨率，上限也以它为准
        nativeResolution = ScreenResolution.nativeSize(this)
        resolutionHint.text = "默认 = 屏幕物理分辨率 " +
                "${nativeResolution.shortSide} × ${nativeResolution.longSide}" +
                "（竖屏）；宽高顺序可互换，均不得超过该值，且需为偶数。"
        showResolution(nativeResolution)

        WindowCompat.getInsetsController(window, window.decorView).apply {
            // 状态栏图标变黑（深色）
            isAppearanceLightStatusBars = true

            // 如果需要导航栏图标也变黑
            isAppearanceLightNavigationBars = true
        }

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
        hdrModeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            hdrModeLabels
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

        // 重启图标：强制停止目标应用（录屏 + 截屏），使新配置在下次启动时生效
        findViewById<ImageView>(
            R.id.iv_reset
        ).setOnClickListener {
            restartTargets()
        }
    }

    /** 从 RemoteSettings 读取已保存的配置并回填到 UI。 */
    private fun loadSettings() {
        val prefs = RemoteSettings.prefs ?: return

        // 旧版本没有分辨率配置项：首次打开界面时把检测到的屏幕物理分辨率写成默认值，
        // 保证目标进程即使拿不到显示模式信息，也能用上正确的默认分辨率。
        if (!prefs.contains("resolution_width") || !prefs.contains("resolution_height")) {
            prefs.edit()
                .putInt("resolution_width", nativeResolution.shortSide)
                .putInt("resolution_height", nativeResolution.longSide)
                .apply()
        }

        showResolution(
            ScreenResolution.normalize(
                prefs.getInt("resolution_width", nativeResolution.shortSide),
                prefs.getInt("resolution_height", nativeResolution.longSide)
            )
        )
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
        hdrModeSpinner.setSelection(
            indexOfValue(hdrModeValues, prefs.getInt("hdr_mode", 0))
        )
        screenshotPngCheck.isChecked = prefs.getBoolean("screenshot_png", true)
    }

    /** 校验并保存 UI 中的配置到 RemoteSettings。 */
    private fun saveSettings() {
        val videoMbps = videoEdit.text.toString().toDoubleOrNull()
        val audioKbps = audioEdit.text.toString().toIntOrNull()

        if (videoMbps == null || audioKbps == null) {
            Toast.makeText(this, "请输入有效数字", Toast.LENGTH_SHORT).show()
            return
        }

        val resWidth = resolutionWidthEdit.text.toString().toIntOrNull()
        val resHeight = resolutionHeightEdit.text.toString().toIntOrNull()
        if (resWidth == null || resHeight == null) {
            Toast.makeText(this, "请输入有效的分辨率数值", Toast.LENGTH_SHORT).show()
            return
        }
        val resError = ScreenResolution.validate(resWidth, resHeight, nativeResolution)
        if (resError != null) {
            Toast.makeText(this, resError, Toast.LENGTH_LONG).show()
            return
        }
        val resolution = ScreenResolution.normalize(resWidth, resHeight)

        val editor = RemoteSettings.prefs?.edit()
        if (editor == null) {
            Toast.makeText(this, "配置服务未就绪，请稍后重试", Toast.LENGTH_SHORT).show()
            return
        }

        val videoBps = (videoMbps * 1_000_000).toInt()
        val audioBps = audioKbps * 1000
        val standard = colorStandardValues[colorStandardSpinner.selectedItemPosition]
        val range = colorRangeValues[colorRangeSpinner.selectedItemPosition]
        val hdr = hdrModeValues[hdrModeSpinner.selectedItemPosition]

        editor.putInt("resolution_width", resolution.shortSide)
            .putInt("resolution_height", resolution.longSide)
            .putInt("video_bitrate", videoBps)
            .putInt("audio_bitrate", audioBps)
            .putBoolean("color_enabled", colorEnabledCheck.isChecked)
            .putInt("color_standard", standard)
            .putInt("color_range", range)
            .putInt("hdr_mode", hdr)
            .putBoolean("screenshot_png", screenshotPngCheck.isChecked)
            .apply()

        // 回填归一化后的值，避免横竖屏顺序误解
        showResolution(resolution)
        Toast.makeText(
            this,
            "设置已保存（分辨率 ${resolution.shortSide} × ${resolution.longSide}）",
            Toast.LENGTH_SHORT
        ).show()
    }

    /** 把分辨率按竖屏基准（宽 = 短边）回填到输入框。 */
    private fun showResolution(size: ScreenResolution.Size) {
        resolutionWidthEdit.setText(size.shortSide.toString())
        resolutionHeightEdit.setText(size.longSide.toString())
    }

    /** 根据值查找对应下拉框位置，找不到时回退到 0。 */
    private fun indexOfValue(values: IntArray, value: Int): Int {
        val i = values.indexOf(value)
        return if (i >= 0) i else 0
    }

    /**
     * 强制停止两个目标应用：
     *  - com.oplus.screenrecorder（系统录屏）
     *  - com.oplus.screenshot（系统截屏）
     *
     * 两者的 Hook 配置都在进程启动时（HookModule.onPackageReady）一次性读取，
     * 因此修改设置后必须杀掉其进程，下次启动才会重新加载新配置。
     * 这里只做 force-stop，不负责重新拉起它们（系统会按需自行拉起）。
     *
     * 依赖设备 Root 权限（LSPosed 环境已具备），通过 su 执行 am force-stop。
     */
    private fun restartTargets() {
        Thread {
            val targets = listOf(
                "com.oplus.screenrecorder",
                "com.oplus.screenshot"
            )
            var success = false
            var error: String? = null
            try {
                val cmd = targets.joinToString("; ") { "am force-stop $it" }
                val proc = Runtime.getRuntime().exec(
                    arrayOf("su", "-c", cmd)
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
                        "已重启录屏与截屏应用，设置将在下次使用时生效",
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