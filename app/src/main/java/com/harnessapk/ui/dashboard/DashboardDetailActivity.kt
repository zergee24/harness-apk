package com.harnessapk.ui.dashboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import com.harnessapk.HarnessApkApplication

// 线程只读详情：打开拉一次最近 items，零按钮零输入，系统返回退出；
// 返回后副屏列表状态保持（同进程 StateFlow）。
class DashboardDetailActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val threadId = intent.getStringExtra(EXTRA_THREAD_ID).orEmpty()
        val container = (application as HarnessApkApplication).container
        setContent {
            MaterialTheme {
                DashboardDetailScreen(
                    container = container,
                    threadId = threadId,
                )
            }
        }
    }

    companion object {
        const val EXTRA_THREAD_ID = "threadId"
    }
}
