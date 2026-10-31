'use strict';

// ================================================================
//  patch_descriptor.js v7.1
//  渲染链: desc(+120/+124) 改写 + swapchain/image/framebuffer/viewport/scissor/renderArea 全强制 2772x1272
//  触控链: scale 守卫还原自然系数 (2126/2772, 975/1272), 使命中框与拉伸画布对齐
//  运行: frida -U -f com.miHoYo.Yuanshen -l patch_descriptor.js | Tee-Object patch_log.txt
// ================================================================

var MOD = 'libyuanshen.so';
var S2_OFF = 0x4fda044, S3_OFF = 0x4fd98ac, S4_OFF = 0x4fda2f4;
var DESC_FMT = 4, DESC_W = 120, DESC_H = 124, TARGET_FMT = 122;
var TGT_W = 2772, TGT_H = 1272;
var BAND_MIN = 1900, BAND_MAX = 2400, H_MIN = 700, H_MAX = 1100;
var SW_W = 44, SW_H = 48;
var IMG_FMT = 24, IMG_W = 28, IMG_H = 32;
var FB_W = 48, FB_H = 52;
var RA_W = 40, RA_H = 44;
var PAIR_OFF = 0x15f37180;
var SCALE_X_OFF = 0x15859778, SCALE_Y_OFF = 0x1585977c;
var SCALE_TX = 2126 / 2772, SCALE_TY = 975 / 1272;

var base = null;
var cntImg = 0, cntVp = 0, cntSc = 0, cntRa = 0, cntFb = 0, cntGuard = 0, cntState = 0;
var hookedPtrs = {}, resolverHooked = {}, siteHooked = {};
var guardActive = false;

function logPatch(m) { console.log(m); }
function inW(v) { return v >= BAND_MIN && v <= BAND_MAX; }
function inH(v) { return v >= H_MIN && v <= H_MAX; }

function descMatch(p) {
    try {
        var fmt = p.add(DESC_FMT).readU32();
        var w = p.add(DESC_W).readU32();
        var h = p.add(DESC_H).readU32();
        if (fmt === TARGET_FMT && inW(w) && inH(h)) return { w: w, h: h };
    } catch (e) {}
    return null;
}
function patchDesc(p, name) {
    var m = descMatch(p);
    if (!m) return;
    try {
        p.add(DESC_W).writeU32(TGT_W);
        p.add(DESC_H).writeU32(TGT_H);
        logPatch('[DESC ' + name + '] ' + m.w + 'x' + m.h + ' -> ' + TGT_W + 'x' + TGT_H);
    } catch (e) {}
}

var vkHandlers = {
    'vkCreateSwapchainKHR': function (args) {
        var p = args[1], w, h;
        try { w = p.add(SW_W).readU32(); h = p.add(SW_H).readU32(); } catch (e) { return; }
        if (w < 500 || w > 8000) return;
        if (inW(w)) {
            try { p.add(SW_W).writeU32(TGT_W); p.add(SW_H).writeU32(TGT_H); } catch (e) {}
            logPatch('[SWAP force] ' + w + 'x' + h);
        } else {
            logPatch('[obs swapchain] ' + w + 'x' + h);
        }
    },
    'vkCreateImage': function (args) {
        var p = args[1], fmt, w, h;
        try { fmt = p.add(IMG_FMT).readU32(); w = p.add(IMG_W).readU32(); h = p.add(IMG_H).readU32(); } catch (e) { return; }
        if (w < 1500 || w > 8000) return;
        if (inW(w) && inH(h)) {
            try { p.add(IMG_W).writeU32(TGT_W); p.add(IMG_H).writeU32(TGT_H); } catch (e) {}
            if (cntImg < 60) { cntImg++; logPatch('[IMG force] fmt#' + fmt + ' ' + w + 'x' + h + ' -> ' + TGT_W + 'x' + TGT_H); }
        }
    },
    'vkCreateFramebuffer': function (args) {
        var p = args[1], w, h;
        try { w = p.add(FB_W).readU32(); h = p.add(FB_H).readU32(); } catch (e) { return; }
        if (inW(w) && inH(h)) {
            try { p.add(FB_W).writeU32(TGT_W); p.add(FB_H).writeU32(TGT_H); } catch (e) {}
            if (cntFb < 60) { cntFb++; logPatch('[FB force] ' + w + 'x' + h + ' -> ' + TGT_W + 'x' + TGT_H); }
        }
    },
    'vkCmdSetViewport': function (args) {
        var n = args[2].toInt32(), p = args[3];
        for (var i = 0; i < n; i++) {
            var vp = p.add(i * 24);
            try {
                var w = vp.add(8).readFloat(), h = vp.add(12).readFloat();
                if (inW(w) && inH(h)) {
                    vp.add(0).writeFloat(0); vp.add(4).writeFloat(0);
                    vp.add(8).writeFloat(TGT_W); vp.add(12).writeFloat(TGT_H);
                    if (cntVp < 20) { cntVp++; logPatch('[VP force] ' + w + 'x' + h); }
                }
            } catch (e) { return; }
        }
    },
    'vkCmdSetViewportWithCount': function (args) {
        vkHandlers.vkCmdSetViewport([args[0], args[0], args[1], args[2]]);
    },
    'vkCmdSetScissor': function (args) {
        var n = args[2].toInt32(), p = args[3];
        for (var i = 0; i < n; i++) {
            var r = p.add(i * 16);
            try {
                var w = r.add(8).readU32(), h = r.add(12).readU32();
                if (inW(w) && inH(h)) {
                    r.add(0).writeU32(0); r.add(4).writeU32(0);
                    r.add(8).writeU32(TGT_W); r.add(12).writeU32(TGT_H);
                    if (cntSc < 20) { cntSc++; logPatch('[SC force] ' + w + 'x' + h); }
                }
            } catch (e) { return; }
        }
    },
    'vkCmdSetScissorWithCount': function (args) {
        vkHandlers.vkCmdSetScissor([args[0], args[0], args[1], args[2]]);
    },
    'vkCmdBeginRenderPass': function (args) {
        var p = args[1];
        try {
            var w = p.add(RA_W).readU32(), h = p.add(RA_H).readU32();
            if (inW(w) && inH(h)) {
                p.add(RA_W - 8).writeU32(0); p.add(RA_W - 4).writeU32(0);
                p.add(RA_W).writeU32(TGT_W); p.add(RA_H).writeU32(TGT_H);
                if (cntRa < 20) { cntRa++; logPatch('[RA force] ' + w + 'x' + h); }
            }
        } catch (e) {}
    }
};

function hookResolver(name) {
    if (resolverHooked[name]) return true;
    var lm = Process.findModuleByName('libvulkan.so');
    if (!lm) return false;
    var p = lm.findExportByName(name);
    if (!p) return false;
    resolverHooked[name] = true;
    Interceptor.attach(p, {
        onEnter: function (args) {
            try { this.fn = args[1].isNull() ? null : args[1].readCString(); } catch (e) { this.fn = null; }
        },
        onLeave: function (ret) {
            var fn = this.fn;
            if (!fn || !vkHandlers[fn] || ret.isNull()) return;
            var key = fn + '@' + ret.toString();
            if (hookedPtrs[key]) return;
            hookedPtrs[key] = true;
            try {
                Interceptor.attach(ret, { onEnter: function (args) { vkHandlers[fn](args, this.context); } });
                console.log('[*] Hooked 真实 ' + fn);
            } catch (e) {}
        }
    });
    console.log('[*] 拦截 ' + name);
    return true;
}

function tryHookCallSite(off, name) {
    var key = name + '@' + off;
    if (siteHooked[key]) return;
    try {
        Interceptor.attach(base.add(off), { onEnter: function () { patchDesc(this.context.x0, name); } });
        siteHooked[key] = true;
        console.log('[*] hook 调用点 ' + name + ' OK');
    } catch (e) {
        console.log('[!] hook 调用点 ' + name + ' 失败: ' + e.message);
    }
}

function startScaleGuard() {
    setInterval(function () {
        if (!base) return;
        try {
            var pw = base.add(PAIR_OFF).readU32(), ph = base.add(PAIR_OFF + 4).readU32();
            if (!guardActive && pw === 2126 && ph === 975) {
                guardActive = true;
                console.log('[guard] 激活: 游戏内事件确认(pair=2126x975), 接管 scale');
            }
            if (!guardActive) return;
            var sx = base.add(SCALE_X_OFF).readFloat(), sy = base.add(SCALE_Y_OFF).readFloat();
            if (Math.abs(sx - SCALE_TX) > 1e-6 || Math.abs(sy - SCALE_TY) > 1e-6) {
                base.add(SCALE_X_OFF).writeFloat(SCALE_TX);
                base.add(SCALE_Y_OFF).writeFloat(SCALE_TY);
                if (cntGuard < 60) { cntGuard++; console.log('[guard] scale ' + sx.toFixed(7) + ',' + sy.toFixed(7) + ' -> ' + SCALE_TX.toFixed(7) + ',' + SCALE_TY.toFixed(7)); }
            }
        } catch (e) {}
    }, 100);
}

function observeState() {
    if (!base || cntState > 30) return;
    try {
        var pw = base.add(PAIR_OFF).readU32(), ph = base.add(PAIR_OFF + 4).readU32();
        var sx = base.add(SCALE_X_OFF).readFloat(), sy = base.add(SCALE_Y_OFF).readFloat();
        if (pw === 0 && ph === 0) return;
        cntState++;
        console.log('[state] pair=' + pw + 'x' + ph + ' scale=' + sx.toFixed(4) + ',' + sy.toFixed(4) +
            (guardActive ? ' (guard ON)' : ''));
    } catch (e) {}
}

function main() {
    var m = Process.findModuleByName(MOD);
    if (!m) { setTimeout(main, 500); return; }
    base = m.base;
    console.log('[*] patch_descriptor v7.1 started, base=' + base);

    tryHookCallSite(S2_OFF, 'S2');
    tryHookCallSite(S3_OFF, 'S3');
    tryHookCallSite(S4_OFF, 'S4');

    var tries = 0;
    var t = setInterval(function () {
        tries++;
        var a = hookResolver('vkGetDeviceProcAddr');
        var b = hookResolver('vkGetInstanceProcAddr');
        if ((a && b) || tries > 40) clearInterval(t);
    }, 200);

    startScaleGuard();
    setInterval(observeState, 2000);
}

setTimeout(main, 0);