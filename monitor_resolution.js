'use strict';

var MIN_WIDTH = 200;
var MIN_HEIGHT = 200;
var ONLY_NEW = true; // true: 同一尺寸只打印一次(避免每帧刷屏); false: 每次都打印

// ---- 输出过滤配置 ----
var SHOW_SWAPCHAIN    = true;  // 呈现分辨率(最有用)
var SHOW_VIEWPORT     = true;  // 全屏渲染视口(主渲染分辨率)
var SHOW_CREATE_IMAGE = false; // 纹理/RT, 噪音大, 默认关
var SHOW_SCISSOR      = false; // 裁剪矩形, 噪音大, 默认关
var RENDER_MIN_FRACTION = 0.6; // 只报告 >= 屏幕宽度60%的视口, 过滤后期降采样
var ASPECT_TOLERANCE    = 0.03; // 宽高比容差(3%)

var lastLogged = {};
function logSize(api, tag, w, h) {
    w = Math.round(w);
    h = Math.round(h);
    if (w < MIN_WIDTH || h < MIN_HEIGHT) return;
    var key = tag + ':' + w + 'x' + h;
    if (ONLY_NEW) {
        if (lastLogged[key]) return;
        lastLogged[key] = true;
    }
    console.log('[' + api + '] ' + tag + ': ' + w + 'x' + h);
}

// ---- 主分辨率追踪 ----
var screenW = 0, screenH = 0;   // 由 Swapchain 得到的呈现分辨率
var lastRenderKey = '';

// 是否为"全屏级"尺寸: 宽高比接近屏幕, 且不小于屏幕宽度的 RENDER_MIN_FRACTION
function isFullScreenish(w, h) {
    if (!screenW || !screenH || h <= 0) return false;
    var r1 = w / h, r2 = screenW / screenH;
    if (Math.abs(r1 - r2) / r2 > ASPECT_TOLERANCE) return false;
    return w >= screenW * RENDER_MIN_FRACTION;
}

function reportRender(w, h) {
    var key = w + 'x' + h;
    if (key === lastRenderKey) return;
    lastRenderKey = key;
    var scale = screenW ? (w / screenW * 100).toFixed(0) + '%' : '?';
    console.log('[RESULT] 主渲染分辨率: ' + w + 'x' + h +
        '  (呈现 ' + screenW + 'x' + screenH + ', 渲染缩放 ' + scale + ')');
}

// Frida 17 移除了静态 Module.findExportByName(null, name)，改用 findGlobalExportByName
// 这里做一层兼容封装，新旧版本都能用
function findGlobalExport(name) {
    try {
        if (typeof Module.findGlobalExportByName === 'function') {
            return Module.findGlobalExportByName(name);
        }
    } catch (e) {}
    try {
        if (typeof Module.findExportByName === 'function') {
            return Module.findExportByName(null, name);
        }
    } catch (e) {}
    return null;
}

// ---------------------- OpenGL ES Hook ----------------------
var isGLESHooked = false;
function hookGLES() {
    if (isGLESHooked) return;
    var module = Process.findModuleByName('libGLESv2.so');
    if (!module) return;

    var glTexImage2D = module.findExportByName('glTexImage2D');
    if (glTexImage2D) {
        Interceptor.attach(glTexImage2D, {
            onEnter: function (args) {
                // target, level, internalformat, width, height, ...
                logSize('GLES', 'glTexImage2D', args[3].toInt32(), args[4].toInt32());
            }
        });
        console.log('[*] Hooked glTexImage2D');
    }

    var glRenderbufferStorage = module.findExportByName('glRenderbufferStorage');
    if (glRenderbufferStorage) {
        Interceptor.attach(glRenderbufferStorage, {
            onEnter: function (args) {
                // target, internalformat, width, height
                logSize('GLES', 'glRenderbufferStorage', args[2].toInt32(), args[3].toInt32());
            }
        });
        console.log('[*] Hooked glRenderbufferStorage');
    }

    var glViewport = module.findExportByName('glViewport');
    if (glViewport) {
        Interceptor.attach(glViewport, {
            onEnter: function (args) {
                // x, y, width, height
                logSize('GLES', 'glViewport', args[2].toInt32(), args[3].toInt32());
            }
        });
        console.log('[*] Hooked glViewport');
    }

    isGLESHooked = true;
}

// ---------------------- Vulkan Hook ----------------------
// 各函数对应的参数解析(VkViewport=24字节, VkRect2D=16字节)
var vkHandlers = {
    'vkCreateImage': function (args) {
        if (!SHOW_CREATE_IMAGE) return;
        // (device, pCreateInfo, pAllocator, pImage); extent.width=28, height=32
        var p = args[1];
        logSize('Vulkan', 'vkCreateImage', p.add(28).readU32(), p.add(32).readU32());
    },
    'vkCreateSwapchainKHR': function (args) {
        if (!SHOW_SWAPCHAIN) return;
        // (device, pCreateInfo, pAllocator, pSwapchain); imageExtent.width=44, height=48
        var p = args[1];
        var w = p.add(44).readU32(), h = p.add(48).readU32();
        if (w < MIN_WIDTH || h < MIN_HEIGHT) return;
        if (w !== screenW || h !== screenH) {
            screenW = w; screenH = h; lastRenderKey = '';
            console.log('[RESULT] 呈现/屏幕分辨率: ' + w + 'x' + h);
        }
    },
    'vkCmdSetViewport': function (args) {
        if (!SHOW_VIEWPORT) return;
        // (cmd, firstViewport, viewportCount, pViewports)
        var count = args[2].toInt32();
        var p = args[3];
        for (var i = 0; i < count && i < 4; i++) {
            var w = Math.round(p.add(i * 24 + 8).readFloat());
            var h = Math.round(p.add(i * 24 + 12).readFloat());
            if (isFullScreenish(w, h)) reportRender(w, h);
        }
    },
    'vkCmdSetViewportWithCount': function (args) {
        if (!SHOW_VIEWPORT) return;
        // (cmd, viewportCount, pViewports) —— 注意签名不同!
        var count = args[1].toInt32();
        var p = args[2];
        for (var i = 0; i < count && i < 4; i++) {
            var w = Math.round(p.add(i * 24 + 8).readFloat());
            var h = Math.round(p.add(i * 24 + 12).readFloat());
            if (isFullScreenish(w, h)) reportRender(w, h);
        }
    },
    'vkCmdSetScissor': function (args) {
        if (!SHOW_SCISSOR) return;
        // (cmd, firstScissor, scissorCount, pScissors); extent.width=8, height=12
        var count = args[2].toInt32();
        var p = args[3];
        for (var i = 0; i < count && i < 4; i++) {
            logSize('Vulkan', 'Scissor', p.add(i * 16 + 8).readU32(), p.add(i * 16 + 12).readU32());
        }
    }
};

var hookedVkPtrs = {};
function hookVkFunc(name, ptr) {
    if (!ptr || ptr.isNull()) return;
    var handler = vkHandlers[name];
    if (!handler) return;
    var key = name + '@' + ptr;
    if (hookedVkPtrs[key]) return;
    hookedVkPtrs[key] = true;
    try {
        Interceptor.attach(ptr, {
            onEnter: function (args) { handler(args); }
        });
        console.log('[*] Hooked Vulkan ' + name);
    } catch (e) {
        console.log('[!] Hook ' + name + ' failed: ' + e);
    }
}

var isVulkanHooked = false;
function hookVulkan() {
    if (isVulkanHooked) return;
    var module = Process.findModuleByName('libvulkan.so');
    if (!module) return;
    isVulkanHooked = true;
    console.log('[*] libvulkan.so found, start hooking');

    // 1) 直接尝试 loader 导出的 trampoline(部分调用会走这里)
    Object.keys(vkHandlers).forEach(function (n) {
        var p = module.findExportByName(n);
        if (p) hookVkFunc(n, p);
    });

    // 2) 关键: 拦截 vkGetDeviceProcAddr / vkGetInstanceProcAddr 拿到的真实驱动函数指针
    ['vkGetDeviceProcAddr', 'vkGetInstanceProcAddr'].forEach(function (getter) {
        var addr = module.findExportByName(getter);
        if (!addr) return;
        Interceptor.attach(addr, {
            onEnter: function (args) {
                // 两者的 pName 都在 args[1]
                try { this.pName = args[1].readCString(); } catch (e) { this.pName = null; }
            },
            onLeave: function (retval) {
                if (this.pName && vkHandlers[this.pName]) {
                    hookVkFunc(this.pName, retval);
                }
            }
        });
        console.log('[*] Hooked ' + getter);
    });
}

// ---------------------- 库加载监听(尽早挂钩) ----------------------
function watchModuleLoads() {
    var candidates = ['android_dlopen_ext', 'dlopen'];
    var target = null;
    for (var i = 0; i < candidates.length; i++) {
        target = findGlobalExport(candidates[i]);
        if (target) break;
    }
    if (!target) {
        console.log('[!] dlopen/android_dlopen_ext not found, skip load watcher');
        return;
    }

    Interceptor.attach(target, {
        onEnter: function (args) {
            try { this.path = args[0].readCString(); } catch (e) { this.path = null; }
        },
        onLeave: function () {
            if (!this.path) return;
            if (this.path.indexOf('libvulkan.so') !== -1) hookVulkan();
            if (this.path.indexOf('libGLESv2.so') !== -1) hookGLES();
        }
    });
    console.log('[*] Watching library loads...');
}

// ---------------------- Main ----------------------
function main() {
    console.log('[*] Render resolution monitor started');
    console.log('[*] Filter: >= ' + MIN_WIDTH + 'x' + MIN_HEIGHT + (ONLY_NEW ? ' (dedup on)' : ''));
    watchModuleLoads();
    hookGLES();
    hookVulkan();

    // 兜底: 定时重试(库可能在启动后才加载)
    setInterval(function () {
        hookGLES();
        hookVulkan();
    }, 2000);
}

// spawn 模式下越早执行越好, 不要延迟太久
setTimeout(main, 0);
