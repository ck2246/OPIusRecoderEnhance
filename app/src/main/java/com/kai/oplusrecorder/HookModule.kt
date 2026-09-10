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
            log("Settings: videoBitrate=$videoBitrate, audioBitrate=$audioBitrate")//这里logcat有输出
        } catch (t: Throwable) {
            log("Failed to read settings, using defaults", t)
        }

        if (isMainProcess) {
            log("========== HOOKING MAIN PROCESS ==========")

            // ---- 诊断：枚举所有可用的 Hook 目标 ----
            diagnoseAvailableHooks(param.classLoader)

            // ---- 框架类 Hook（可能不触发，用于验证） ----
            hookMediaProjectionCreateVirtualDisplay()
            hookMediaFormatSetInteger()
            hookMediaFormatCreateVideoFormat()
            hookMediaCodecConfigureLog()
            hookDisplayGetMetrics()
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
    // 诊断：枚举应用内部类，寻找分辨率相关的 Hook 点
    // =============================================
    private fun diagnoseAvailableHooks(classLoader: ClassLoader) {
        // 1. 检查 e4.h 是否仍然存在（旧版 OPlus 混淆类）
        try {
            val cls = Class.forName("e4.h", false, classLoader)
            log("[DIAG] e4.h EXISTS, methods:")
            for (m in cls.declaredMethods) {
                log("  ${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }})")
            }
        } catch (_: Throwable) {
            log("[DIAG] e4.h NOT FOUND (class mapping may have changed)")
        }

        // 2. 扫描常见混淆包名，寻找含 int,int 参数的方法（可能是分辨率传入点）
        val suspectPackages = setOf("e4", "i4", "d4", "f4", "g4", "h4")
        for (pkg in suspectPackages) {
            try {
                // 尝试枚举 dex 中的类（通过已知类推断）
                val knownClasses = listOf(
                    "$pkg.h", "$pkg.o", "$pkg.u", "$pkg.a", "$pkg.b",
                    "$pkg.c", "$pkg.d", "$pkg.e", "$pkg.f", "$pkg.g"
                )
                for (cn in knownClasses) {
                    try {
                        val cls = Class.forName(cn, false, classLoader)
                        for (m in cls.declaredMethods) {
                            val params = m.parameterTypes
                            // 找含有两个连续 int 参数的方法（可能是 width, height）
                            for (i in 0 until params.size - 1) {
                                if (params[i] == Int::class.javaPrimitiveType &&
                                    params[i + 1] == Int::class.javaPrimitiveType
                                ) {
                                    log("[DIAG] $cn.${m.name} has (int,int) at [$i,${i + 1}]: ${params.joinToString(",") { it.simpleName }}")
                                }
                            }
                        }
                    } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
        }

        // 3. 检查 MediaProjection 的所有 createVirtualDisplay 重载
        try {
            val mpClass = MediaProjection::class.java
            log("[DIAG] MediaProjection.createVirtualDisplay overloads:")
            for (m in mpClass.getDeclaredMethods()) {
                if (m.name == "createVirtualDisplay") {
                    log("  (${m.parameterTypes.joinToString(",") { it.simpleName }})")
                }
            }
        } catch (t: Throwable) {
            log("[DIAG] MediaProjection scan failed: ${t.message}")
        }

        // 4. 检查 DisplayManager 的 createVirtualDisplay
        try {
            val dmClass = DisplayManager::class.java
            log("[DIAG] DisplayManager.createVirtualDisplay overloads:")
            for (m in dmClass.getDeclaredMethods()) {
                if (m.name == "createVirtualDisplay") {
                    log("  (${m.parameterTypes.joinToString(",") { it.simpleName }})")
                }
            }
        } catch (t: Throwable) {
            log("[DIAG] DisplayManager scan failed: ${t.message}")
        }

        // 在 diagnoseAvailableHooks 中添加
        try {
            val uCls = classLoader.loadClass("i4.u")
            log("[DIAG] i4.u EXISTS, methods:")
            for (m in uCls.declaredMethods) {
                val params = m.parameterTypes.joinToString { it.simpleName }
                log("  ${m.name}($params)")
            }
        } catch (_: Throwable) {
            log("[DIAG] i4.u NOT FOUND")
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
    // Hook Display.getMetrics / getRealMetrics
    // 从源头修改应用看到的屏幕分辨率
    // =============================================
    private fun hookDisplayGetMetrics() {
        try {
            val displayClass = Display::class.java

            // getMetrics(Point)
            try {
                val getMetrics = displayClass.getDeclaredMethod(
                    "getMetrics", android.graphics.Point::class.java
                )
                deoptimize(getMetrics)
                hook(getMetrics).intercept { chain ->
                    try {
                        chain.proceed()
                        val point = chain.args[0] as android.graphics.Point
                        log("[Display.getMetrics] ${point.x}x${point.y}")
                        if (point.x == ORIG_W && point.y == ORIG_H) {
                            point.x = TARGET_W
                            point.y = TARGET_H
                            log("[HOOK Display.getMetrics] -> ${TARGET_W}x${TARGET_H}")
                        } else if (point.x == ORIG_H && point.y == ORIG_W) {
                            point.x = TARGET_H
                            point.y = TARGET_W
                            log("[HOOK Display.getMetrics] -> ${TARGET_H}x${TARGET_W}")
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
                val getRealMetrics = displayClass.getDeclaredMethod(
                    "getRealMetrics", android.graphics.Point::class.java
                )
                deoptimize(getRealMetrics)
                hook(getRealMetrics).intercept { chain ->
                    try {
                        chain.proceed()
                        val point = chain.args[0] as android.graphics.Point
                        log("[Display.getRealMetrics] ${point.x}x${point.y}")
                        if (point.x == ORIG_W && point.y == ORIG_H) {
                            point.x = TARGET_W
                            point.y = TARGET_H
                            log("[HOOK Display.getRealMetrics] -> ${TARGET_W}x${TARGET_H}")
                        } else if (point.x == ORIG_H && point.y == ORIG_W) {
                            point.x = TARGET_H
                            point.y = TARGET_W
                            log("[HOOK Display.getRealMetrics] -> ${TARGET_H}x${TARGET_W}")
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
                                    dumpFields(arg, "dm.config$i")
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
                                dumpFields(arg, "i4u.$mName.arg$i")
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

    // =============================================
    // Hook MediaCodec.configure (日志 + 调用栈追踪)
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

                    // 打印调用栈，追踪分辨率是从哪里传入的
                    val stackTrace = Thread.currentThread().stackTrace
                        .filter { it.className.contains("oplus") || it.className.startsWith("e4.") || it.className.startsWith("i4.") || it.className.startsWith("d4.") }
                        .joinToString("\n") { "  at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})" }
                    if (stackTrace.isNotEmpty()) {
                        log("[configure] App call stack:\n$stackTrace")
                    }

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

    private fun dumpFields(obj: Any?, tag: String) {
        if (obj == null) { log("[$tag] null"); return }
        try {
            for (f in obj.javaClass.declaredFields) {
                try {
                    f.isAccessible = true
                    log("[$tag] ${f.name} (${f.type.simpleName}) = ${f.get(obj)}")
                } catch (_: Throwable) {}
            }
        } catch (t: Throwable) { log("dumpFields $tag failed", t) }
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
