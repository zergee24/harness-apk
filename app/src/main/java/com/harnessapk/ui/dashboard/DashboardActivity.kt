package com.harnessapk.ui.dashboard

import android.content.Intent
import com.harnessapk.MainActivity
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import com.harnessapk.HarnessApkApplication
import com.harnessapk.remote.RemoteConnectionService

// 副屏模式宿主：前台常亮（FLAG_KEEP_SCREEN_ON，配合 USB 供电习惯），
// 无二级界面，系统返回即退出。
class DashboardActivity : ComponentActivity() {
    // 与角标共用同一实例：副屏打点会推 revision，桌面角标立即重算未读。
    private val viewedStore by lazy {
        (application as HarnessApkApplication).container.dashboardViewedStore
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 副屏是专用常显设备：锁屏之上直接显示并点亮（含 PIN 锁屏，仅盖住展示
        // 不代解锁），USB 重插/超时锁屏后回到副屏不再被锁屏挡住。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 复用前台服务保活连接：夜间进程被杀后服务重启，重新订阅即由
        // bridge 的 dashboard.snapshot 全量快照恢复整屏状态。
        RemoteConnectionService.start(this)
        val container = (application as HarnessApkApplication).container
        setContent {
            MaterialTheme {
                DashboardScreen(
                    container = container,
                    viewedStore = viewedStore,
                    onExit = { finish() },
                    onConnect = {
                        startActivity(Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            .putExtra(MainActivity.EXTRA_OPEN_REMOTE_SETTINGS, true))
                        finish()
                    },
                )
            }
        }
    }
}
