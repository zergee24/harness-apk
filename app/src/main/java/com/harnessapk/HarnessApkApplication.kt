package com.harnessapk

import android.app.Application
import com.harnessapk.common.AppContainer

class HarnessApkApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
