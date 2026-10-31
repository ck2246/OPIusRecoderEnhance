# 录屏 / 截图增强（OplusScreenRecorderEnhancer）

一个基于 **LSPosed / Xposed** 的模块，同时增强 OPPO / OnePlus 的两个系统应用：

- **系统录屏** `com.oplus.screenrecorder` —— 录制分辨率、码率、色彩、HDR 10-bit；
- **系统截屏** `com.oplus.screenshot` —— 把默认的 JPEG 输出改为**无损 PNG**。

Hook 的目标都是目标应用里**原生设置没有开放**的能力：

| 子功能 | 目标应用 | 说明 |
| --- | --- | --- |
| 点对点分辨率 | 录屏 | 录制分辨率提升到屏幕物理分辨率 `1272 × 2772`（仅限一加15） |
| 自定义码率 | 录屏 | 视频 / 音频码率自由配置（默认视频 30 Mbps、音频 288 kbps） |
| 色彩优化 | 录屏 | BT.601 + Limited → **BT.709 + Full Range** |
| HDR / 10-bit 录制 | 录屏 | 可开启 **HLG** 或 **HDR10(PQ)**，注入 BT.2020 与 10-bit HEVC profile，相册标记为 **ProXDR** |
| **无损 PNG 截图** | 截屏 | 保存格式 JPEG(q=100) → **PNG**，文件名 / MIME / 编码三者一致 |

> 所有参数均可在模块自带的界面里配置，无需修改脚本或重新编译。

---

## 功能一览

### 录屏（com.oplus.screenrecorder）

| 功能 | 说明 | 默认值 | 是否可配置 |
| --- | --- | --- | --- |
| 分辨率 | 捕获与编码尺寸同步提升到屏幕物理分辨率 | `1272 × 2772`（固定） | 否（跟随设备屏幕） |
| 视频码率 | 单位 Mbps | 30 | 是 |
| 音频码率 | 单位 kbps | 288 | 是 |
| 色彩优化开关 | 关闭时完全保留 App 原始色彩参数 | 开启 | 是 |
| 色彩标准 | BT.709 / BT.601 / BT.2020 | BT.709 | 是 |
| 色彩范围 | Full（0-255）/ Limited（16-235） | Full | 是 |
| HDR 模式 | 关闭（SDR 8-bit）/ HLG 10-bit / HDR10（PQ）10-bit | 关闭 | 是 |

### 截图（com.oplus.screenshot）

| 功能 | 说明 | 默认值 | 是否可配置 |
| --- | --- | --- | --- |
| 无损 PNG | 系统截屏固定以 JPEG(q=100) 保存，开启后改为 PNG | 开启 | 是 |

> PNG 只解决 JPEG 的色度下采样 / 块效应，**不恢复位深**：若屏幕内容是 HDR/10-bit，PNG 依旧被压到 8-bit。

---

## 环境要求

- 已 **Root** 的 Android 设备
- 已安装 **LSPosed**（或其它兼容 libxposed API 102 的框架）
- Android 10（API 29）及以上
- 目标应用（按需勾选）：
  - 系统录屏 `com.oplus.screenrecorder`
  - 系统截屏 `com.oplus.screenshot`（系统应用，**需在 LSPosed 设置里打开「显示系统应用」**才会出现在作用域列表）

---

## 安装与使用

1. **构建 / 安装 APK**
   使用 Android Studio 打开本项目，或在项目根目录执行：

   ```bash
   ./gradlew assembleDebug
   ```

   产物位于 `app/build/outputs/apk/debug/`。

2. **激活模块**
   在 LSPosed 管理器中启用本模块，作用域勾选需要的目标：
   - 「屏幕录制」（`com.oplus.screenrecorder`）
   - 「截屏」（`com.oplus.screenshot`，需先打开「显示系统应用」）

3. **配置参数**
   打开「录屏/截图增强」界面，设置录屏码率 / 色彩 / HDR，以及是否把截图保存为 PNG，点击 **保存设置**。

4. **重启目标应用使配置生效**
   点击右上角的 **重启图标（⟳）**。它会对两个目标应用执行 `am force-stop`。
   由于 Hook 只在进程启动时（`onPackageReady`）读取一次配置，**修改设置后必须重启对应进程才会生效**。
   > 首次点击会弹出超级用户授权框，请授予本模块 Root 权限。

5. **开始使用**
   - 录屏：重新拉起系统录屏进行录制。若开启了 HDR，请在录屏应用内把编码格式选为 **HEVC（H.265）**——AVC 不支持 10-bit / HDR。
   - 截图：音量下 + 电源，或下拉通知栏点「截屏」，存下来的图片即为 PNG。

---

## 工作原理

### 录屏管线

```
屏幕内容 → VirtualDisplay（捕获尺寸） → Surface → MediaCodec 编码器（MediaFormat 尺寸 / 色彩） → 输出视频
```

模块在目标进程启动时（`onPackageReady`）安装一系列 Hook：

- **分辨率 / 码率**：Hook `MediaFormat.setInteger`、`MediaFormat.createVideoFormat`，将 width / height / bitrate 改写为目标值。
- **虚拟显示器捕获尺寸**：若只改编码器尺寸而不改 VirtualDisplay 捕获尺寸，视频右侧 / 下侧会出现纯黑区域。为此模块：
  - Hook 应用层包装类 `i4.u`（App 自己的 `createVirtualDisplay` 封装）——**主修复入口**；
  - 同时 Hook `MediaProjection.createVirtualDisplay`、`DisplayManager.createVirtualDisplay`、`VirtualDisplay.resize` 作为补充。
- **色彩注入**：Hook `MediaCodec.configure`，在编码器 configure 前强制注入 `color-standard` / `color-range` / `color-transfer` / `color-matrix`；开启 HDR 时额外注入 10-bit profile。

> **为什么优先 Hook 应用层类而非框架方法？**
> 由 boot class loader 加载的框架方法经 PGO / AOT 优化后可能被内联或走 fast path，导致 Java 层 Hook 不触发；而应用自身代码（`i4.u`）始终可被 Xposed 拦截。

### 截图管线（无损 PNG）

系统截屏的保存链路（`com.oplus.screenshot` 17.7.10 实测）：

```
TaskSaveScreen → SaveExecutor
   ├─ ContentValues{ mime_type = fileFormat.getMimeType() }
   ├─ ImageFileInfo{ name = "%s_%s" + fileFormat.getSuffix() }
   └─ bitmap.compress(fileFormat.getCompressFormat(), 100, out)
```

`ImageFileFormat` 的三个 getter 是**唯一真相源**，模块只对 `JPEG` 这个枚举单例改写返回值：

| Hook | 原值 | 改写为 |
| --- | --- | --- |
| `getMimeType()` | `image/jpeg` | `image/png` |
| `getSuffix()` | `.jpg` | `.png` |
| `getCompressFormat()` | `JPEG` | `PNG` |

因此文件名、MediaStore 的 `mime_type`、真正写盘的编码格式三者天然一致，**不会出现「JPEG 改后缀成 .png」的假无损**。

补充说明：

- 用 `Enum.name() == "JPEG"` 判断，而不是读 `JPEG` 静态字段——R8 可能重命名字段，但枚举名字符串一定保留；
- 已用 `dexdump` 反汇编 `classes5.dex` 确认这三个 getter 在调用点都是 `invoke-virtual`，**没有被 R8 内联**，所以 Hook 必然命中；
- 另有兜底 Hook：按参数签名 `(ContentResolver, Uri, Bitmap, CompressFormat)` 定位 `SaveExecutor` 的写盘方法，直接把第 4 个参数改成 `PNG`；
- 该分支同时覆盖：普通截图、长截图、编辑后保存、标记保存（它们都汇入 `SaveExecutor`）。

> **为什么不用「自己写 App + MediaProjection」？** 每次截图都要重新授权、常驻「正在投屏」图标，且拿不到音量下+电源的物理键触发，体验与画质都不占优。

### HDR / 10-bit 录制原理

开启 HDR 后，模块在 `MediaCodec.configure` 前对视频编码器额外注入：

- **传输特性**：HLG（`color-transfer=7`）或 PQ（`color-transfer=6`）；
- **原色 / 矩阵**：强制 BT.2020（`color-standard=6` / `color-matrix=6`）；
- **10-bit profile**：先 `getCapabilitiesForType(mime)` 查询能力，再注入带 HDR 语义的 HEVC 档位——HLG 用 `Main10HLG10 (0x2000)`，PQ 用 `Main10HDR10 (0x1000)`。

> **关键：为什么不能只用 `Main10`？**
> `Main10 (0x2)` 只代表「10-bit」，本身仍是 SDR 档位。一旦显式指定 profile，编码器会优先用 profile 推导 VUI，把传输特性写回 **bt709**。必须用带 HDR 语义的 `Main10HLG10 / Main10HDR10`。

> **Surface 输入与 `color-format`**：录屏是 Surface 输入模式，`color-format` 恒为 `COLOR_FormatSurface (0x7F000789)`，位深由 profile 决定。

---

## 项目结构

```
app/src/main/
├── java/com/kai/oplusrecorder/
│   ├── HookModule.kt      # Xposed 模块入口：录屏 Hook + 截屏 PNG Hook
│   ├── MainActivity.kt    # 配置界面（码率 / 色彩 / HDR / 截图 PNG / 保存 / 重启）
│   └── App.kt             # Application，RemoteSettings 远程配置桥接
├── res/layout/
│   └── activity_main.xml  # 界面布局
└── resources/META-INF/xposed/
    ├── java_init.list     # 模块入口类声明
    ├── scope.list         # 作用域（com.oplus.screenrecorder + com.oplus.screenshot）
    └── module.prop        # 模块元信息（libxposed API 102）
```

### 技术栈

- **语言**：Kotlin
- **UI**：Android View（AppCompat + ConstraintLayout）
- **Hook 框架**：libxposed `api:102.0.0`（compileOnly）+ `service:102.0.0`（远程配置）
- **构建**：Gradle（Kotlin DSL），`compileSdk 37` / `minSdk 29` / `targetSdk 37`

---

## 常见问题

### 录屏

- **视频右侧 / 下侧有黑边**：说明 VirtualDisplay 捕获尺寸未被改写。请确认作用域已勾选系统录屏，并检查 logcat 中 `i4.u` 相关 Hook 是否命中。
- **改了码率但没生效**：修改后必须点击重启图标 force-stop 目标应用。
- **界面重新打开又变回默认值**：配置通过 `XposedService` 异步绑定读取，界面已用 `runWhenReady` 在绑定完成后回填。
- **Full Range 画面偏暗 / 发灰**：部分播放器对 Full Range 兼容性不佳，可切回 Limited 对比。
- **开启 HDR 后传输特性还是 bt709 / 位深仍 8-bit**：多为录屏应用未选 HEVC，或编码器未暴露 `Main10HLG10 (8192)` 档位。抓 logcat（tag `OplusRecorderHook`）看 `[CAPS] ... profiles=[...]`。

### 截图

- **LSPosed 里找不到「截屏」**：它是系统应用，需在 LSPosed 设置中打开「显示系统应用」。
- **截图还是 JPEG**：① 确认作用域已勾选截屏应用；② 点一次重启图标让 `com.oplus.screenshot` 重新加载；③ 抓 logcat（tag `OplusRecorderHook`）搜索 `SCREENSHOT PACKAGE READY` / `ImageFileFormat.` 是否出现。
- **怎么确认真的无损**：相册详情应显示 PNG；或 `adb shell head -c 8 <文件>` 应得到 `89 50 4E 47 0D 0A 1A 0A`。
- **文件变大 / 保存变慢**：PNG 体积约为 JPEG 的 2–4 倍，编码多 200–500ms，但写盘在后台协程里，不影响操作。
- **某个分享目标不认 PNG**：界面里关掉「保存为无损 PNG」即可立刻回到系统默认行为，无需卸载。

---

## 免责声明

本项目仅供学习与研究使用。修改系统应用的录制 / 截屏参数可能因设备、系统版本差异而表现不同，请自行评估风险。
