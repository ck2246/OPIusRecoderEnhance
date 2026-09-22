package com.kai.oplusrecorder

import android.hardware.display.DisplayManager
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.util.Log
import android.view.Display
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

class HookModule : XposedModule() {

    companion object {
        private const val TAG = "OplusRecorderHook"

        // 原始屏幕分辨率（竖屏基准）
        private const val ORIG_W = 1080
        private const val ORIG_H = 2354

        // 目标分辨率（竖屏基准）
        private const val TARGET_W = 1272
        private const val TARGET_H = 2772

        // ---- 色彩配置默认值（对应 color.js：BT.709 + Full Range）----
        // 实际值由模块 UI 保存到 settings，可运行时配置，见下方成员变量
        // color-standard / color-matrix: 1=BT709, 2=BT601_PAL, 4=BT601_NTSC, 6=BT2020
        private const val DEFAULT_COLOR_STANDARD = 1   // BT.709（原 bt601）
        // color-range: 1=FULL(0-255), 2=LIMITED(16-235)
        private const val DEFAULT_COLOR_RANGE = 1      // Full（原 limited）
        // color-transfer: 3=SDR, 6=ST2084(PQ), 7=HLG
        private const val DEFAULT_COLOR_TRANSFER = 3   // SDR
        private const val TRANSFER_ST2084 = 6          // PQ（HDR10）
        private const val TRANSFER_HLG = 7             // HLG

        // HDR 模式：0=关闭(SDR 8-bit), 1=HLG 10-bit, 2=HDR10(PQ) 10-bit
        private const val HDR_MODE_OFF = 0
        private const val HDR_MODE_HLG = 1
        private const val HDR_MODE_HDR10 = 2

        // HDR 开启时强制的 BT.2020 原色/矩阵值
        private const val COLOR_STANDARD_BT2020 = 6

        // HEVC 10-bit profile：必须用带 HDR 语义的档位，否则编码器按 SDR 推导 VUI，传输特性退回 bt709
        private const val PROFILE_MAIN10 = 2             // 10-bit，但 VUI 默认 bt709(SDR)
        private const val PROFILE_MAIN10_HDR10 = 0x1000  // 4096：10-bit + PQ
        private const val PROFILE_MAIN10_HLG10 = 0x2000  // 8192：10-bit + HLG

        // MediaFormat 色彩相关 key
        private const val KEY_COLOR_STANDARD = "color-standard"
        private const val KEY_COLOR_RANGE = "color-range"
        private const val KEY_COLOR_TRANSFER = "color-transfer"
        private const val KEY_COLOR_MATRIX = "color-matrix"
        private const val KEY_PROFILE = "profile"
    }

    // 从模块 UI 读取的用户设置
    private var videoBitrate: Int = 30_000_000
    private var audioBitrate: Int = 288_000

    // 色彩设置（由模块 UI 配置，onPackageReady 时读取）
    private var colorEnabled: Boolean = true
    private var colorStandard: Int = DEFAULT_COLOR_STANDARD
    private var colorRange: Int = DEFAULT_COLOR_RANGE
    private var colorTransfer: Int = DEFAULT_COLOR_TRANSFER
    private var hdrMode: Int = HDR_MODE_OFF

    private fun log(msg: String) {
        log(Log.INFO, TAG, msg)
    }

    private fun log(msg: String, tr: Throwable) {
        log(Log.ERROR, TAG, msg, tr)
    }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {

        log(
            Log.INFO,
            TAG,
            "========== MODULE LOADED =========="
        )

        log(
            Log.INFO,
            TAG,
            "API = ${getApiVersion()}"
        )

        log(
            Log.INFO,
            TAG,
            "Framework = ${getFrameworkName()}"
        )
    }

    override fun onPackageReady(param: PackageReadyParam) {

        log(
            Log.INFO,
            TAG,
            "========== PACKAGE READY =========="
        )

        log(
            Log.INFO,
            TAG,
            "packageName = ${param.packageName}"
        )

        if (param.packageName != "com.oplus.screenrecorder") {
            return
        }

        log(
            Log.INFO,
            TAG,
            "========== TARGET FOUND =========="
        )

        // 检测当前进程
        val pid = android.os.Process.myPid()
        val cmdline = java.io.File("/proc/self/cmdline").readText().trimEnd('\u0000')
        val isMainProcess = !cmdline.contains(':')
        val processSuffix = cmdline.substringAfterLast(':', cmdline)
        log("Process: $cmdline, suffix: $processSuffix, PID: $pid, isMain: $isMainProcess")

        // 从模块 UI 的 SharedPreferences 读取用户设置
        try {
            val prefs = getRemotePreferences("settings")
            videoBitrate = prefs.getInt("video_bitrate", 30_000_000)
            audioBitrate = prefs.getInt("audio_bitrate", 288_000)
            colorEnabled = prefs.getBoolean("color_enabled", true)
            colorStandard = prefs.getInt("color_standard", DEFAULT_COLOR_STANDARD)
            colorRange = prefs.getInt("color_range", DEFAULT_COLOR_RANGE)
            hdrMode = prefs.getInt("hdr_mode", HDR_MODE_OFF)
            log("Settings: videoBitrate=$videoBitrate, audioBitrate=$audioBitrate, " +
                    "colorEnabled=$colorEnabled, colorStandard=$colorStandard, colorRange=$colorRange, hdrMode=$hdrMode")
        } catch (t: Throwable) {
            log("Failed to read settings, using defaults", t)
        }

        if (isMainProcess) {
            log("========== HOOKING MAIN PROCESS ==========")

            // ---- 框架类 Hook（可能不触发，用于验证） ----
            hookMediaProjectionCreateVirtualDisplay()
            hookMediaFormatSetInteger()
            hookMediaFormatCreateVideoFormat()
            hookMediaCodecConfigure()
            hookDisplayManagerCreateVirtualDisplay()
            hookVirtualDisplayResize()

            // 应用层包装类 i4.u（App 代码，始终可被 Xposed 拦截）—— 主修复入口
            hookI4UClass(param.classLoader)
        } else {
            // 子进程：只记录信息
            log("Child process: $processSuffix - functional hooks limited to main process")
        }
    }

    // =============================================
    // Hook MediaProjection.createVirtualDisplay (8-arg)
    // =============================================
    private fun hookMediaProjectionCreateVirtualDisplay() {
        try {
            val mpClass = MediaProjection::class.java
            val surfaceClass = Class.forName("android.view.Surface")
            val vdCallbackClass = Class.forName("android.hardware.display.VirtualDisplay\$Callback")
            val handlerClass = Class.forName("android.os.Handler")

            val createVD8 = mpClass.getDeclaredMethod(
                "createVirtualDisplay",
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                surfaceClass,
                vdCallbackClass,
                handlerClass
            )

            deoptimize(createVD8)

            hook(createVD8).intercept { chain ->
                try {
                    val args = chain.args
                    val w = args[1] as Int
                    val h = args[2] as Int

                    log("[VirtualDisplay BEFORE] ${w}x${h}")

                    if (w == ORIG_W && h == ORIG_H) {
                        args[1] = TARGET_W
                        args[2] = TARGET_H
                        log("[HOOK VD] ${w}x${h} -> ${TARGET_W}x${TARGET_H}")
                    } else if (w == ORIG_H && h == ORIG_W) {
                        args[1] = TARGET_H
                        args[2] = TARGET_W
                        log("[HOOK VD] ${w}x${h} -> ${TARGET_H}x${TARGET_W}")
                    }

                    log("[VirtualDisplay AFTER] ${args[1]}x${args[2]}")
                    chain.proceed(args.toTypedArray())
                } catch (t: Throwable) {
                    log("[VD CALLBACK ERROR]", t)
                    chain.proceed()
                }
            }

            log("[+] MediaProjection.createVirtualDisplay hook installed")
        } catch (t: Throwable) {
            log("HOOK createVirtualDisplay FAILED", t)
        }
    }

    // =============================================
    // Hook MediaFormat.setInteger(String, int)
    // =============================================
    private fun hookMediaFormatSetInteger() {
        try {
            val setInteger = MediaFormat::class.java.getDeclaredMethod(
                "setInteger", String::class.java, Int::class.javaPrimitiveType
            )

            deoptimize(setInteger)

            hook(setInteger).intercept { chain ->
                try {
                    val format = chain.thisObject as MediaFormat
                    val key = chain.args[0] as String
                    var value = chain.args[1] as Int

                    log("[setInteger BEFORE] key=$key, value=$value")

                    val newValue = computeNewValue(key, value)
                    if (newValue != null) {
                        log("[HOOK] $key $value -> $newValue")
                        value = newValue
                    }

                    // Proceed with possibly-modified value so the original caller receives the changed value
                    chain.proceed(arrayOf(chain.args[0], value))

                    log("[setInteger AFTER] key=$key, value=$value")
                } catch (t: Throwable) {
                    log("[setInteger CALLBACK ERROR]", t)
                    chain.proceed()
                }
            }

            log("[+] MediaFormat.setInteger hook installed")

        } catch (t: Throwable) {
            log("HOOK setInteger FAILED", t)
        }
    }

    /**
     * 计算修改后的值，返回 null 表示不需要修改
     * 与 hook.js (Frida) 保持一致的修改策略
     */
    private fun computeNewValue(key: String, value: Int): Int? {
        // 修改宽度
        if (key == "width" && value == ORIG_W) return TARGET_W
        if (key == "width" && value == ORIG_H) return TARGET_H
        // 修改高度
        if (key == "height" && value == ORIG_H) return TARGET_H
        if (key == "height" && value == ORIG_W) return TARGET_W
        // 修改视频码率（> 1Mbps 视为视频）
        if (key == "bitrate" && value > 1_000_000) return videoBitrate
        // 修改音频码率（< 1Mbps 视为音频）
        if (key == "bitrate" && value < 1_000_000) return audioBitrate
        // 色彩空间与范围（由 UI 配置）；关闭时不改写，保持 App 原值
        if (colorEnabled) {
            val hdrOn = hdrMode != HDR_MODE_OFF
            val effStandard = if (hdrOn) COLOR_STANDARD_BT2020 else colorStandard
            val effTransfer = when {
                !hdrOn -> colorTransfer
                hdrMode == HDR_MODE_HDR10 -> TRANSFER_ST2084
                else -> TRANSFER_HLG
            }
            if (key == KEY_COLOR_STANDARD) return effStandard
            if (key == KEY_COLOR_RANGE) return colorRange
            if (key == KEY_COLOR_TRANSFER) return effTransfer
            if (key == KEY_COLOR_MATRIX) return effStandard
        }
        return null
    }

    // =============================================
    // Hook MediaFormat.createVideoFormat(String, int, int)
    // 诊断：检查应用是否通过此工厂方法创建格式
    // =============================================
    private fun hookMediaFormatCreateVideoFormat() {
        try {
            val createVideoFormat = MediaFormat::class.java.getDeclaredMethod(
                "createVideoFormat", String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )

            deoptimize(createVideoFormat)

            hook(createVideoFormat).intercept { chain ->
                try {
                    val mime = chain.args[0] as String
                    val w = chain.args[1] as Int
                    val h = chain.args[2] as Int
                    log("[createVideoFormat] BEFORE: mime=$mime, ${w}x${h}")

                    val result = chain.proceed()

                    log("[createVideoFormat] AFTER: result=$result")
                    result
                } catch (t: Throwable) {
                    log("[createVideoFormat CALLBACK ERROR]", t)
                    chain.proceed()
                }
            }

            log("[+] MediaFormat.createVideoFormat hook installed")
        } catch (t: Throwable) {
            log("HOOK createVideoFormat FAILED", t)
        }
    }

    // =============================================
    // Hook MediaCodec.configure（所有含 MediaFormat 的重载）
    // 对应 color.js：在编码器 configure 前强制注入色彩参数，
    // 防止 App 未显式调用 setInteger 导致色彩参数缺失（保持 bt601+limited）。
    // 这是色彩修改的主入口：configure 为大型 native 方法，不会被 AOT 内联，触发可靠。
    // =============================================
    private fun hookMediaCodecConfigure() {
        try {
            val codecClass = MediaCodec::class.java
            var hooked = 0
            for (m in codecClass.declaredMethods) {
                if (m.name != "configure") continue
                val params = m.parameterTypes
                // 仅处理带 MediaFormat 参数的重载
                val fmtIdx = params.indexOfFirst { it == MediaFormat::class.java }
                if (fmtIdx < 0) continue
                val sig = params.joinToString(",") { it.simpleName }
                try {
                    deoptimize(m)
                    hook(m).intercept { chain ->
                        try {
                            val codec = chain.thisObject as? MediaCodec
                            val name = try { codec?.name ?: "" } catch (t: Throwable) { "" }
                            val format = chain.args[fmtIdx] as? MediaFormat
                            injectColorParams(codec, name, format)
                            chain.proceed()
                        } catch (t: Throwable) {
                            log("[CONFIG CALLBACK ERROR]", t)
                            chain.proceed()
                        }
                    }
                    hooked++
                    log("[+] MediaCodec.configure($sig) hooked")
                } catch (t: Throwable) {
                    log("[!] Failed to hook MediaCodec.configure($sig): ${t.message}")
                }
            }
            if (hooked == 0) {
                log("[!] MediaCodec.configure: 未找到含 MediaFormat 参数的重载")
            }
        } catch (t: Throwable) {
            log("hookMediaCodecConfigure FAILED", t)
        }
    }

    /**
     * 对视频编码器的 MediaFormat 强制注入色彩参数。
     * - SDR 模式：注入 UI 选择的色彩标准/范围，传输特性为 SDR。
     * - HDR 模式：强制 BT.2020 原色/矩阵 + HLG/PQ 传输特性，并注入带 HDR 语义的
     *   10-bit profile（Main10HLG10 / Main10HDR10）。这是同时拿到 10-bit 与正确传输特性
     *   的关键：仅设 Main10 会让编码器按 SDR 推导 VUI，传输特性退回 bt709。
     * 通过 mime / codec 名称判定视频编码器，音频编码器不受影响。
     */
    private fun injectColorParams(codec: MediaCodec?, codecName: String, format: MediaFormat?) {
        if (format == null) return
        if (!colorEnabled) {
            log("[CONFIG] 色彩优化已关闭，跳过注入")
            return
        }
        val mime = try { format.getString(MediaFormat.KEY_MIME) ?: "" } catch (t: Throwable) { "" }
        val lower = codecName.lowercase()
        val isVideo = mime.startsWith("video/") ||
                lower.contains("video") || lower.contains("avc") || lower.contains("hevc")
        log("[CONFIG] Codec=$codecName, mime=$mime, isVideo=$isVideo, hdrMode=$hdrMode")
        if (!isVideo) return

        val hdrOn = hdrMode != HDR_MODE_OFF
        // HDR 开启时强制 BT.2020 原色/矩阵与 HLG/PQ 传输特性，覆盖 UI 的 SDR 选择
        val effStandard = if (hdrOn) COLOR_STANDARD_BT2020 else colorStandard
        val effTransfer = when {
            !hdrOn -> colorTransfer
            hdrMode == HDR_MODE_HDR10 -> TRANSFER_ST2084
            else -> TRANSFER_HLG
        }
        try {
            format.setInteger(KEY_COLOR_STANDARD, effStandard)
            format.setInteger(KEY_COLOR_RANGE, colorRange)
            format.setInteger(KEY_COLOR_TRANSFER, effTransfer)
            format.setInteger(KEY_COLOR_MATRIX, effStandard)

            if (hdrOn) {
                val profile = pickHdrProfile(codec, mime, effTransfer)
                if (profile != null) {
                    format.setInteger(KEY_PROFILE, profile)
                    log("[CONFIG] 注入 profile=$profile (10-bit + HDR)")
                } else {
                    log("[CONFIG] 无可用 10-bit profile，保持编码器默认（可能 8-bit）")
                }
            }
            log("[CONFIG] 已注入色彩参数 (standard=$effStandard, range=$colorRange, transfer=$effTransfer)")
        } catch (t: Throwable) {
            log("[CONFIG] 注入色彩参数失败，硬件可能不支持: ${t.message}")
        }
    }

    /**
     * 查询编码器能力，按传输特性挑选带 HDR 语义的 10-bit profile。
     * 优先 Main10HLG10(HLG) / Main10HDR10(PQ)；若硬件不暴露该档位，回退 Main10
     * （此时能拿到 10-bit，但传输特性可能被推导为 bt709）；均不支持则返回 null。
     * 注意：API 37 已移除 MediaCodecInfo.getCapabilities()，须用 getCapabilitiesForType(mime)。
     */
    private fun pickHdrProfile(codec: MediaCodec?, mime: String, transfer: Int): Int? {
        val profiles = mutableSetOf<Int>()
        try {
            val caps = codec?.codecInfo?.getCapabilitiesForType(mime)
            if (caps != null) {
                for (pl in caps.profileLevels) {
                    profiles.add(pl.profile)
                }
            }
        } catch (t: Throwable) {
            log("[CAPS] 读取编码器能力失败: ${t.message}")
        }
        log("[CAPS] $mime 支持的 profiles=$profiles")

        val want = if (transfer == TRANSFER_HLG) PROFILE_MAIN10_HLG10 else PROFILE_MAIN10_HDR10
        return when {
            profiles.contains(want) -> want
            profiles.contains(PROFILE_MAIN10) -> PROFILE_MAIN10
            else -> null
        }
    }

    // =============================================
    // Hook DisplayManager.createVirtualDisplay
    // =============================================
    private fun hookDisplayManagerCreateVirtualDisplay() {
        try {
            val dmClass = DisplayManager::class.java
            val surfaceClass = Class.forName("android.view.Surface")

            // 尝试所有 createVirtualDisplay 重载
            for (m in dmClass.getDeclaredMethods()) {
                if (m.name != "createVirtualDisplay") continue
                val params = m.parameterTypes
                try {
                    deoptimize(m)
                    hook(m).intercept { chain ->
                        try {
                            val args = chain.args
                            log("[DM createVD] ${args.size} args: ${params.joinToString(",") { it.simpleName }}")

                            // 找 int 参数中的 width/height
                            val intIndices = params.indices.filter { params[it] == Int::class.javaPrimitiveType }
                            if (intIndices.size >= 2) {
                                val wIdx = intIndices[0]
                                val hIdx = intIndices[1]
                                val w = args[wIdx] as Int
                                val h = args[hIdx] as Int
                                log("[DM createVD] w=$w, h=$h at idx [$wIdx,$hIdx]")

                                if (w == ORIG_W && h == ORIG_H) {
                                    args[wIdx] = TARGET_W
                                    args[hIdx] = TARGET_H
                                    log("[HOOK DM createVD] -> ${TARGET_W}x${TARGET_H}")
                                } else if (w == ORIG_H && h == ORIG_W) {
                                    args[wIdx] = TARGET_H
                                    args[hIdx] = TARGET_W
                                    log("[HOOK DM createVD] -> ${TARGET_H}x${TARGET_W}")
                                }
                            }

                            // 对 VirtualDisplayConfig 等对象参数，反射重映射内部分辨率字段
                            for (i in args.indices) {
                                val arg = args[i] ?: continue
                                if (arg.javaClass.name.contains("VirtualDisplayConfig")) {
                                    log("[DM createVD] config arg[$i]=${arg.javaClass.name}")
//                                    dumpFields(arg, "dm.config$i")
                                    modifyIntFields(arg, "dm.config$i")
                                }
                            }

                            chain.proceed(args.toTypedArray())
                        } catch (t: Throwable) {
                            log("[DM createVD CALLBACK ERROR]", t)
                            chain.proceed()
                        }
                    }
                    log("[+] DisplayManager.createVirtualDisplay(${params.joinToString(",") { it.simpleName }}) hooked")
                } catch (t: Throwable) {
                    log("[!] Failed to hook DM.createVD: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            log("hookDisplayManagerCreateVirtualDisplay FAILED", t)
        }
    }

    private fun hookVirtualDisplayResize() {
        try {
            val vdClass = Class.forName("android.hardware.display.VirtualDisplay")
            for (m in vdClass.getDeclaredMethods()) {
                if (m.name == "resize") {
                    deoptimize(m)
                    hook(m).intercept { chain ->
                        val args = chain.args
                        val w = args[0] as Int
                        val h = args[1] as Int
                        log("[VD.resize BEFORE] ${w}x${h}")
                        if (w == ORIG_W && h == ORIG_H) {
                            args[0] = TARGET_W
                            args[1] = TARGET_H
                            log("[HOOK VD.resize] ${w}x${h} -> ${TARGET_W}x${TARGET_H}")
                        }
                        chain.proceed(args.toTypedArray())
                    }
                    log("[+] VirtualDisplay.resize hooked")
                }
            }
        } catch (t: Throwable) {
            log("[!] VirtualDisplay.resize hook failed: ${t.message}")
        }
    }

    // =============================================
    // Hook 应用层包装类 i4.u（app 代码，始终可拦截）
    // 这是主修复入口：在 App 调用框架 createVirtualDisplay 之前，
    // 直接反射修改配置对象 o 里的宽高字段，绕开框架方法 AOT 内联问题。
    // =============================================
    private fun hookI4UClass(classLoader: ClassLoader) {
        try {
            val uCls = classLoader.loadClass("i4.u")
            var hooked = 0
            for (m in uCls.declaredMethods) {
                val params = m.parameterTypes
                // 只 hook 第一个参数为 MediaProjection 的重载（即 createVirtualDisplay 包装）
                if (params.isEmpty()) continue
                if (params[0].name != "android.media.projection.MediaProjection") continue
                try {
                    deoptimize(m)
                    val mName = m.name
                    val paramSig = params.joinToString(",") { it.simpleName }
                    hook(m).intercept { chain ->
                        try {
                            log("[i4.u.$mName] CALLED ($paramSig)")
                            // 扫描所有对象参数：dump 字段 + 重映射分辨率 int 字段
                            for (i in chain.args.indices) {
                                val arg = chain.args[i] ?: continue
                                val cn = arg.javaClass.name
                                // 跳过框架/JDK 类型（Surface、MediaProjection、Callback、Handler、String 等）
                                if (cn.startsWith("android.") || cn.startsWith("java.") ||
                                    cn.startsWith("kotlin.") || cn.startsWith("androidx.")
                                ) continue
                                log("[i4.u.$mName] arg[$i] type=$cn")

//                                dumpFields(arg, "i4u.$mName.arg$i")

                                modifyIntFields(arg, "i4u.$mName.arg$i")
                            }
                            // 对象字段是就地修改的，直接 proceed 即可
                            chain.proceed()
                        } catch (t: Throwable) {
                            log("[i4.u.$mName CALLBACK ERROR]", t)
                            chain.proceed()
                        }
                    }
                    hooked++
                    log("[+] i4.u.$mName($paramSig) hooked")
                } catch (t: Throwable) {
                    log("[!] Failed to hook i4.u.${m.name}: ${t.message}")
                }
            }
            if (hooked == 0) {
                log("[!] i4.u: 未找到以 MediaProjection 为首参的重载，无法 hook")
            }
        } catch (t: Throwable) {
            log("hookI4UClass FAILED", t)
        }
    }


    private fun modifyIntFields(obj: Any?, tag: String) {
        if (obj == null) return
        try {
            for (f in obj.javaClass.declaredFields) {
                try {
                    f.isAccessible = true
                    if (f.type == Int::class.javaPrimitiveType) {
                        val v = f.getInt(obj)
                        if (v == ORIG_W) {
                            f.setInt(obj, TARGET_W)
                            log("[HOOK $tag.${f.name}] $v -> $TARGET_W")
                        } else if (v == ORIG_H) {
                            f.setInt(obj, TARGET_H)
                            log("[HOOK $tag.${f.name}] $v -> $TARGET_H")
                        }
                    }
                } catch (_: Throwable) {}
            }
        } catch (t: Throwable) { log("modifyIntFields $tag failed", t) }
    }
}
