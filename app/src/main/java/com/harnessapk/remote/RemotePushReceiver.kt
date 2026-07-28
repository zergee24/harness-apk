package com.harnessapk.remote

import android.content.Context
import com.alibaba.sdk.android.push.MessageReceiver
import com.alibaba.sdk.android.push.notification.CPushMessage

class RemotePushReceiver : MessageReceiver() {
    override fun onMessage(context: Context, message: CPushMessage) {
        if (message.content == "wake") {
            RemoteConnectionService.start(context)
        }
    }

    override fun onNotification(
        context: Context,
        title: String,
        summary: String,
        extraMap: Map<String, String>,
    ) = Unit

    override fun onNotificationOpened(
        context: Context,
        title: String,
        summary: String,
        extraMap: Map<String, String>,
    ) = Unit

    override fun onNotificationRemoved(context: Context, messageId: String) = Unit

    override fun showNotificationNow(context: Context, extraMap: Map<String, String>): Boolean = false
}
