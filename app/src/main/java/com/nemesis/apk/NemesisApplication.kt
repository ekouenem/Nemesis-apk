package com.nemesis.apk

import android.app.Application
import com.nemap.android.EmbeddedRuntime

class NemesisApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        EmbeddedRuntime.start(this)
    }
}
