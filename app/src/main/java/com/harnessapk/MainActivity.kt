package com.harnessapk

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.harnessapk.ui.HarnessApkApp
import com.harnessapk.ui.theme.HarnessApkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HarnessApkTheme {
                HarnessApkApp()
            }
        }
    }
}
