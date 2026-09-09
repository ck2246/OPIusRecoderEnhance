package com.kai.oplusrecorder

import android.graphics.Point
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
    }

    // 从模块 UI 读取的用户设置
    private var videoBitrate: Int = 30_000_000
    private var audioBitrate: Int = 320_000

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
            audioBitrate = prefs.getInt("audio_bitrate", 320_000)
            log("Settings: videoBitrate=$videoBitrate, audioBitrate=$audioBitrate")
        } catch (t: Throwable) {
            log("Failed to read settings, using defaults", t)
        }

        // VirtualDisplay 分辨率 hook（在所有进程中安装）
        hookVirtualDisplayConfigBuilder()  // 新 API：Builder 模式（主要路径）
        hookVirtualDisplay()               // 旧 API：8 参数直接传宽高（兼容）
        hookDisplayManagerCreateVirtualDisplay()  // DisplayManager 路径（兼容）
        hookDisplayManagerGlobal()         // DisplayManagerGlobal 最终汇聚点
        hookMediaProjectionAll()           // MediaProjection 所有方法诊断

        // Display 分辨率查询 hook（从源头修改应用看到的分辨率）
        hookDisplayGetMetrics()

        if (isMainProcess) {
            // 主进程：构建 MediaFormat 的地方，setInteger/createVD 在这里调用
            log("========== HOOKING MAIN PROCESS ==========")
            hookMediaFormatSetInteger()
            hookMediaFormatSetFloat()
            hookMediaFormatSetString()
            hookMediaCodecConfigureLog()
        } else {
            // 子进程：只记录信息
            log("Child process: $processSuffix - functional hooks limited to main process")
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

    // =============================================
    // Hook MediaFormat.setFloat(String, float)
    // 排查：应用是否用 setFloat 设置分辨率
    // =============================================
    private fun hookMediaFormatSetFloat() {
        try {
            val setFloat = MediaFormat::class.java.getDeclaredMethod(
                "setFloat", String::class.java, Float::class.javaPrimitiveType
            )

            deoptimize(setFloat)

            hook(setFloat).intercept { chain ->
                try {
                    val key = chain.args[0] as String
                    val value = chain.args[1] as Float
                    log("[setFloat] key=$key, value=$value")
                    chain.proceed(arrayOf(chain.args[0], value))
                } catch (t: Throwable) {
                    log("[setFloat CALLBACK ERROR]", t)
                    chain.proceed()
                }
            }

            log("[+] MediaFormat.setFloat hook installed")

        } catch (t: Throwable) {
            log("HOOK setFloat FAILED", t)
        }
    }

    // =============================================
    // Hook MediaFormat.setString(String, String)
    // 排查：应用是否用 setString 设置 mime 等关键参数
    // =============================================
    private fun hookMediaFormatSetString() {
        try {
            val setString = MediaFormat::class.java.getDeclaredMethod(
                "setString", String::class.java, String::class.java
            )

            deoptimize(setString)

            hook(setString).intercept { chain ->
                try {
                    val key = chain.args[0] as String
                    val value = chain.args[1] as String
                    log("[setString] key=$key, value=$value")
                    chain.proceed(arrayOf(chain.args[0], value))
                } catch (t: Throwable) {
                    log("[setString CALLBACK ERROR]", t)
                    chain.proceed()
                }
            }

            log("[+] MediaFormat.setString hook installed")

        } catch (t: Throwable) {
            log("HOOK setString FAILED", t)
        }
    }

    /**
     * 计算修改后的值，返回 null 表示不需要修改
     */
    private fun computeNewValue(key: String, value: Int): Int? {
        if (key == "width" && value == ORIG_W) return TARGET_W
        if (key == "height" && value == ORIG_H) return TARGET_H
        if (key == "bitrate" && value > 1_000_000) return videoBitrate
        if (key == "bitrate" && value < 1_000_000) return audioBitrate
        return null
    }

    // =============================================
    // Hook Display.getMetrics / getRealMetrics
    // 从源头修改应用看到的屏幕分辨率
    // 应用查询屏幕尺寸后用它创建 VirtualDisplayConfig.Builder
    // =============================================
    private fun hookDisplayGetMetrics() {
        try {
            val displayClass = Display::class.java

            // getMetrics(Point)
            try {
                val getMetrics = displayClass.getDeclaredMethod("getMetrics", Point::class.java)
                deoptimize(getMetrics)
                hook(getMetrics).intercept { chain ->
                    try {
                        chain.proceed()
                        val point = chain.args[0] as Point
                        log("[Display.getMetrics] ${point.x}x${point.y}")
                        if (point.x == ORIG_W && point.y == ORIG_H) {
                            point.x = TARGET_W
                            point.y = TARGET_H
                            log("[HOOK Display.getMetrics] ${ORIG_W}x${ORIG_H} -> ${TARGET_W}x${TARGET_H}")
                        } else if (point.x == ORIG_H && point.y == ORIG_W) {
                            point.x = TARGET_H
                            point.y = TARGET_W
                            log("[HOOK Display.getMetrics] ${ORIG_H}x${ORIG_W} -> ${TARGET_H}x${TARGET_W}")
                        }
                    } catch (t: Throwable) {
                        log("[Display.getMetrics CALLBACK ERROR]", t)
                        chain.proceed()
                    }
                }
                log("[+] Display.getMetrics hook installed")
            } catch (t: Throwable) {
                log("[!] Display.getMetrics hook failed: ${t.message}")
            }

            // getRealMetrics(Point)
            try {
                val getRealMetrics = displayClass.getDeclaredMethod("getRealMetrics", Point::class.java)
                deoptimize(getRealMetrics)
                hook(getRealMetrics).intercept { chain ->
                    try {
                        chain.proceed()
                        val point = chain.args[0] as Point
                        log("[Display.getRealMetrics] ${point.x}x${point.y}")
                        if (point.x == ORIG_W && point.y == ORIG_H) {
                            point.x = TARGET_W
                            point.y = TARGET_H
                            log("[HOOK Display.getRealMetrics] ${ORIG_W}x${ORIG_H} -> ${TARGET_W}x${TARGET_H}")
                        } else if (point.x == ORIG_H && point.y == ORIG_W) {
                            point.x = TARGET_H
                            point.y = TARGET_W
                            log("[HOOK Display.getRealMetrics] ${ORIG_H}x${ORIG_W} -> ${TARGET_H}x${TARGET_W}")
                        }
                    } catch (t: Throwable) {
                        log("[Display.getRealMetrics CALLBACK ERROR]", t)
                        chain.proceed()
                    }
                }
                log("[+] Display.getRealMetrics hook installed")
            } catch (t: Throwable) {
                log("[!] Display.getRealMetrics hook failed: ${t.message}")
            }

        } catch (t: Throwable) {
            log("hookDisplayGetMetrics FAILED", t)
        }
    }

    // =============================================
    // 核心分辨率 hook：VirtualDisplayConfig.Builder
    // 策略：Hook 所有构造函数 + build() 方法
    // =============================================
    private fun hookVirtualDisplayConfigBuilder() {
        try {
            val builderClass = Class.forName(
                "android.hardware.display.VirtualDisplayConfig\$Builder"
            )
            val configClass = Class.forName(
                "android.hardware.display.VirtualDisplayConfig"
            )

            // ===== 诊断：输出所有构造函数签名 =====
            log("[VD Builder] Constructors:")
            for (c in builderClass.declaredConstructors) {
                log("  ctor: ${c.parameterTypes.joinToString(", ") { it.simpleName }}")
            }
            // ===== 诊断：输出 Builder 所有方法 =====
            log("[VD Builder] Methods:")
            for (m in builderClass.declaredMethods) {
                log("  method: ${m.name}(${m.parameterTypes.joinToString(", ") { it.simpleName }}) -> ${m.returnType.simpleName}")
            }
            // ===== 诊断：输出 VirtualDisplayConfig 字段 =====
            log("[VD Config] Fields:")
            for (f in configClass.declaredFields) {
                log("  field: ${f.type.simpleName} ${f.name} (final=${java.lang.reflect.Modifier.isFinal(f.modifiers)})")
            }

            // ===== 1. Hook ALL 构造函数 =====
            for (c in builderClass.declaredConstructors) {
                try {
                    deoptimize(c)
                    hook(c).intercept { chain ->
                        try {
                            val args = chain.args
                            val paramTypes = c.parameterTypes
                            log("[VD Builder ctor] called with ${args.size} args, types: ${paramTypes.joinToString(", ") { it.simpleName }}")
                            for (i in args.indices) {
                                log("  arg[$i] = ${args[i]} (${args[i]?.javaClass?.simpleName})")
                            }

                            // 找所有 int 参数，尝试匹配 width/height
                            val intIndices = paramTypes.indices.filter { params ->
                                paramTypes[params] == Int::class.javaPrimitiveType
                            }
                            if (intIndices.size >= 2) {
                                val wIdx = intIndices[0]
                                val hIdx = intIndices[1]
                                val w = args[wIdx] as Int
                                val h = args[hIdx] as Int
                                log("[VD Builder ctor] w=$w, h=$h at idx [$wIdx,$hIdx]")

                                if (w == ORIG_W && h == ORIG_H) {
                                    args[wIdx] = TARGET_W
                                    args[hIdx] = TARGET_H
                                    log("[HOOK VD Builder ctor] ${w}x${h} -> ${TARGET_W}x${TARGET_H}")
                                } else if (w == ORIG_H && h == ORIG_W) {
                                    args[wIdx] = TARGET_H
                                    args[hIdx] = TARGET_W
                                    log("[HOOK VD Builder ctor] ${w}x${h} -> ${TARGET_H}x${TARGET_W}")
                                }
                            }

                            chain.proceed(args.toTypedArray())
                        } catch (t: Throwable) {
                            log("[VD Builder ctor CALLBACK ERROR]", t)
                            chain.proceed()
                        }
                    }
                    log("[+] Hooked VD Builder ctor: ${c.parameterTypes.joinToString(", ") { it.simpleName }}")
                } catch (t: Throwable) {
                    log("[!] Failed to hook VD Builder ctor: ${t.message}")
                }
            }

            // ===== 2. Hook build() 方法 - 修改返回的 config =====
            try {
                val buildMethod = builderClass.getDeclaredMethod("build")
                deoptimize(buildMethod)
                hook(buildMethod).intercept { chain ->
                    try {
                        val config = chain.proceed()
                        log("[VD Builder build()] config=$config")

                        // 尝试通过反射修改 config 的 width/height
                        try {
                            val wField = configClass.getDeclaredField("mWidth")
                            val hField = configClass.getDeclaredField("mHeight")
                            wField.isAccessible = true
                            hField.isAccessible = true

                            val oldW = wField.getInt(config)
                            val oldH = hField.getInt(config)
                            log("[VD Builder build()] config size: ${oldW}x${oldH}")

                            if (oldW == ORIG_W && oldH == ORIG_H) {
                                wField.setInt(config, TARGET_W)
                                hField.setInt(config, TARGET_H)
                                log("[HOOK VD Builder build()] ${oldW}x${oldH} -> ${TARGET_W}x${TARGET_H}")
                            } else if (oldW == ORIG_H && oldH == ORIG_W) {
                                wField.setInt(config, TARGET_H)
                                hField.setInt(config, TARGET_W)
                                log("[HOOK VD Builder build()] ${oldW}x${oldH} -> ${TARGET_H}x${TARGET_W}")
                            }
                        } catch (e: Throwable) {
                            log("[VD Builder build()] reflection failed: ${e.message}")
                            // 尝试其他字段名
                            for (f in configClass.declaredFields) {
                                if (f.type == Int::class.javaPrimitiveType) {
                                    try {
                                        f.isAccessible = true
                                        log("  field '${f.name}' = ${f.getInt(config)}")
                                    } catch (_: Throwable) {}
                                }
                            }
                        }

                        config  // 返回（可能已修改的）config
                    } catch (t: Throwable) {
                        log("[VD Builder build() CALLBACK ERROR]", t)
                        chain.proceed()
                    }
                }
                log("[+] VirtualDisplayConfig.Builder.build() hook installed")
            } catch (t: Throwable) {
                log("[!] build() hook failed: ${t.message}")
            }

        } catch (t: Throwable) {
            log("hookVirtualDisplayConfigBuilder FAILED", t)
        }
    }

    // =============================================
    // Hook ALL MediaProjection.createVirtualDisplay overloads
    // 诊断：找出应用实际调用的重载
    // =============================================
    private fun hookMediaProjectionAll() {
        try {
            val mpClass = MediaProjection::class.java
            log("[MediaProjection] All createVirtualDisplay overloads:")
            for (m in mpClass.getDeclaredMethods()) {
                if (m.name == "createVirtualDisplay") {
                    log("  ${m.parameterTypes.joinToString(", ") { it.simpleName }}")
                }
            }

            // Hook 所有 createVirtualDisplay 重载
            for (m in mpClass.getDeclaredMethods()) {
                if (m.name != "createVirtualDisplay") continue
                val params = m.parameterTypes
                try {
                    deoptimize(m)
                    hook(m).intercept { chain ->
                        try {
                            val args = chain.args
                            log("[MP createVD] called with ${args.size} args: ${args.joinToString(", ")}")

                            // 找 int 参数中的 width/height
                            val intIndices = params.indices.filter { params[it] == Int::class.javaPrimitiveType }
                            if (intIndices.size >= 2) {
                                val wIdx = intIndices[0]
                                val hIdx = intIndices[1]
                                val w = args[wIdx] as Int
                                val h = args[hIdx] as Int
                                log("[MP createVD] w=$w, h=$h at idx [$wIdx,$hIdx]")

                                if (w == ORIG_W && h == ORIG_H) {
                                    args[wIdx] = TARGET_W
                                    args[hIdx] = TARGET_H
                                    log("[HOOK MP createVD] ${w}x${h} -> ${TARGET_W}x${TARGET_H}")
                                } else if (w == ORIG_H && h == ORIG_W) {
                                    args[wIdx] = TARGET_H
                                    args[hIdx] = TARGET_W
                                    log("[HOOK MP createVD] ${w}x${h} -> ${TARGET_H}x${TARGET_W}")
                                }
                            }

                            chain.proceed(args.toTypedArray())
                        } catch (t: Throwable) {
                            log("[MP createVD CALLBACK ERROR]", t)
                            chain.proceed()
                        }
                    }
                    log("[+] Hooked MP.createVirtualDisplay(${params.joinToString(", ") { it.simpleName }})")
                } catch (t: Throwable) {
                    log("[!] Failed to hook MP.createVD: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            log("hookMediaProjectionAll FAILED", t)
        }
    }

    // =============================================
    // Hook DisplayManagerGlobal.createVirtualDisplay
    // 所有 VD 创建最终汇聚到这里
    // =============================================
    private fun hookDisplayManagerGlobal() {
        try {
            val dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal")

            log("[DMG] All createVirtualDisplay overloads:")
            for (m in dmgClass.getDeclaredMethods()) {
                if (m.name == "createVirtualDisplay") {
                    log("  ${m.name}(${m.parameterTypes.joinToString(", ") { it.simpleName }})")
                }
            }

            for (m in dmgClass.getDeclaredMethods()) {
                if (m.name != "createVirtualDisplay") continue
                val params = m.parameterTypes
                try {
                    deoptimize(m)
                    hook(m).intercept { chain ->
                        try {
                            val args = chain.args
                            log("[DMG createVD] called with ${args.size} args")
                            for (i in args.indices) {
                                log("  arg[$i] = ${args[i]} (${args[i]?.javaClass?.simpleName})")
                            }

                            // 找 int 参数中的 width/height
                            val intIndices = params.indices.filter { params[it] == Int::class.javaPrimitiveType }
                            if (intIndices.size >= 2) {
                                val wIdx = intIndices[0]
                                val hIdx = intIndices[1]
                                val w = args[wIdx] as Int
                                val h = args[hIdx] as Int
                                log("[DMG createVD] w=$w, h=$h at idx [$wIdx,$hIdx]")

                                if (w == ORIG_W && h == ORIG_H) {
                                    args[wIdx] = TARGET_W
                                    args[hIdx] = TARGET_H
                                    log("[HOOK DMG createVD] ${w}x${h} -> ${TARGET_W}x${TARGET_H}")
                                } else if (w == ORIG_H && h == ORIG_W) {
                                    args[wIdx] = TARGET_H
                                    args[hIdx] = TARGET_W
                                    log("[HOOK DMG createVD] ${w}x${h} -> ${TARGET_H}x${TARGET_W}")
                                }
                            }

                            chain.proceed(args.toTypedArray())
                        } catch (t: Throwable) {
                            log("[DMG createVD CALLBACK ERROR]", t)
                            chain.proceed()
                        }
                    }
                    log("[+] Hooked DMG.createVirtualDisplay(${params.joinToString(", ") { it.simpleName }})")
                } catch (t: Throwable) {
                    log("[!] Failed to hook DMG.createVD: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            log("hookDisplayManagerGlobal FAILED", t)
        }
    }

    // =============================================
    // Hook MediaProjection.createVirtualDisplay (旧式 8/6 参数)
    // 兼容旧版 Android 直接传 int width/height 的路径
    // =============================================
    private fun hookVirtualDisplay() {
        try {
            val mpClass = MediaProjection::class.java
            val surfaceClass = Class.forName("android.view.Surface")
            val vdCallbackClass = Class.forName("android.hardware.display.VirtualDisplay\$Callback")
            val handlerClass = Class.forName("android.os.Handler")

            // 8-arg overload: (String, int, int, int, int, Surface, Callback, Handler)
            try {
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

                        log("[createVirtualDisplay(8)] BEFORE: ${w}x${h}")

                        if (w == ORIG_W && h == ORIG_H) {
                            args[1] = TARGET_W
                            args[2] = TARGET_H
                            log("[HOOK VD-8] ${w}x${h} -> ${TARGET_W}x${TARGET_H}")
                        } else if (w == ORIG_H && h == ORIG_W) {
                            args[1] = TARGET_H
                            args[2] = TARGET_W
                            log("[HOOK VD-8] ${w}x${h} -> ${TARGET_H}x${TARGET_W}")
                        }

                        chain.proceed(args.toTypedArray())
                    } catch (t: Throwable) {
                        log("[VD-8 CALLBACK ERROR]", t)
                        chain.proceed()
                    }
                }

                log("[+] VirtualDisplay 8-arg hook installed")
            } catch (_: Throwable) {}

        } catch (t: Throwable) {
            log("hookVirtualDisplay FAILED", t)
        }
    }

    // =============================================
    // Hook DisplayManager.createVirtualDisplay (兼容)
    // =============================================
    private fun hookDisplayManagerCreateVirtualDisplay() {
        try {
            val dmClass = Class.forName("android.hardware.display.DisplayManager")
            val surfaceClass = Class.forName("android.view.Surface")

            try {
                val createVD = dmClass.getDeclaredMethod(
                    "createVirtualDisplay",
                    String::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    surfaceClass,
                    Int::class.javaPrimitiveType
                )

                deoptimize(createVD)

                hook(createVD).intercept { chain ->
                    try {
                        val args = chain.args
                        val w = args[1] as Int
                        val h = args[2] as Int

                        log("[DisplayManager VD] BEFORE: ${w}x${h}")

                        if (w == ORIG_W && h == ORIG_H) {
                            args[1] = TARGET_W
                            args[2] = TARGET_H
                            log("[HOOK DM VD] ${w}x${h} -> ${TARGET_W}x${TARGET_H}")
                        } else if (w == ORIG_H && h == ORIG_W) {
                            args[1] = TARGET_H
                            args[2] = TARGET_W
                            log("[HOOK DM VD] ${w}x${h} -> ${TARGET_H}x${TARGET_W}")
                        }

                        chain.proceed(args.toTypedArray())
                    } catch (t: Throwable) {
                        log("[DM VD CALLBACK ERROR]", t)
                        chain.proceed()
                    }
                }

                log("[+] DisplayManager.createVirtualDisplay hook installed")
            } catch (_: Throwable) {}

        } catch (t: Throwable) {
            log("hookDisplayManagerCreateVirtualDisplay FAILED", t)
        }
    }

    // =============================================
    // Hook MediaCodec.configure (日志 + 查看最终 Format)
    // =============================================
    private fun hookMediaCodecConfigureLog() {
        try {
            val configure = MediaCodec::class.java.getDeclaredMethod(
                "configure",
                MediaFormat::class.java,
                Class.forName("android.view.Surface"),
                Class.forName("android.media.MediaCrypto"),
                Int::class.javaPrimitiveType
            )

            deoptimize(configure)

            hook(configure).intercept { chain ->
                try {
                    val format = chain.args[0] as? MediaFormat
                    val name = (chain.thisObject as MediaCodec).name

                    log("[configure] Codec: $name")
                    log("[configure] Format: $format")

                    chain.proceed()
                } catch (t: Throwable) {
                    log("[configure CALLBACK ERROR]", t)
                    chain.proceed()
                }
            }

            log("[+] MediaCodec.configure hook installed")

        } catch (t: Throwable) {
            log("HOOK configure FAILED", t)
        }
    }
}