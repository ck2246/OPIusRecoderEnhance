package com.kai.oplusrecorder

import android.app.Application
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

object RemoteSettings {

    @Volatile
    var prefs:
            android.content.SharedPreferences? = null
        private set

    // 等待“配置就绪”的一次性回调（由 XposedService 异步绑定触发）
    private val readyListeners =
        mutableListOf<() -> Unit>()

    /**
     * 由 App 的 onServiceBind / onServiceDied 调用，更新 prefs。
     * 绑定成功（非 null）时，回调并清空所有等待中的监听器。
     */
    fun updatePrefs(p: android.content.SharedPreferences?) {
        prefs = p
        if (p != null) {
            val snapshot = synchronized(readyListeners) {
                readyListeners.toList().also { readyListeners.clear() }
            }
            snapshot.forEach { runCatching { it() } }
        }
    }

    /**
     * 注册“配置就绪”回调：
     * - 若 prefs 已就绪，立即执行；
     * - 否则在 onServiceBind 后执行（仅一次）。
     * 用于解决 UI 在 prefs 异步绑定完成前读取到 null、只能显示默认值的问题。
     */
    fun runWhenReady(block: () -> Unit) {
        if (prefs != null) {
            block()
            return
        }
        synchronized(readyListeners) { readyListeners.add(block) }
    }
}

class App : Application() {

    override fun onCreate() {
        super.onCreate()

        XposedServiceHelper.registerListener(
            object :
                XposedServiceHelper.OnServiceListener {

                override fun onServiceBind(
                    service: XposedService
                ) {

                    RemoteSettings.updatePrefs(
                        service.getRemotePreferences(
                            "settings"
                        )
                    )
                }

                override fun onServiceDied(
                    service: XposedService
                ) {

                    RemoteSettings.updatePrefs(null)
                }
            }
        )
    }
}