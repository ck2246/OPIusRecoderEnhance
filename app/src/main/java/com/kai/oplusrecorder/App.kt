package com.kai.oplusrecorder

import android.app.Application
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

object RemoteSettings {

    @Volatile
    var prefs:
            android.content.SharedPreferences? = null
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

                    RemoteSettings.prefs =
                        service.getRemotePreferences(
                            "settings"
                        )
                }

                override fun onServiceDied(
                    service: XposedService
                ) {

                    RemoteSettings.prefs = null
                }
            }
        )
    }
}