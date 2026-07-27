package com.harnessapk.remote

import android.content.Context
import android.util.Log
import com.alibaba.sdk.android.push.CommonCallback
import com.alibaba.sdk.android.push.noonesdk.PushInitConfig
import com.alibaba.sdk.android.push.noonesdk.PushServiceFactory
import com.harnessapk.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AliyunPushManager(private val context: Context) {
    private val _deviceId = MutableStateFlow<String?>(null)
    val deviceId: StateFlow<String?> = _deviceId.asStateFlow()

    fun initialize() {
        if (BuildConfig.ALIYUN_PUSH_APP_KEY.isBlank() || BuildConfig.ALIYUN_PUSH_APP_SECRET.isBlank()) return
        PushServiceFactory.init(
            PushInitConfig.Builder()
                .application(context.applicationContext as android.app.Application)
                .appKey(BuildConfig.ALIYUN_PUSH_APP_KEY)
                .appSecret(BuildConfig.ALIYUN_PUSH_APP_SECRET)
                .build(),
        )
        val service = PushServiceFactory.getCloudPushService()
        service.register(context.applicationContext, object : CommonCallback {
            override fun onSuccess(response: String?) {
                _deviceId.value = service.deviceId?.takeIf(String::isNotBlank)
            }

            override fun onFailed(errorCode: String?, errorMessage: String?) {
                Log.w(TAG, "Aliyun push registration failed: $errorCode $errorMessage")
            }
        })
    }

    private companion object { const val TAG = "AliyunPushManager" }
}
