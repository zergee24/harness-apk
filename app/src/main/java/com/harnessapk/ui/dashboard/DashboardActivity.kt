package com.harnessapk.ui.dashboard

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import com.harnessapk.HarnessApkApplication
import com.harnessapk.remote.DashboardViewedStore
import com.harnessapk.remote.RemoteConnectionService

// 副屏模式宿主：前台常亮（FLAG_KEEP_SCREEN_ON，配合 USB 供电习惯），
// 无二级界面，系统返回即退出。
class DashboardActivity : ComponentActivity() {
    private val viewedStore by lazy { DashboardViewedStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                )
            }
        }
    }
}
