Java.perform(function () {

    console.log("[+] Hook started");

    /*
     * MediaProjection.createVirtualDisplay
     */

    var MediaProjection =
        Java.use("android.media.projection.MediaProjection");

    var createVD =
        MediaProjection.createVirtualDisplay.overload(
            "java.lang.String",
            "int",
            "int",
            "int",
            "int",
            "android.view.Surface",
            "android.hardware.display.VirtualDisplay$Callback",
            "android.os.Handler"
        );

    createVD.implementation = function (
        name,
        width,
        height,
        dpi,
        flags,
        surface,
        callback,
        handler
    ) {

        console.log(
            "[VirtualDisplay BEFORE] " +
            width + "x" + height
        );

        if (
            width === 1080 &&
            height === 2354
        ) {

            console.log(
                "[HOOK VD] 1080x2354 -> 1272x2772"
            );

            width = 1272;
            height = 2772;
        }

        console.log(
            "[VirtualDisplay AFTER] " +
            width + "x" + height
        );

        return createVD.call(
            this,
            name,
            width,
            height,
            dpi,
            flags,
            surface,
            callback,
            handler
        );
    };

    /*
     * MediaFormat.setInteger
     *
     */
    var MediaFormat = Java.use("android.media.MediaFormat");
    var setInteger = MediaFormat.setInteger.overload("java.lang.String", "int");

    setInteger.implementation = function (key, value) {
        var k = key.toString();
        var originalValue = value;

        // 修改宽度
        if (k === "width" && value === 1080) {
            console.log("[HOOK Format] width 1080 -> 1272");
            value = 1272;
        }

        // 修改高度
        if (k === "height" && value === 2354) {
            console.log("[HOOK Format] height 2354 -> 2772");
            value = 2772;
        }

        // 拦截并修改视频码率 
        // 有两个 bitrate，分别来自视频(8000000)和音频(128000)。
        // 只修改视频码率，可以通过判断数值来区分。
        // 这里区分音频：设置里没有音频码率设置；而视频码率最低1mbps，如果 key 是 bitrate，且数值大于 1000000 (1Mbps)，
        // 我们就认为它是视频码率并进行修改。
        if (k === "bitrate" && value > 1000000) {
            var newBitrate = 30000000; // 30 Mbps，自定义值，这里可以作为变量，后续由用户在图形化模块中填入
            console.log("[HOOK Format] 视频 bitrate " + value + " -> " + newBitrate);
            value = newBitrate;
        }
        //修改音频码率
        if (k === "bitrate" && value < 1000000) {
            var newBitrate = 320000; // 自定义值，边界288kbps
            console.log("[HOOK Format] 音频 bitrate " + value + " -> " + newBitrate);
            value = newBitrate;
        }

        // 打印所有被拦截的 MediaFormat 设置
        console.log("[MediaFormat] " + k + " = " + value + (originalValue !== value ? " (已修改)" : ""));

        // 调用原始方法，传入可能已修改的 value
        return setInteger.call(this, key, value);
    };

    console.log(
        "[+] Hooks installed"
    );



//新增MediaCodec.configure hook
var MediaCodec = Java.use("android.media.MediaCodec");  // 

var configure = MediaCodec.configure.overload(
    "android.media.MediaFormat",
    "android.view.Surface",
    "android.media.MediaCrypto",
    "int"
);

configure.implementation = function (
    format,
    surface,
    crypto,
    flags
) {

    var name = this.getName();

    console.log("");
    console.log("========== MediaCodec.configure ==========");
    console.log("Codec: " + name);
    console.log("Format: " + format.toString());
    console.log("Flags: " + flags);
    console.log("==========================================");

    return configure.call(
        this,
        format,
        surface,
        crypto,
        flags
    );
};

//id排序
var seq = 0;

configure.implementation = function (
    format,
    surface,
    crypto,
    flags
) {
    var id = ++seq;

    console.log("");
    console.log("[CONFIG #" + id + "] BEGIN");
    console.log("[CONFIG #" + id + "] Codec: " + this.getName());
    console.log("[CONFIG #" + id + "] Format: " + format.toString());
    console.log("[CONFIG #" + id + "] Flags: " + flags);

    var ret = configure.call(
        this,
        format,
        surface,
        crypto,
        flags
    );

    console.log("[CONFIG #" + id + "] END");

    return ret;
};

//新增acc output format
var getOutputFormat =
    MediaCodec.getOutputFormat.overload();

getOutputFormat.implementation = function () {

    var result = getOutputFormat.call(this);

    try {
        var name = this.getName();

        if (name.indexOf("aac") >= 0) {

            console.log("");
            console.log("========== AAC OutputFormat ==========");
            console.log("Codec: " + name);
            console.log("Format: " + result.toString());
            console.log("======================================");

        }

    } catch (e) {
        console.log("[OutputFormat ERROR] " + e);
    }

    return result;
};


});