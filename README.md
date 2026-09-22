# OPlus 录屏增强（OplusScreenRecorderEnhancer）

一个基于 **LSPosed / Xposed** 的模块，用于增强 OPPO / OnePlus 系统自带录屏应用（`com.oplus.screenrecorder`）的录制质量。

通过 Hook 目标应用的编码与虚拟显示器参数，实现原生录屏设置里**没有开放**的能力：

- **点对点分辨率**：将录制分辨率从 `1080 × 2354` 提升到屏幕物理分辨率 `1272 × 2772`，实现像素点对点的清晰画面。
- **自定义码率**：视频 / 音频码率自由配置（默认视频 30 Mbps、音频 288 kbps）。
- **色彩优化**：将色彩空间从默认的 BT.601 + Limited 改为 **BT.709 + Full Range**，色彩更准确、层次更丰富。

> 所有参数均可在模块自带的界面里配置，无需修改脚本或重新编译。

---

## 功能一览

| 功能 | 说明 | 默认值 | 是否可配置 |
| --- | --- | --- | --- |
| 分辨率 | 捕获与编码尺寸同步提升到屏幕物理分辨率 | `1272 × 2772`（固定） | 否（跟随设备屏幕） |
| 视频码率 | 单位 Mbps | 30 | 是 |
| 音频码率 | 单位 kbps | 288 | 是 |
| 色彩优化开关 | 关闭时完全保留 App 原始色彩参数 | 开启 | 是 |
| 色彩标准 | BT.709 / BT.601 / BT.2020 | BT.709 | 是 |
| 色彩范围 | Full（0-255）/ Limited（16-235） | Full | 是 |

---

## 环境要求

- 已 **Root** 的 Android 设备
- 已安装 **LSPosed**（或其它兼容 libxposed API 102 的框架）
- Android 10（API 29）及以上
- 目标应用：OPPO / OnePlus 系统录屏 `com.oplus.screenrecorder`

---

## 安装与使用

1. **构建 / 安装 APK**
   使用 Android Studio 打开本项目，或在项目根目录执行：

   ```bash
   ./gradlew assembleDebug
   ```

   产物位于 `app/build/outputs/apk/`。

2. **激活模块**
   在 LSPosed 管理器中启用本模块，并将作用域勾选为 **系统录屏（`com.oplus.screenrecorder`）**。

3. **配置参数**
   打开「OPlus 录屏增强」界面，设置视频 / 音频码率与色彩选项，点击 **保存设置**。

4. **重启目标应用使配置生效**
   点击右上角的 **重启图标**（⟳）。它会对 `com.oplus.screenrecorder` 执行 `am force-stop`。
   由于目标应用只在进程启动时读取一次配置，**修改码率 / 色彩后必须重启录屏进程才会生效**。
   > 首次点击会弹出超级用户授权框，请授予本模块 Root 权限。

5. **开始录屏**
   重新拉起系统录屏进行录制，即可得到增强后的视频。

---

## 工作原理

录屏管线：

```
屏幕内容 → VirtualDisplay（捕获尺寸） → Surface → MediaCodec 编码器（MediaFormat 尺寸 / 色彩） → 输出视频
```

模块在目标进程启动时（`onPackageReady`）安装一系列 Hook：

- **分辨率 / 码率**：Hook `MediaFormat.setInteger`、`MediaFormat.createVideoFormat`，将 width / height / bitrate 改写为目标值。
- **虚拟显示器捕获尺寸**：这是画面出现黑边的关键。若只改编码器尺寸而不改 VirtualDisplay 捕获尺寸，视频右侧 / 下侧会出现纯黑区域。为此模块：
  - Hook 应用层包装类 `i4.u`（App 自己的 `createVirtualDisplay` 封装），在其调用框架 API 前反射改写配置对象内的宽高字段——这是**主修复入口**；
  - 同时 Hook `MediaProjection.createVirtualDisplay`、`DisplayManager.createVirtualDisplay`（含 `VirtualDisplayConfig` 对象重载）、`VirtualDisplay.resize` 作为补充。
- **色彩注入**：Hook `MediaCodec.configure`，在编码器 configure 前对视频编码器强制注入 `color-standard` / `color-range` / `color-transfer` / `color-matrix`。即使 App 从不显式设置色彩参数，也能兜底生效。

> **为什么优先 Hook 应用层类而非框架方法？**
> 由 boot class loader 加载的框架方法（如 `MediaProjection.createVirtualDisplay`）经 PGO / AOT 优化后可能被内联或走 fast path，导致 Java 层 Hook 不触发；而应用自身代码（`i4.u`）始终可被 Xposed 拦截，因此更可靠。

---

## 项目结构

```
app/src/main/
├── java/com/kai/oplusrecorder/
│   ├── HookModule.kt      # Xposed 模块入口，所有 Hook 逻辑
│   ├── MainActivity.kt    # 配置界面（码率 / 色彩 / 保存 / 重启）
│   └── App.kt             # Application，RemoteSettings 远程配置桥接
├── res/layout/
│   └── activity_main.xml  # 界面布局
└── resources/META-INF/xposed/
    ├── java_init.list     # 模块入口类声明
    ├── scope.list         # 作用域（com.oplus.screenrecorder）
    └── module.prop        # 模块元信息（libxposed API 102）
```

### 技术栈

- **语言**：Kotlin
- **UI**：Android View（AppCompat + ConstraintLayout）
- **Hook 框架**：libxposed `api:102.0.0`（compileOnly）+ `service:102.0.0`（远程配置）
- **构建**：Gradle（Kotlin DSL），`compileSdk 37` / `minSdk 29` / `targetSdk 37`

---

## 调试

录制时可通过 logcat 过滤 TAG `OplusRecorderHook` 观察 Hook 是否命中：

```bash
adb logcat -c          # 清空旧日志
adb logcat | grep OplusRecorderHook
```

关键日志示例：

```
Settings: videoBitrate=..., audioBitrate=..., colorEnabled=true, colorStandard=1, colorRange=1
[i4.u.b] CALLED (MediaProjection,o,Surface,a,Callback,Handler)
[HOOK i4u.b.arg1.xxx] 1080 -> 1272
[CONFIG] Codec=...encoder.avc, mime=video/avc, isVideo=true
[CONFIG] 已注入色彩参数 (standard=1, range=1, transfer=3)
```

> 注意：`onPackageReady` 阶段打印的都是「Hook 安装」日志；带 `1080 -> 1272`、`已注入色彩参数` 等改值日志只在**真正开始录屏**时才会出现。

---

## 常见问题

- **视频右侧 / 下侧有黑边**：说明 VirtualDisplay 捕获尺寸未被改写，仅编码器尺寸生效。请确认作用域已勾选系统录屏，并检查 logcat 中 `i4.u` 相关 Hook 是否命中。
- **改了码率但没生效**：修改后必须点击重启图标 force-stop 目标应用，下次录屏才会加载新配置。
- **界面重新打开又变回默认值**：配置通过 `XposedService` 异步绑定读取，界面已用 `runWhenReady` 在绑定完成后回填；若仍异常，请确认 LSPosed 服务正常。
- **Full Range 画面偏暗 / 发灰**：部分播放器对 Full Range 兼容性不佳，可在界面里把色彩范围切回 Limited 对比。

---

## 免责声明

本项目仅供学习与研究使用。修改系统应用的录制参数可能因设备、系统版本差异而表现不同，请自行评估风险。
