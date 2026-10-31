# HDR 静态元数据未生效分析与模块方案评估

> 设备：一加 15（PLK110），Android 17 / API 37，LSPosed（libxposed API 102）
> 目标应用：`com.oplus.screenrecorder` 17.5.4（`屏幕录制_17.5.4.apk`）
> 分析对象：`app/src/main/java/com/kai/oplusrecorder/HookModule.kt`
> 结论先行：**这不是"编码器不支持"，而是 HDR 分支从头到尾没有写 `hdr-static-info`。**
> 该 key 的值类型是 **25 字节 ByteBuffer**，不是 int；当前 `setInteger` 体系无法携带它。
> 已在工作区修复并用真机录制验证（见第 4、6 节）。

---

## 1. 结论摘要（TL;DR）

1. **直接原因**：`injectColorParams()` 的 HDR 分支只写了 `color-standard/range/transfer` 和 `profile`，**没有写 `hdr-static-info`**。因此 MediaMuxer 不会写 `mdcv`/`clli` box，播放器拿不到 mastering display / MaxCLL / MaxFALL，看起来就是"HDR 没生效"。
2. **第二个坑**：即使补上，也不能用 `format.setInteger("hdr-static-info", ...)`。Android 要求该 key 是 **ByteBuffer**；类型不对时 native 侧静默忽略（不报错），`MediaMuxer` 还要求 **长度恰好 25 字节**，否则只打印 `Ignoring HDR static info with unexpected size`，同样静默丢弃。
3. **第三个坑**：`PROFILE_MAIN10_HLG10 = 0x2000` 是 `HEVCProfileMain10HDR10Plus`（HDR10+），**不是 HLG**。HLG 没有独立 profile，应使用 `Main10(0x2) + color-transfer=HLG(7)`。
4. **第四个坑**：HDR 应该是 **limited range**；旧代码沿用 UI 的 Full，实测旧文件 `colr` 的 `full_range=0x80`（pc），不符合 HDR10/HLG 规范。修复后为 `full_range=0`（tv）。
5. **第五个坑（方案层）**：目标 App 的捕获链路是 **8-bit**：`ImageReader.newInstance(w, h, 1, 2)`（`ImageFormat.RGBA_8888`）+ GLES20/EGL RGBA8888；VirtualDisplay 的 `supportedHdrTypes=[]`。因此即使元数据写对，也只是**给 8-bit SDR 画面贴 HDR 标签**，不是真正的 HDR 采集。10-bit 输出是把 8-bit 上采样到 P010，并不会增加动态范围；PQ/BT.2020 还会让播放器按 HDR 做 tone mapping，画面可能反而变暗/发灰。
6. **组件选择**：App 用 `MediaCodec.createByCodecName()` 固定选择列表第一个 HEVC 编码器（本机 `c2.qti.hevc.encoder`，`hdr-editing=false`）。`c2.qti.hevc.encoder.hdr`（`hdr-editing=true`）不会被选中。当前修复在 `c2.qti.hevc.encoder` 上已验证可写元数据；如果要做厂商 HDR 编辑管线，必须在 `createByCodecName` 阶段换组件，configure 阶段已经换不了。
7. **动态扫描 bug**：Android 14+ `DexPathList$Element.path` 是 `java.io.File`，旧代码 `as? String` 永远为 null，日志出现 `候选 0，加载 0`，应用层 `i4.u`/`z5.u` Hook 实际从未安装。已修复为 `File/String` 兼容；并增加已知类名 `z5.u` 快路径（17.5.4 实测命中，hook 3 个方法）。
8. **双 proceed bug**：多处 `chain.proceed()` 放在 try 里、catch 里又 `proceed()` 一次；一旦 proceed 之后抛异常，会重复执行 `setInteger`/`configure`/`createVirtualDisplay`。已改为只 proceed 一次。

---

## 2. 目标 App 的实际管线（17.5.4 反编译）

关键类（R8 混淆名）：

| 类 | 作用 |
|---|---|
| `z5.o` | `VideoRecorderConfig`：`f14815a=width`、`f14816b=height`、`f14817c=fps`、`f14819e=bitrate`、`f14820f=i-frame`、`f14821g=mime`、`f14822h=profile/level`、`f14823i=codecName`、`f14825k=isAreaRecorder` |
| `z5.o.d()` | 构造 `MediaFormat`：`color-format=0x7F000789(COLOR_FormatSurface)`、`bitrate`、`frame-rate`、`i-frame-interval`、`profile/level`（`c.d()` 实际返回 null，所以通常不设 profile） |
| `z5.u` | App 自己的 `createVirtualDisplay` 封装；`a()`/`b()`/`c()` 首参都是 `MediaProjection`；内部走 `MediaProjection.createVirtualDisplay(name,w,h,1,16,surface,...)` 或 `VirtualDisplayConfig.Builder(...)` |
| `z5.e` | 直接把 `MediaCodec.createInputSurface()` 交给 VirtualDisplay 的一路 |
| `z5.g` | `ImageReader.newInstance(w,h,1,2)`（RGBA_8888）+ VirtualDisplay，随后 GL 绘制到编码器 Surface |
| `z5.l` | GL 渲染（`a6.i`/`a6.d`，EGL RGBA8888）到编码器 Surface |
| `com.oplus.screenrecorder.common.c.b(mime)` | 返回该 mime 的**所有**编码器，App 取 `[0]`；HEVC 本机即 `c2.qti.hevc.encoder` |

要点：

- App **完全不设置** `color-standard` / `color-range` / `color-transfer` / `hdr-static-info`；这些全部来自模块注入。
- 编码器在 `MediaCodec.createByCodecName(f14823i)` 时就已经固定，`configure()` 只能改参数，不能换组件。
- 捕获端是 8-bit RGBA_8888（`ImageReader` format=1），GL 是 RGBA8888；`VirtualDisplay` 信息里 `supportedHdrTypes=[]`。
- App 自己会把 HEVC 码率减半（`max(bitrate/2, 2M)`），模块的 `bitrate` 改写会覆盖它。

---

## 3. 为什么静态元数据注入不生效

### 3.1 代码路径：HDR 分支根本没有写这个 key

旧版 `injectColorParams()` 的 HDR 分支只有：

```kotlin
format.setInteger(KEY_COLOR_STANDARD, effStandard)
format.setInteger(KEY_COLOR_RANGE, colorRange)
format.setInteger(KEY_COLOR_TRANSFER, effTransfer)
format.setInteger(KEY_COLOR_MATRIX, effStandard)   // color-matrix 也不是公开 key
if (hdrOn) {
    format.setInteger(KEY_PROFILE, profile)
}
```

没有 `KEY_HDR_STATIC_INFO`。所以旧文件（真机实测）：

- `profile=Main 10`、`yuv420p10le`、`color_space=bt2020nc`、`color_transfer=smpte2084`、`color_range=pc`
- **没有** `mdcv`/`clli` box，**没有** Mastering display / Content light level side data，首帧也**没有** SEI。
- 看起来"HDR 已经生效"（因为 PQ/BT.2020 标记在），但播放器没有 mastering/CLL，无法正确 tone map，很多播放器会按 SDR/BT.709 回退。

### 3.2 平台约束：`hdr-static-info` 是 ByteBuffer，且必须 25 字节

Android `MediaFormat.KEY_HDR_STATIC_INFO` 的官方注释（API 37）：

> The associated value is a **ByteBuffer**. This buffer contains the raw contents of the
> **Static Metadata Descriptor (including the descriptor ID)** of an HDMI Dynamic Range and
> Mastering InfoFrame as defined by CTA-861.3. This key must be provided to video encoders
> for HDR video content.

对应 AOSP `MPEG4Writer::writeMdcvAndClliBoxes()` 的实现：

- `meta->findData(kKeyHdrStaticInfo, ...)`，**size != 25 直接 return** 并打印 `Ignoring HDR static info with unexpected size`；
- 按小端读 `data[1..24]`；
- `mdcv` 的 max/min 写的是 `uint16 * 10000`（即 Android 这个 16-bit 字段按 **1 nit** 解释，不是 CTA 文档的 0.0001 nit）；
- `clli` 直接写 `data[21..24]` 的 MaxCLL/MaxFALL。

25 字节布局（本模块 `buildHdrStaticInfo()` 已按此实现）：

| 偏移 | 内容 | 单位 |
|---|---|---|
| 0 | descriptor ID = 0 | - |
| 1..12 | R/G/B 原色 x,y（6 × uint16） | 1/50000 |
| 13..16 | 白点 x,y（2 × uint16） | 1/50000 |
| 17 | max display luminance | 1 nit |
| 19 | min display luminance | 1 nit（AOSP 按此解释） |
| 21 | MaxCLL | 1 nit |
| 23 | MaxFALL | 1 nit |

**关键**：

- 不能用 `setInteger`；`MediaFormat.setInteger` 只是 `mMap.put(name, Integer)`，native 侧按 ByteBuffer 取会失败，**静默忽略**。
- 必须 `ByteBuffer.order(LITTLE_ENDIAN)`，写完后 `flip()`（position=0，limit=25），否则 native 读到 0 字节或错位。
- 长度必须 25；24 字节（漏 descriptor ID）或 26 字节都会被 MediaMuxer 丢弃。
- min 不能想当然写 50：本机实测 `min=50` 会写成 **50 nit 黑位**（mdcv/SEI 都是 50），黑位严重抬高；写 `1` 才是 1 nit。`0` 会写成 0 nit。

### 3.3 编码器/组件层

本机 `MediaCodecList` 探测结果：

| 编码器 | hdr-editing | hlg-editing | profileLevels |
|---|---|---|---|
| `c2.qti.hevc.encoder` | **false** | **true** | Main(1), MainStill(4), Main10(2), HDR10(0x1000), HDR10+(0x2000), 0x20 |
| `c2.qti.hevc.encoder.hdr` | **true** | false | 同上，最大 4096×4096 / 120fps |
| `c2.qti.avc.encoder` | false | false | AVC |

- App 取 `b("video/hevc")[0]`，即 `c2.qti.hevc.encoder`；`hdr-editing=false`。
- 但实测在 `c2.qti.hevc.encoder` 上写 `profile=0x1000` + 25B `hdr-static-info` **可以**正常 configure，输出会带 `mdcv/clli` 和 SEI；所以"元数据写不进去"不是组件不支持导致的。
- `.hdr` 组件的优势是厂商 `hdr-editing` 管线（tone mapping/10-bit 编辑），不是"能不能写 SEI"的必要条件。若要用它，必须 hook `MediaCodec.createByCodecName` 把 `c2.qti.hevc.encoder` 换成 `c2.qti.hevc.encoder.hdr`；configure 阶段已经无法换。
- 输出里 `profile` 会从请求的 0x1000 归一化为 **2 (Main10)**，这是正常的：HEVC 码流 `general_profile_idc=2`，HDR10 由 VUI transfer=PQ(16) + static metadata 表达，不是靠 profile_idc=4096。

### 3.4 HLG 的 profile 常量是错的

- `HEVCProfileMain10HDR10 = 0x1000`：PQ/HDR10，正确。
- `HEVCProfileMain10HDR10Plus = 0x2000`：**HDR10+**，不是 HLG。
- HEVC 没有 HLG 专用 profile；HLG 用 `Main10(0x2)` + `color-transfer=HLG(7)`，码流 VUI transfer=18（arib-std-b67）。本机 `c2.qti.hevc.encoder` 有 `hlg-editing`，适合 HLG。

旧代码 `PROFILE_MAIN10_HLG10 = 0x2000` 只是在这台机器上被编码器归一化了，没暴露问题；换一台设备/组件可能真的按 HDR10+ 处理，属于语义错误。

---

## 4. 真机实测对比（同一台手机、同一个 App）

### 4.1 修复前（旧模块，14:01 录制）

```
codec_name=hevc
profile=Main 10
width=1272 height=2772
pix_fmt=yuv420p10le
color_range=pc
color_space=bt2020nc
color_transfer=smpte2084
color_primaries=bt2020
（无 Mastering display metadata / 无 Content light level metadata）
colr: nclx primaries=9 transfer=16 matrix=9 full_range=128 (0x80)
```

首帧没有 SEI；没有 `mdcv`/`clli` box。

### 4.2 修复后（本工作区代码，14:23 录制）

```
codec_name=hevc
profile=Main 10
width=1272 height=2772
pix_fmt=yuv420p10le
color_range=tv
color_space=bt2020nc
color_transfer=smpte2084
color_primaries=bt2020
Mastering display metadata:
  red_x=35400/50000 red_y=14600/50000
  green_x=8500/50000 green_y=39850/50000
  blue_x=6550/50000 blue_y=2300/50000
  white_point_x=15635/50000 white_point_y=16450/50000
  min_luminance=10000/10000 (1 nit)
  max_luminance=10000000/10000 (1000 nit)
Content light level metadata
colr: nclx primaries=9 transfer=16 matrix=9 full_range=0
mdcv: ... 00989680 00002710  → max=1000 nit, min=1 nit
clli: 03e8 0190             → MaxCLL=1000, MaxFALL=400
首帧 SEI: NAL type 39，包含 payload 137（mastering）+ 144（CLL）
```

结论：**静态元数据从"完全没有"变成"容器 mdcv/clli + 码流 SEI 都有"**。CLL 精确为 1000/400；mastering 的容器值精确为 BT.2020/D65/1000/1，编码器 SEI 的 mastering 字段仍有自己的默认（min 约 0.0768 nit），但已经不再缺失。

### 4.3 探针（同一设备，独立编码器测试）

| 测试 | 结果 |
|---|---|
| `setByteBuffer("hdr-static-info", 25B)` | configure OK；输出 `mdcv/clli`；ffprobe 有 mastering/CLL side data |
| `setInteger("hdr-static-info", 1)` | configure OK；**没有** `mdcv/clli`，ffprobe 无 side data（静默忽略） |
| 不写该 key | configure OK；没有 `mdcv/clli` |
| `setByteBuffer` 长度不是 25 | MediaMuxer 忽略（AOSP 源码行为） |
| `min=50` | mdcv/SEI 写成 50 nit 黑位（错误值） |
| `min=1` / `min=0` | mdcv 写成 1 nit / 0 nit |
| 加 `profile=0x1000`（HEVC） | 输出 profile=Main10、10-bit、PQ/BT2020 |
| 加 `profile=0x2000` + transfer=HLG | 输出仍是 HLG（transfer=18），0x2000 未改变 VUI；但常量语义仍错 |

---

## 5. 方案合理性评估

### 5.1 设计得好的部分

- **Hook 点选得对**：`MediaCodec.configure` 是编码参数的最终汇聚点，比 `MediaRecorder`/App 内部 setter 更可靠；`MediaFormat.setInteger` 覆盖码率；`VirtualDisplayConfig`/`MediaProjection.createVirtualDisplay` 覆盖捕获尺寸。
- **分辨率成对改写**：录屏请求是"短边档位 × 屏幕比例"（如 1080×2354），成对识别再映射，避免单值误伤，这是正确的思路。
- **App 包装类兜底**：`z5.u` 这类 App 代码 Hook 比 boot class 方法更可靠（虽然本机看到小方法可能被内联，框架构造器 Hook 仍覆盖）。
- **设置与 UI 分离**：RemotePreferences + 强制停止生效，逻辑清晰。

### 5.2 P0：HDR 语义与静态元数据

1. **HDR 不是"改 metadata"**：捕获链路是 8-bit RGBA_8888 + GLES20 RGBA8888，VirtualDisplay `supportedHdrTypes=[]`；Android 17 的 `VirtualDisplayConfig`/`MediaProjection` 也没有公开 HDR 捕获开关。当前 HDR 模式本质是 **"10-bit 编码 + HDR 标签"**，不是真 HDR。
   - 10-bit 输出是 8-bit 上采样，不会增加动态范围；
   - PQ/BT.2020 + mastering metadata 会让播放器按 HDR tone map，SDR 内容可能变暗/发灰/过饱和；
   - 如果目标是"减少色带"，更合理的是 **Main10 + BT.709 + SDR transfer** 的"10-bit SDR"档，而不是 PQ。
   - 如果目标是真 HDR，需要 HDR 捕获源（HDR 屏幕合成/HDMI/相机），当前框架路径做不到。
2. **静态元数据必须按 ByteBuffer 注入**（已修）：
   - HDR10 分支写 25B `hdr-static-info`；
   - 值：BT.2020 原色、D65、1000 nit 峰值、1 nit 黑位、MaxCLL 1000/MaxFALL 400；
   - 长度/字节序/position 必须正确；否则静默失败；
   - HLG 不写 mastering metadata（HLG 是显示参考，不需要），只设 `Main10 + transfer=HLG`。
3. **HDR 只在 HEVC 上做**：AVC 没有 HDR profile；旧代码在 AVC 上也会写 PQ/BT2020 和 0x1000，可能产生非法 VUI 或 configure 失败。已加 `isHevc` 守卫，不满足就回退 SDR。
4. **HDR 强制 limited range**：旧代码沿用 UI 的 Full，实测旧文件 `full_range=0x80`；HDR10/HLG 规范是 limited（tv）。已修。
5. **HLG profile 常量修正**：`0x2000` → `Main10(0x2)`；`0x2000` 仅表示 HDR10+。
6. **组件选择（可选）**：若要厂商 HDR 编辑管线，增加 `MediaCodec.createByCodecName` Hook，按 `MediaCodecList` 找支持 `FEATURE_HdrEditing`（HDR10）或 `FEATURE_HlgEditing`（HLG）的 HEVC 编码器；注意 `.hdr` 最大 4096×4096、120fps，8K/高帧率要保留原组件。当前元数据注入在默认组件已验证，不必强制换。

### 5.3 P1：可靠性问题

1. **动态类扫描在 Android 14+ 完全失效**（已修一部分）：
   - `DexPathList$Element.path` 是 `File`，旧代码 `as? String` → null → `候选 0`（LSPosed 日志实证）；
   - 即使修成 File，`MAX_SCAN_CLASSES=400` 也小于本 App 的 1882 个短类名候选，`z5.u` 在字典序靠后，扫不到；
   - 已加已知类名 `i4.u`/`z5.u` 快路径；17.5.4 实测 `z5.u` 命中并 hook 3 个方法；
   - 不建议为了通用性加载全部 1882 个类（启动慢、可能触发静态初始化）；建议"已知名 + 框架 Hook"为主，全量扫描只做诊断开关。
2. **double proceed**（已修）：`setInteger`/`configure`/`createVirtualDisplay` 的 catch 里再次 `proceed()`，在 proceed 之后抛异常时会重复执行目标方法。轻则重复写入，重则创建两个 VirtualDisplay、configure 两次、泄漏 Surface。
3. **码率启发式**（已修）：旧逻辑 `>1Mbps 视为视频`，720p 低码率视频（<1M）会被写成 288kbps 音频码率。已改为优先读 `format` 的 mime。
4. **`modifyIntFields` 仍然过宽**：它会把对象里所有值等于识别到的 `(w,h)` 的 int 字段都改掉，可能误改无关字段（如缓存、标志位、裁剪相关 int）。目前只用于 App 的 `z5.o`/`f6.a`，风险可控，但建议：
   - 只改 App 已知字段（`z5.o.f14815a/f14816b`）或按字段名白名单；
   - 或在 `MediaCodec.configure` 里只改 format，不改 App 对象，避免状态不一致。
5. **分辨率识别容差**：`isRecordingPreset` 只接受短边 720/1080/1440，比例容差 1.5%。用户如果填一个与屏幕比例不同的偶数尺寸（例如 1080×2340），编码器尺寸和 VD 尺寸会按目标改，但画面比例与源不一致，可能拉伸/黑边。建议 UI 强制保持屏幕宽高比，或明确提示。
6. **VD density 不调整**：App 传 density=1，目前不影响；但若某些机型 density 非 1，改 w/h 不改 dpi 会改变逻辑尺寸/浮窗比例，需评估。

### 5.4 P2：工程与体验

1. **设置只在 `onPackageReady` 读一次**：改码率/色彩必须 force-stop（README 已说明）；可以考虑监听 RemotePreferences 变更或在每次 configure 时懒读，减少重启。
2. **HDR 与"色彩优化"开关耦合**：`colorEnabled=false` 时整个 HDR 分支直接 return；UI 上仍可选 HDR，行为不一致。建议 HDR 独立开关，或在 UI 禁用。
3. **`color-matrix` 不是公开 key**：已移除；`color-standard` 会决定 matrix，写 `color-matrix` 无效果。
4. **日志**：本次真机验证发现，`injectColorParams` 明明执行了（输出有 mdcv/clli），但 LSPosed 的 modules 日志里看不到 `[CONFIG]` 行（只有启动日志）。不要只依赖 LSPosed 日志判断 Hook 是否生效；建议关键路径同时 `Log.i(TAG, ...)` 到 logcat，或用计数器/文件落盘。
5. **测试覆盖**：至少覆盖 HEVC/AVC、HLG/HDR10、Full/Limited、横竖屏、区域录制、暂停/恢复、不同厂商编码器。HDR 输出建议用 `ffprobe`/`MediaExtractor` 验证 `colr/mdcv/clli` 和 SEI，而不是只看画面。

---

## 6. 本次改动（工作区）

文件：`app/src/main/java/com/kai/oplusrecorder/HookModule.kt`

1. **HDR10 静态元数据注入**
   - 新增 `KEY_HDR_STATIC_INFO`、`HDR_STATIC_INFO_SIZE=25`、亮度/CLL 常量；
   - `injectColorParams()` 在 HDR10 且 HEVC + Main10 可用时 `format.setByteBuffer(KEY_HDR_STATIC_INFO, buildHdrStaticInfo())`；
   - `buildHdrStaticInfo()` 按 CTA-861.3/MPEG4Writer 布局写 25B 小端；
   - `buildHdrStaticInfo()` 写完后 `flip()`；min 取 1 nit。
2. **HDR 正确性**
   - HDR 只在 HEVC 上生效，否则回退 SDR；
   - 无 Main10 profile 时回退 SDR，不写 PQ 标记；
   - HDR 强制 `color-range=2 (limited)`；
   - HLG 使用 `Main10(0x2)`，不再用 `0x2000`；
   - HLG 不写 `hdr-static-info`（不需要 mastering），只写 profile/transfer/standard/range；
   - 删除不存在的 `color-matrix` key。
3. **可靠性**
   - `shortClassNames()` 兼容 `Element.path` 为 `File`（Android 14+）；
   - 已知类名快路径 `i4.u`/`z5.u`，命中后不做全量扫描；
   - `setInteger`/`configure`/`createVideoFormat`/`hookSizeCall`/`hookWrapperMethods` 全部改为**只 proceed 一次**；
   - 码率按 mime 区分音视频；
   - `modifyIntFields` 遍历父类字段（仍建议后续收窄）。

构建：`./gradlew assembleDebug` 通过（JDK 17，AGP 9.3.2，Gradle 9.5.0）。
真机：已安装 debug APK，force-stop 后录制 2 分钟，`ffprobe` 验证见第 4.2 节。

---

## 7. 建议

### 方案 A（推荐，保留"HDR 元数据"但诚实命名）

- UI 改为「HDR10 元数据（实验）」/「HLG 元数据（实验）」，并在说明里写清楚：
  - 捕获是 8-bit SDR，HDR 标记不会创造动态范围；
  - 部分播放器可能显示偏暗/发灰；
  - 只建议在确认播放器兼容时使用。
- 保留本次静态元数据注入；HDR10 默认 limited；HLG 用 Main10。
- 增加「10-bit SDR（Main10 + BT.709 + SDR）」档，给"减少色带"的真实需求使用，不贴 PQ 标签。

### 方案 B（真 HDR）

- 需要 HDR 捕获源：HDR 屏幕合成/VirtualDisplay HDR、HDMI 采集、相机 HDR 等；当前 `VirtualDisplayConfig`/`MediaProjection` 没有公开 HDR 开关，单靠 `MediaFormat` 改标签做不到。
- 若未来 Android 提供 HDR VirtualDisplay，需同时改：VD 的 dataspace/HDR 配置、GL 的 10-bit/F16 中间缓冲、编码器 P010 输入、VUI/SEI；工作量远大于当前 Hook。

### 方案 C（最小风险）

- 删除 HDR 模式，只保留 SDR 色彩优化（BT.709/Full 或 Limited）与 10-bit SDR；避免"看起来是 HDR、实际是 SDR"的兼容性问题。

---

## 8. 附录：关键参考与复现命令

### 8.1 平台参考

- `MediaFormat.KEY_HDR_STATIC_INFO` 文档：<https://developer.android.com/reference/android/media/MediaFormat#KEY_HDR_STATIC_INFO>
- AOSP `MPEG4Writer::writeMdcvAndClliBoxes()`（25 字节校验、mdcv/clli 写入、min×10000）：
  <https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/media/libstagefright/MPEG4Writer.cpp>
- CTA-861.3 Static Metadata Descriptor（descriptor ID + 24B payload）

### 8.2 复现验证

```bash
# 1. 看编码器能力
adb shell "CLASSPATH=/data/local/tmp/probe/enc.dex app_process /system/bin EncProbe c2.qti.hevc.encoder.hdr buf 0x1000 2 6 6 test"

# 2. 看录制文件颜色/静态元数据
ffprobe -v error -select_streams v:0 \
  -show_entries stream=codec_name,profile,pix_fmt,color_range,color_space,color_transfer,color_primaries \
  -show_entries stream_side_data=side_data_type,max_luminance,min_luminance \
  -of default=noprint_wrappers=1 Record.mp4

# 3. 看 mdcv/clli box（任意 MP4 box 解析器均可；ffprobe 的 SIDE_DATA 已能看出有无）
ffprobe -v error -show_entries stream_side_data -of default=noprint_wrappers=1 Record.mp4
```

### 8.3 本机实测关键值

| 项 | 旧 | 新 |
|---|---|---|
| `colr.full_range` | 0x80 (pc) | 0x00 (tv) |
| `mdcv` | 无 | BT.2020 / D65 / 1000 nit / 1 nit |
| `clli` | 无 | MaxCLL=1000, MaxFALL=400 |
| 首帧 SEI | 无 | NAL 39（137 + 144） |
| `profile` | Main 10 | Main 10 |
| `pix_fmt` | yuv420p10le | yuv420p10le |
| 分辨率 | 1272×2772 | 1272×2772 |
