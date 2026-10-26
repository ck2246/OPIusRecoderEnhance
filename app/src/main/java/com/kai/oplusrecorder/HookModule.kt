package com.kai.oplusrecorder

import android.hardware.display.DisplayManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.util.Log
import android.view.Display
import dalvik.system.DexFile
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.io.File
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

class HookModule : XposedModule() {

    companion object {
        private const val TAG = "OplusRecorderHook"

        // ---- 色彩配置默认值（对应 color.js：BT.709 + Full Range）----
        // 实际值由模块 UI 保存到 settings，可运行时配置，见下方成员变量
        // color-standard / color-matrix: 1=BT709, 2=BT601_PAL, 4=BT601_NTSC, 6=BT2020
        private const val DEFAULT_COLOR_STANDARD = 1   // BT.709（原 bt601）
        // color-range: 1=FULL(0-255), 2=LIMITED(16-235)
        private const val DEFAULT_COLOR_RANGE = 1      // Full（原 limited）
        private const val COLOR_RANGE_LIMITED = 2
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
        private const val PROFILE_MAIN10 = 0x02             // 10-bit SDR（HLG 也用它 + transfer=HLG）
        private const val PROFILE_MAIN10_HDR10 = 0x1000     // 4096：10-bit + PQ
        // 注意：0x2000 是 HEVCProfileMain10HDR10Plus（HDR10+），不是 HLG；
        // HLG 没有独立的 profile，应使用 Main10 + color-transfer=HLG(7)。

        // MediaFormat 色彩相关 key
        private const val KEY_COLOR_STANDARD = "color-standard"
        private const val KEY_COLOR_RANGE = "color-range"
        private const val KEY_COLOR_TRANSFER = "color-transfer"
        private const val KEY_HDR_STATIC_INFO = "hdr-static-info"
        private const val KEY_PROFILE = "profile"

        // HDR 静态元数据（CTA-861.3 Static Metadata Descriptor，MediaMuxer 硬性要求 25 字节）
        // 布局：1 字节 descriptor ID + 6 个 16bit 原色 x/y + 2 个 16bit 白点 + 4 个 16bit 亮度/光强。
        // 全部小端；数值单位：原色/白点 1/50000，亮度 1 nit，MaxCLL/MaxFALL 1 nit。
        private const val HDR_STATIC_INFO_SIZE = 25
        private const val HDR_MAX_DISPLAY_LUMINANCE = 1000
        // 实测 Android/MediaMuxer 把该字段按 1 nit 解释并写入 mdcv（不是 0.0001 nit），
        // 因此这里取 1 而不是 CTA 文档里的 50（50 会被写成 50 nit 的黑位）。
        private const val HDR_MIN_DISPLAY_LUMINANCE = 1
        private const val HDR_MAX_CONTENT_LIGHT_LEVEL = 1000
        private const val HDR_MAX_FRAME_AVERAGE_LIGHT_LEVEL = 400

        // 应用层 VirtualDisplay 包装类的已知类名（不同 App 版本混淆名会变）。
        // 先试已知名，命中就跳过昂贵的全 dex 扫描。
        private val KNOWN_WRAPPER_CLASSES = arrayOf("i4.u", "z5.u")

        // 动态扫描应用层包装类时的最大候选数（避免启动过慢）
        private const val MAX_SCAN_CLASSES = 400
    }

    // 从模块 UI 读取的用户设置
    private var videoBitrate: Int = 30_000_000
    private var audioBitrate: Int = 288_000

    // 分辨率（竖屏基准）：
    // screen = 面板物理分辨率，用于识别录屏 App 的请求尺寸（短边档位 × 屏幕比例）；
    // display = 当前显示尺寸，作为第二个识别基准（有的机型换算长边时扣除了系统栏/挖孔）；
    // target = 用户配置的目标分辨率，未配置或非法时回退到 screen
    @Volatile private var screenSize = ScreenResolution.Size(0, 0)
    @Volatile private var displaySize = ScreenResolution.Size(0, 0)
    @Volatile private var targetShort = 0
    @Volatile private var targetLong = 0

    // 编码器 configure 时捕获到的真实录制请求尺寸（如 1080 × 2354），用于 VD 侧精确匹配
    @Volatile private var sourceShort = 0
    @Volatile private var sourceLong = 0

    // 宽高改写可能再次进入 setInteger Hook，用线程级标记防重入
    private val formatRewriteGuard = ThreadLocal<Boolean>()

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

        // 从模块 UI 的 SharedPreferences 读取用户设置；分辨率为 0 表示未配置
        var prefWidth = 0
        var prefHeight = 0
        try {
            val prefs = getRemotePreferences("settings")
            videoBitrate = prefs.getInt("video_bitrate", 30_000_000)
            audioBitrate = prefs.getInt("audio_bitrate", 288_000)
            colorEnabled = prefs.getBoolean("color_enabled", true)
            colorStandard = prefs.getInt("color_standard", DEFAULT_COLOR_STANDARD)
            colorRange = prefs.getInt("color_range", DEFAULT_COLOR_RANGE)
            hdrMode = prefs.getInt("hdr_mode", HDR_MODE_OFF)
            prefWidth = prefs.getInt("resolution_width", 0)
            prefHeight = prefs.getInt("resolution_height", 0)
            log("Settings: videoBitrate=$videoBitrate, audioBitrate=$audioBitrate, " +
                    "colorEnabled=$colorEnabled, colorStandard=$colorStandard, colorRange=$colorRange, " +
                    "hdrMode=$hdrMode, resolution=${prefWidth}x$prefHeight")
        } catch (t: Throwable) {
            log("Failed to read settings, using defaults", t)
        }

        resolveResolution(prefWidth, prefHeight)

        // 录制链路可能出现在任意进程（部分机型在子进程编码），所有进程都安装 Hook
        log(
            if (isMainProcess) "========== HOOKING MAIN PROCESS =========="
            else "========== HOOKING CHILD PROCESS ($processSuffix) =========="
        )

        // ---- 框架层 Hook：覆盖各系统版本的 VirtualDisplay 创建路径 ----
        hookMediaProjectionCreateVirtualDisplay()
        hookDisplayManagerCreateVirtualDisplay()
        hookDisplayManagerGlobalCreateVirtualDisplay()
        hookVirtualDisplayConfigConstructors()
        hookVirtualDisplayResize()

        // ---- 编码器 Hook：宽高 / 码率 / 色彩 ----
        hookMediaFormatSetInteger()
        hookMediaFormatCreateVideoFormat()
        hookMediaCodecConfigure()

        // 应用层包装类：类名随 App 版本变化（旧版为 i4.u），改为动态发现
        hookAppWrapperClasses(param.classLoader)
    }

    // =============================================
    // Hook MediaProjection.createVirtualDisplay（所有重载）
    // App 可能改用 createVirtualDisplay(VirtualDisplayConfig) 新 API，
    // 只 hook 8 参旧重载会完全失效。
    // =============================================
    private fun hookMediaProjectionCreateVirtualDisplay() {
        try {
            var hooked = 0
            for (m in MediaProjection::class.java.declaredMethods) {
                if (m.name != "createVirtualDisplay") continue
                try {
                    hookSizeCall(m, "MP createVD")
                    hooked++
                    log("[+] MediaProjection.createVirtualDisplay(${m.parameterTypes.joinToString(",") { it.simpleName }}) hooked")
                } catch (t: Throwable) {
                    log("[!] Failed to hook MediaProjection.createVD(${m.parameterTypes.size} args): ${t.message}")
                }
            }
            if (hooked == 0) log("[!] MediaProjection.createVirtualDisplay: 未找到可用重载")
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
                val format = chain.thisObject as? MediaFormat
                val key = chain.args.getOrNull(0) as? String
                val original = chain.args.getOrNull(1) as? Int
                if (format == null || key == null || original == null) {
                    // 参数异常时不改写，且只 proceed 一次
                    chain.proceed()
                } else {
                    var value = original
                    try {
                        log("[setInteger BEFORE] key=$key, value=$value")
                        val newValue = computeNewValue(format, key, value)
                        if (newValue != null) {
                            log("[HOOK] $key $value -> $newValue")
                            value = newValue
                        }
                    } catch (t: Throwable) {
                        log("[setInteger CALLBACK ERROR]", t)
                    }

                    // Proceed with possibly-modified value so the original caller receives the changed value.
                    // 这里只调用一次 proceed；异常后再次调用会重复执行 setInteger。
                    chain.proceed(arrayOf<Any?>(key, value))

                    // 宽高是分两次写入的：写完后成对检查一次，命中录屏尺寸则同步改写
                    try {
                        rewriteFormatPair(format)
                    } catch (t: Throwable) {
                        log("[setInteger REWRITE ERROR]", t)
                    }
                    log("[setInteger AFTER] key=$key, value=$value")
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
    private fun computeNewValue(format: MediaFormat?, key: String, value: Int): Int? {
        // 宽高必须成对改写（见 rewriteResolution），这里不对单个 width/height 做映射
        // 码率：优先按 mime 区分音视频，取不到 mime 时退回 >1Mbps 的经验判断
        if (key == "bitrate") {
            val mime = try { format?.getString(MediaFormat.KEY_MIME) ?: "" } catch (t: Throwable) { "" }
            return when {
                mime.startsWith("video/") -> videoBitrate
                mime.startsWith("audio/") -> audioBitrate
                value > 1_000_000 -> videoBitrate
                else -> audioBitrate
            }
        }
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
            if (key == KEY_COLOR_RANGE) return if (hdrOn) COLOR_RANGE_LIMITED else colorRange
            if (key == KEY_COLOR_TRANSFER) return effTransfer
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
                } catch (t: Throwable) {
                    log("[createVideoFormat BEFORE ERROR]", t)
                }
                val result = chain.proceed()
                try {
                    log("[createVideoFormat] AFTER: result=$result")
                } catch (t: Throwable) {
                    log("[createVideoFormat AFTER ERROR]", t)
                }
                result
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
                            rewriteResolution(name, format)
                            injectColorParams(codec, name, format)
                        } catch (t: Throwable) {
                            log("[CONFIG CALLBACK ERROR]", t)
                        }
                        // 只 proceed 一次：configure 失败时再次调用会导致重复 configure
                        chain.proceed()
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
     * 在编码器 configure 前成对改写宽高。
     *
     * 录屏 App 的请求尺寸 = 短边档位 × 屏幕宽高比（如 1080 × 2354），
     * 只能成对识别改写；对单个 width/height 值做映射会误伤缩略图等其它编码器。
     * 与色彩开关无关，始终生效。
     */
    private fun rewriteResolution(codecName: String, format: MediaFormat?) {
        if (format == null) return
        val mime = try { format.getString(MediaFormat.KEY_MIME) ?: "" } catch (t: Throwable) { "" }
        val lower = codecName.lowercase()
        val isVideo = mime.startsWith("video/") ||
                lower.contains("video") || lower.contains("avc") || lower.contains("hevc")
        if (!isVideo) return
        rewriteFormatPair(format)
    }

    /**
     * 读取 MediaFormat 的宽高，命中录屏请求尺寸时成对改写为目标分辨率。
     * 首次命中时顺便记录真实请求尺寸，供 VirtualDisplay 侧精确匹配。
     */
    private fun rewriteFormatPair(format: MediaFormat?) {
        if (format == null || !screenSize.isValid) return
        if (formatRewriteGuard.get() == true) return
        val w = try { format.getInteger(MediaFormat.KEY_WIDTH) } catch (t: Throwable) { return }
        val h = try { format.getInteger(MediaFormat.KEY_HEIGHT) } catch (t: Throwable) { return }
        if (sourceShort <= 0 && ScreenResolution.isSourceSize(w, h, screenSize, displaySize)) {
            sourceShort = minOf(w, h)
            sourceLong = maxOf(w, h)
            log("检测到录屏请求尺寸 ${sourceShort}x$sourceLong")
        }
        val mapped = mapSize(w, h) ?: return
        formatRewriteGuard.set(true)
        try {
            format.setInteger(MediaFormat.KEY_WIDTH, mapped.first)
            format.setInteger(MediaFormat.KEY_HEIGHT, mapped.second)
        } finally {
            formatRewriteGuard.set(false)
        }
        log("[CONFIG] 分辨率 ${w}x${h} -> ${mapped.first}x${mapped.second}")
    }

    /**
     * 对视频编码器的 MediaFormat 强制注入色彩参数。
     * - SDR 模式：注入 UI 选择的色彩标准/范围，传输特性为 SDR。
     * - HDR 模式：仅对 HEVC 生效，强制 BT.2020 原色 + HLG/PQ 传输特性 + 10-bit profile。
     *   HDR10 还会写入 25 字节的 `hdr-static-info`（BT.2020 原色/D65 白点/1000nit 峰值/
     *   1nit 黑位/MaxCLL 1000/MaxFALL 400）；MediaMuxer 只有在该 ByteBuffer 恰好为
     *   25 字节时才写 mdcv/clli box，类型/长度不对会被静默忽略。
     *   HLG 是显示参考信号，不使用 mastering metadata，因此不写该 key。
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
        val isHevc = mime.equals("video/hevc", ignoreCase = true) ||
                lower.contains("hevc") || lower.contains("h265")
        log("[CONFIG] Codec=$codecName, mime=$mime, isVideo=$isVideo, isHevc=$isHevc, hdrMode=$hdrMode")
        if (!isVideo) return

        // HDR 需要 HEVC + 10-bit profile；不满足条件时回退 SDR，避免写出“PQ 标记的 8-bit SDR”
        var hdrActive = hdrMode != HDR_MODE_OFF
        if (hdrActive && !isHevc) {
            log("[CONFIG] HDR 仅支持 HEVC，当前不是 HEVC 编码器，回退 SDR 色彩")
            hdrActive = false
        }
        var profile: Int? = null
        if (hdrActive) {
            profile = pickHdrProfile(codec, mime, hdrMode)
            if (profile == null) {
                log("[CONFIG] 编码器未暴露 10-bit（Main10）能力，回退 SDR 色彩")
                hdrActive = false
            }
        }

        // HDR 开启时强制 BT.2020 原色与 HLG/PQ 传输特性，覆盖 UI 的 SDR 选择；
        // HDR 信号按规范使用 limited range，即使 UI 选了 Full 也强制改回 limited。
        val effStandard = if (hdrActive) COLOR_STANDARD_BT2020 else colorStandard
        val effTransfer = when {
            !hdrActive -> colorTransfer
            hdrMode == HDR_MODE_HDR10 -> TRANSFER_ST2084
            else -> TRANSFER_HLG
        }
        val effRange = if (hdrActive) COLOR_RANGE_LIMITED else colorRange
        try {
            format.setInteger(KEY_COLOR_STANDARD, effStandard)
            format.setInteger(KEY_COLOR_RANGE, effRange)
            format.setInteger(KEY_COLOR_TRANSFER, effTransfer)

            if (hdrActive) {
                format.setInteger(KEY_PROFILE, profile!!)
                if (hdrMode == HDR_MODE_HDR10) {
                    val staticInfo = buildHdrStaticInfo()
                    format.setByteBuffer(KEY_HDR_STATIC_INFO, staticInfo)
                    log("[CONFIG] 注入 profile=0x${profile.toString(16)} + " +
                            "hdr-static-info=${staticInfo.remaining()}B (BT.2020/PQ, " +
                            "${HDR_MAX_DISPLAY_LUMINANCE}/${HDR_MIN_DISPLAY_LUMINANCE} nit, " +
                            "CLL=$HDR_MAX_CONTENT_LIGHT_LEVEL/$HDR_MAX_FRAME_AVERAGE_LIGHT_LEVEL)")
                } else {
                    log("[CONFIG] 注入 profile=0x${profile.toString(16)} (BT.2020/HLG；" +
                            "HLG 不使用 mastering metadata，跳过 hdr-static-info)")
                }
            }
            log("[CONFIG] 已注入色彩参数 (standard=$effStandard, range=$effRange, " +
                    "transfer=$effTransfer, hdr=$hdrActive)")
        } catch (t: Throwable) {
            log("[CONFIG] 注入色彩参数失败，硬件可能不支持: ${t.message}")
        }
    }

    /**
     * 查询编码器能力，选出 10-bit profile。
     * - HDR10(PQ)：优先 Main10HDR10(0x1000)，回退 Main10(0x2)；
     * - HLG：使用 Main10(0x2) + transfer=HLG，HEVC 没有独立的 HLG profile
     *   （0x2000 是 HDR10+，不能用于 HLG）。
     * 注意：API 37 已移除 MediaCodecInfo.getCapabilities()，须用 getCapabilitiesForType(mime)。
     */
    private fun pickHdrProfile(codec: MediaCodec?, mime: String, mode: Int): Int? {
        val profiles = mutableSetOf<Int>()
        var caps: MediaCodecInfo.CodecCapabilities? = null
        try {
            caps = codec?.codecInfo?.getCapabilitiesForType(mime)
            if (caps != null) {
                for (pl in caps.profileLevels) {
                    profiles.add(pl.profile)
                }
            }
        } catch (t: Throwable) {
            log("[CAPS] 读取编码器能力失败: ${t.message}")
        }
        log("[CAPS] $mime 支持的 profiles=$profiles")

        if (caps != null) {
            val feature = if (mode == HDR_MODE_HDR10) {
                MediaCodecInfo.CodecCapabilities.FEATURE_HdrEditing
            } else {
                MediaCodecInfo.CodecCapabilities.FEATURE_HlgEditing
            }
            log("[CAPS] $mime $feature=${caps.isFeatureSupported(feature)}")
        }

        val want = if (mode == HDR_MODE_HDR10) PROFILE_MAIN10_HDR10 else PROFILE_MAIN10
        return when {
            profiles.contains(want) -> want
            profiles.contains(PROFILE_MAIN10) -> PROFILE_MAIN10
            else -> null
        }
    }

    /**
     * 构造 25 字节的 CTA-861.3 Static Metadata Descriptor（小端）。
     *
     * 布局（与 AOSP MPEG4Writer::writeMdcvAndClliBoxes 一致）：
     *   [0]      descriptor ID（0）
     *   [1..12]  R/G/B 原色 x,y（每项 uint16，单位 1/50000）
     *   [13..16] 白点 x,y（单位 1/50000）
     *   [17]     峰值亮度（uint16，单位 1 nit）
     *   [19]     黑位亮度（uint16；MediaMuxer 按 1 nit 解释）
     *   [21]     MaxCLL（uint16，单位 1 nit）
     *   [23]     MaxFALL（uint16，单位 1 nit）
     *
     * 注意：长度必须恰好 25，否则 MediaMuxer 只打印 “Ignoring HDR static info with
     * unexpected size”，不会写 mdcv/clli。
     */
    private fun buildHdrStaticInfo(): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(HDR_STATIC_INFO_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(0.toByte())
        putChromaticity(buffer, 0.708, 0.292)   // R
        putChromaticity(buffer, 0.170, 0.797)   // G
        putChromaticity(buffer, 0.131, 0.046)   // B
        putChromaticity(buffer, 0.3127, 0.3290) // D65
        buffer.putShort(HDR_MAX_DISPLAY_LUMINANCE.toShort())
        buffer.putShort(HDR_MIN_DISPLAY_LUMINANCE.toShort())
        buffer.putShort(HDR_MAX_CONTENT_LIGHT_LEVEL.toShort())
        buffer.putShort(HDR_MAX_FRAME_AVERAGE_LIGHT_LEVEL.toShort())
        buffer.flip()
        return buffer
    }

    private fun putChromaticity(buffer: ByteBuffer, x: Double, y: Double) {
        buffer.putShort((x * 50_000.0).roundToInt().toShort())
        buffer.putShort((y * 50_000.0).roundToInt().toShort())
    }

    // =============================================
    // Hook DisplayManager.createVirtualDisplay（所有重载）
    // =============================================
    private fun hookDisplayManagerCreateVirtualDisplay() {
        try {
            var hooked = 0
            for (m in DisplayManager::class.java.declaredMethods) {
                if (m.name != "createVirtualDisplay") continue
                try {
                    hookSizeCall(m, "DM createVD")
                    hooked++
                    log("[+] DisplayManager.createVirtualDisplay(${m.parameterTypes.joinToString(",") { it.simpleName }}) hooked")
                } catch (t: Throwable) {
                    log("[!] Failed to hook DM.createVD: ${t.message}")
                }
            }
            if (hooked == 0) log("[!] DisplayManager.createVirtualDisplay: 未找到可用重载")
        } catch (t: Throwable) {
            log("hookDisplayManagerCreateVirtualDisplay FAILED", t)
        }
    }

    /**
     * Hook DisplayManagerGlobal：VirtualDisplay 最终由它创建，
     * App 绕过 DisplayManager 直接调用时同样能拦截。
     */
    private fun hookDisplayManagerGlobalCreateVirtualDisplay() {
        try {
            val cls = Class.forName("android.hardware.display.DisplayManagerGlobal")
            var hooked = 0
            for (m in cls.declaredMethods) {
                if (m.name != "createVirtualDisplay") continue
                try {
                    hookSizeCall(m, "DMG createVD")
                    hooked++
                    log("[+] DisplayManagerGlobal.createVirtualDisplay(${m.parameterTypes.joinToString(",") { it.simpleName }}) hooked")
                } catch (t: Throwable) {
                    log("[!] Failed to hook DMG.createVD: ${t.message}")
                }
            }
            if (hooked == 0) log("[!] DisplayManagerGlobal.createVirtualDisplay: 未找到可用重载")
        } catch (t: Throwable) {
            log("hookDisplayManagerGlobalCreateVirtualDisplay FAILED", t)
        }
    }

    /**
     * Hook VirtualDisplayConfig(.Builder) 的 (name, width, height, dpi, ...) 构造器。
     * 无论 App 走新旧 API，配置对象最终都由该构造器创建，直接改参数最可靠
     * （不受 final 字段无法反射赋值的影响）。
     */
    private fun hookVirtualDisplayConfigConstructors() {
        val names = arrayOf(
            "android.hardware.display.VirtualDisplayConfig\$Builder",
            "android.hardware.display.VirtualDisplayConfig"
        )
        for (cn in names) {
            val cls = try {
                Class.forName(cn)
            } catch (t: Throwable) {
                log("[!] $cn 不可用: ${t.message}")
                continue
            }
            for (c in cls.declaredConstructors) {
                val params = c.parameterTypes
                // (String name, int width, int height, int dpi, ...)
                if (params.size < 4) continue
                if (params[0] != String::class.java) continue
                if (params[1] != Int::class.javaPrimitiveType || params[2] != Int::class.javaPrimitiveType) continue
                try {
                    hookSizeCall(c, "VDC(${cls.simpleName})")
                    log("[+] $cn(${params.joinToString(",") { it.simpleName }}) hooked")
                } catch (t: Throwable) {
                    log("[!] Failed to hook $cn constructor: ${t.message}")
                }
            }
        }
    }

    /** 对方法/构造器安装尺寸改写 Hook（参数复制后 proceed，避免修改不可变参数表）。 */
    private fun hookSizeCall(target: Executable, tag: String) {
        deoptimize(target)
        val params = target.parameterTypes
        hook(target).intercept { chain ->
            val args = chain.args.toTypedArray()
            try {
                rewriteSizeArgs(args, params, tag)
            } catch (t: Throwable) {
                log("[$tag CALLBACK ERROR]", t)
            }
            // 只 proceed 一次
            chain.proceed(args)
        }
    }

    /**
     * 改写一次调用中的尺寸：
     * 1. 按签名中 int 参数的顺序两两配对，识别 (width, height) 并映射；
     * 2. 对 VirtualDisplayConfig 参数做字段级改写兜底（覆盖无法改参数的路径）。
     */
    private fun rewriteSizeArgs(args: Array<Any?>, params: Array<Class<*>>, tag: String) {
        val intIdx = params.indices.filter { params[it] == Int::class.javaPrimitiveType }
        var applied = false
        for (a in intIdx.indices) {
            if (applied) break
            for (b in intIdx.indices) {
                if (applied || a >= b) continue
                val w = args[intIdx[a]] as? Int ?: continue
                val h = args[intIdx[b]] as? Int ?: continue
                val mapped = mapSize(w, h) ?: continue
                args[intIdx[a]] = mapped.first
                args[intIdx[b]] = mapped.second
                log("[HOOK $tag] ${w}x${h} -> ${mapped.first}x${mapped.second}")
                applied = true
            }
        }
        if (!applied && intIdx.size >= 2) {
            log("[SIZE $tag] 未命中: ints=${intIdx.map { args[it] }}")
        }
        for (i in args.indices) {
            val arg = args[i] ?: continue
            if (arg.javaClass.name.contains("VirtualDisplayConfig")) {
                modifyIntFields(arg, "$tag.config$i")
            }
        }
    }

    private fun hookVirtualDisplayResize() {
        try {
            val vdClass = Class.forName("android.hardware.display.VirtualDisplay")
            for (m in vdClass.declaredMethods) {
                if (m.name != "resize") continue
                hookSizeCall(m, "VD.resize")
                log("[+] VirtualDisplay.resize hooked")
            }
        } catch (t: Throwable) {
            log("[!] VirtualDisplay.resize hook failed: ${t.message}")
        }
    }

    // =============================================
    // Hook 应用层 VirtualDisplay 包装类
    // 旧版本类名为 i4.u，17.5.4 是 z5.u；混淆名随版本变化，因此先试已知名，
    // 未命中再扫描 dex 中的短类名逐个检查。
    // =============================================
    private fun hookAppWrapperClasses(classLoader: ClassLoader) {
        var hooked = 0
        for (name in KNOWN_WRAPPER_CLASSES) {
            val cls = try {
                classLoader.loadClass(name)
            } catch (t: Throwable) {
                null
            } ?: continue
            val count = hookWrapperMethods(cls)
            hooked += count
            log("[i4] 已知类名 $name 命中，hook $count 个方法")
        }
        if (hooked > 0) return

        val start = System.currentTimeMillis()
        val candidates = shortClassNames(classLoader)
        var loaded = 0
        for (name in candidates) {
            val cls = try {
                classLoader.loadClass(name)
            } catch (t: Throwable) {
                continue
            }
            loaded++
            hooked += hookWrapperMethods(cls)
        }
        log("[i4] 应用层类扫描完成：候选 ${candidates.size}，加载 $loaded，hook $hooked 个方法，" +
                "耗时 ${System.currentTimeMillis() - start}ms")
    }

    /** hook 类中所有「首参为 MediaProjection」的方法（即录屏配置入口）。 */
    private fun hookWrapperMethods(cls: Class<*>): Int {
        var hooked = 0
        for (m in cls.declaredMethods) {
            val params = m.parameterTypes
            if (params.isEmpty()) continue
            if (params[0].name != "android.media.projection.MediaProjection") continue
            val tag = "${cls.name}.${m.name}"
            val sig = params.joinToString(",") { it.simpleName }
            try {
                deoptimize(m)
                hook(m).intercept { chain ->
                    try {
                        log("[$tag] CALLED ($sig)")
                        for (i in chain.args.indices) {
                            val arg = chain.args[i] ?: continue
                            val cn = arg.javaClass.name
                            // 跳过框架/JDK 类型（Surface、MediaProjection、Callback、Handler、String 等）
                            if (cn.startsWith("android.") || cn.startsWith("java.") ||
                                cn.startsWith("kotlin.") || cn.startsWith("androidx.")
                            ) continue
                            modifyIntFields(arg, "$tag.arg$i")
                        }
                    } catch (t: Throwable) {
                        log("[$tag CALLBACK ERROR]", t)
                    }
                    // 只 proceed 一次：异常后再次 proceed 会重复执行目标方法
                    chain.proceed()
                }
                hooked++
                log("[+] $tag($sig) hooked")
            } catch (t: Throwable) {
                log("[!] Failed to hook $tag: ${t.message}")
            }
        }
        return hooked
    }

    /**
     * 从 App 的 APK dex 中提取混淆后的短类名（如 i4.u、d4.a）。
     * 限制段数与每段长度，避免加载大量完整包名类拖慢启动。
     */
    private fun shortClassNames(cl: ClassLoader): List<String> {
        val result = LinkedHashSet<String>()
        try {
            val baseDex = Class.forName("dalvik.system.BaseDexClassLoader")
            val pathListField = baseDex.getDeclaredField("pathList").apply { isAccessible = true }
            val pathList = pathListField.get(cl) ?: return emptyList()
            val elementsField = pathList.javaClass.getDeclaredField("dexElements").apply { isAccessible = true }
            val elements = elementsField.get(pathList) as? Array<*> ?: return emptyList()
            for (element in elements) {
                element ?: continue
                val rawPath = try {
                    val pathField = element.javaClass.getDeclaredField("path").apply { isAccessible = true }
                    pathField.get(element)
                } catch (t: Throwable) {
                    null
                }
                // Android 14+ DexPathList$Element.path 是 java.io.File（旧版本是 String）
                val path = when (rawPath) {
                    is String -> rawPath
                    is File -> rawPath.absolutePath
                    else -> null
                } ?: continue
                val file = File(path)
                if (!file.isFile) continue
                val dex = try {
                    DexFile(file)
                } catch (t: Throwable) {
                    log("[i4] 解析 ${file.name} 失败: ${t.message}")
                    continue
                }
                try {
                    val entries = dex.entries()
                    while (entries.hasMoreElements() && result.size < MAX_SCAN_CLASSES) {
                        val name = entries.nextElement()
                        if (isShortClassName(name)) result.add(name)
                    }
                } finally {
                    try { dex.close() } catch (_: Throwable) {}
                }
            }
        } catch (t: Throwable) {
            log("[i4] 读取 dexElements 失败: ${t.message}")
        }
        return result.toList()
    }

    /** 类名是否像 R8 混淆产物：2~3 段，且每段不超过 3 个字符。 */
    private fun isShortClassName(name: String): Boolean {
        val parts = name.split('.')
        if (parts.size < 2 || parts.size > 3) return false
        return parts.all { it.isNotEmpty() && it.length <= 3 }
    }


    private fun modifyIntFields(obj: Any?, tag: String) {
        if (obj == null || !screenSize.isValid) return
        try {
            val ints = ArrayList<Pair<Field, Int>>()
            var cls: Class<*>? = obj.javaClass
            while (cls != null && cls != Any::class.java) {
                for (f in cls.declaredFields) {
                    try {
                        f.isAccessible = true
                        if (f.type == Int::class.javaPrimitiveType) ints.add(f to f.getInt(obj))
                    } catch (_: Throwable) {}
                }
                cls = cls.superclass
            }
            if (ints.size < 2) return

            // 先确认对象里存在符合录屏比例的宽高对，再按值同步改写（宽高可能被多处缓存）
            val (wValue, hValue) = findSourcePair(ints) ?: return
            val (newW, newH) = toTarget(wValue, hValue)
            if (newW == wValue && newH == hValue) return

            for ((field, value) in ints) {
                val mapped = when (value) {
                    wValue -> newW
                    hValue -> newH
                    else -> continue
                }
                if (mapped == value) continue
                field.setInt(obj, mapped)
                log("[HOOK $tag.${field.name}] $value -> $mapped")
            }
        } catch (t: Throwable) { log("modifyIntFields $tag failed", t) }
    }

    /** 在 int 字段中寻找可改写的 (宽, 高) 值对。 */
    private fun findSourcePair(ints: List<Pair<Field, Int>>): Pair<Int, Int>? {
        // 优先匹配编码器侧捕获到的真实请求尺寸，避免误改对象里可能存在的屏幕尺寸参考字段
        findPair(ints) { w, h -> isKnownSourceSize(w, h) }?.let { return it }
        // 其次按“短边档位 × 屏幕比例”兜底识别，且优先于“与屏幕尺寸相同”的参考字段
        findPair(ints) { w, h ->
            ScreenResolution.isRecordingPreset(w, h, screenSize, displaySize) && toTarget(w, h) != (w to h)
        }?.let { return it }
        // 最后才接受屏幕尺寸本身（部分机型按原生分辨率录制）
        return findPair(ints) { w, h ->
            ScreenResolution.isSourceSize(w, h, screenSize, displaySize) && toTarget(w, h) != (w to h)
        }
    }

    private fun findPair(
        ints: List<Pair<Field, Int>>,
        predicate: (Int, Int) -> Boolean
    ): Pair<Int, Int>? {
        for (i in ints.indices) {
            for (j in ints.indices) {
                if (i == j) continue
                val w = ints[i].second
                val h = ints[j].second
                if (predicate(w, h)) return w to h
            }
        }
        return null
    }

    /** (w, h) 是否就是编码器侧捕获到的录屏请求尺寸（含横竖屏）。 */
    private fun isKnownSourceSize(w: Int, h: Int): Boolean =
        sourceShort > 0 &&
                ((w == sourceShort && h == sourceLong) || (w == sourceLong && h == sourceShort))

    /** 按 (w, h) 的横竖方向把源尺寸换算为目标尺寸。 */
    private fun toTarget(w: Int, h: Int): Pair<Int, Int> =
        if (w <= h) targetShort to targetLong else targetLong to targetShort

    /**
     * 计算本次进程使用的屏幕尺寸与目标尺寸（竖屏基准）。
     *
     * 目标尺寸取用户在模块 UI 中配置的分辨率，未配置或非法时回退到面板物理分辨率。
     */
    private fun resolveResolution(prefWidth: Int, prefHeight: Int) {
        val native = ScreenResolution.nativeSize(null)
        val current = ScreenResolution.currentSize()
        val screen = if (native.isValid) native else current

        screenSize = screen
        displaySize = current

        val configured = prefWidth > 0 && prefHeight > 0 &&
                ScreenResolution.validate(prefWidth, prefHeight, screen) == null
        val target = if (configured) ScreenResolution.normalize(prefWidth, prefHeight) else screen

        targetShort = target.shortSide
        targetLong = target.longSide
        log("Resolution: screen=${screen.shortSide}x${screen.longSide}, " +
                "pref=${prefWidth}x$prefHeight, target=${targetShort}x$targetLong")
    }

    /**
     * 识别录屏请求尺寸并按方向映射到目标分辨率。
     * 不是录屏尺寸或映射后无变化时返回 null（表示无需改写）。
     */
    private fun mapSize(w: Int, h: Int): Pair<Int, Int>? {
        if (!screenSize.isValid) return null
        if (!isKnownSourceSize(w, h) && !ScreenResolution.isSourceSize(w, h, screenSize, displaySize)) return null
        val mapped = toTarget(w, h)
        return if (mapped.first == w && mapped.second == h) null else mapped
    }
}
