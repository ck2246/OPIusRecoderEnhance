package com.kai.oplusrecorder

import android.content.Context
import android.content.res.Resources
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 屏幕分辨率探测与校验工具。
 *
 * 统一约定：分辨率一律用「竖屏基准」的 Size(短边, 长边) 表示，
 * 横竖屏由调用方自行换算，避免各 Hook 点重复判断方向。
 */
object ScreenResolution {

    private const val TAG = "OplusRecorderResolution"

    /** 允许设置的最小短边（低于该尺寸编码器基本不可用）。 */
    const val MIN_SIDE = 240

    /**
     * 系统录屏提供的分辨率档位（仅指定短边）。
     * 长边由屏幕宽高比换算，例如 1272 × 2772 屏幕的 1080p 实际请求 1080 × 2354。
     * 480 档因与常见屏幕密度值（480dpi）冲突，不纳入改写（保持原有行为）。
     */
    private val SOURCE_SHORT_SIDES = intArrayOf(720, 1080, 1440)

    /** 竖屏基准的屏幕尺寸。 */
    data class Size(val shortSide: Int, val longSide: Int) {
        val isValid: Boolean get() = shortSide > 0 && longSide > 0
    }

    /**
     * 面板原生物理分辨率（短边, 长边）。
     *
     * 系统「屏幕分辨率」设置会切换显示模式（例如 FHD+ / 原生档位），
     * 这里遍历所有显示模式取物理像素最多的一档，保证默认值始终等于面板原生分辨率。
     * [context] 为空时（Xposed 目标进程内没有 Context）改用反射读取 DisplayInfo，
     * 失败则回退到当前显示尺寸。
     */
    fun nativeSize(context: Context?): Size {
        if (context != null) {
            try {
                val manager = context.getSystemService(DisplayManager::class.java)
                bestModeSize(manager?.getDisplay(Display.DEFAULT_DISPLAY)?.supportedModes)
                    ?.let { return it }
            } catch (t: Throwable) {
                Log.w(TAG, "读取显示模式失败: ${t.message}")
            }
        }
        bestModeSize(displayModesByReflection())?.let { return it }
        return currentSize()
    }

    /** 当前生效的屏幕尺寸（短边, 长边），即目标应用实际看到的尺寸。 */
    fun currentSize(): Size {
        val metrics = Resources.getSystem().displayMetrics
        return normalize(metrics.widthPixels, metrics.heightPixels)
    }

    /** 归一化为 (短边, 长边)。 */
    fun normalize(width: Int, height: Int): Size =
        Size(min(width, height), max(width, height))

    /**
     * 判断 (w, h) 是否是系统录屏按「短边档位 × 屏幕宽高比」算出的请求尺寸。
     *
     * 录屏 App 的分辨率选项只提供短边，长边由屏幕宽高比换算（取整），
     * 因此不能用屏幕尺寸直接比对；同时接受屏幕尺寸本身（部分机型按原生分辨率录制）。
     * [alt] 传入当前显示尺寸作为第二个基准：部分机型换算长边时用的是扣除系统栏/挖孔后
     * 的显示尺寸，而非面板物理尺寸。
     */
    fun isSourceSize(w: Int, h: Int, screen: Size, alt: Size? = null): Boolean {
        if (w <= 0 || h <= 0) return false
        val short = min(w, h)
        val long = max(w, h)
        if (sameAs(short, long, screen) || (alt != null && sameAs(short, long, alt))) return true
        return isRecordingPreset(w, h, screen, alt)
    }

    /**
     * 判断 (w, h) 是否对应录屏的某个短边档位（不包含“等于屏幕尺寸”的放宽）。
     */
    fun isRecordingPreset(w: Int, h: Int, screen: Size, alt: Size? = null): Boolean {
        if (w <= 0 || h <= 0) return false
        val short = min(w, h)
        val long = max(w, h)
        if (SOURCE_SHORT_SIDES.none { it == short }) return false
        if (matchRatio(short, long, screen)) return true
        return alt != null && matchRatio(short, long, alt)
    }

    /** (短边, 长边) 是否与某个基准尺寸完全一致。 */
    private fun sameAs(short: Int, long: Int, base: Size): Boolean =
        base.isValid && short == base.shortSide && long == base.longSide

    /** 按基准尺寸的宽高比换算长边并比较（容差覆盖取整误差与轻微比例差异）。 */
    private fun matchRatio(short: Int, long: Int, base: Size): Boolean {
        if (!base.isValid) return false
        val expected = base.longSide.toDouble() * short / base.shortSide
        // 1.5% 容差：既覆盖取整误差，也覆盖“去掉导航栏/挖孔区域后再换算”的机型差异
        val tolerance = max(2.0, expected * 0.015)
        return abs(long - expected) <= tolerance
    }

    /**
     * 校验用户输入的分辨率，合法返回 null，否则返回错误提示。
     * [limit] 为屏幕物理分辨率，宽高均不允许超过它。
     */
    fun validate(width: Int, height: Int, limit: Size): String? {
        if (width <= 0 || height <= 0) return "分辨率必须为正整数"
        if (width % 2 != 0 || height % 2 != 0) return "宽高必须为偶数，否则编码器会报错"
        val size = normalize(width, height)
        if (size.shortSide < MIN_SIDE) return "分辨率过小，短边不能小于 $MIN_SIDE"
        if (limit.isValid &&
            (size.shortSide > limit.shortSide || size.longSide > limit.longSide)
        ) {
            return "不能超过屏幕物理分辨率 ${limit.shortSide} × ${limit.longSide}"
        }
        return null
    }

    /** 从显示模式列表中取物理像素面积最大的一档。 */
    private fun bestModeSize(modes: Array<Display.Mode>?): Size? {
        if (modes == null) return null
        var best: Size? = null
        var bestArea = 0L
        for (mode in modes) {
            if (mode.physicalWidth <= 0 || mode.physicalHeight <= 0) continue
            val size = normalize(mode.physicalWidth, mode.physicalHeight)
            val area = size.shortSide.toLong() * size.longSide
            if (area > bestArea) {
                bestArea = area
                best = size
            }
        }
        return best
    }

    /** 目标进程内没有 Context，只能反射 DisplayManagerGlobal 读取 DisplayInfo。 */
    private fun displayModesByReflection(): Array<Display.Mode>? {
        return try {
            val globalClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
            val global = globalClass.getMethod("getInstance").invoke(null) ?: return null
            val info = globalClass
                .getMethod("getDisplayInfo", Int::class.javaPrimitiveType)
                .invoke(global, Display.DEFAULT_DISPLAY) ?: return null
            @Suppress("UNCHECKED_CAST")
            info.javaClass.getField("supportedModes").get(info) as? Array<Display.Mode>
        } catch (t: Throwable) {
            Log.w(TAG, "反射读取显示模式失败: ${t.message}")
            null
        }
    }
}
