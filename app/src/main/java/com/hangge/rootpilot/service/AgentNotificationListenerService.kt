package com.hangge.rootpilot.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.hangge.rootpilot.RootPilotApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

data class AgentNotification(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    val ongoing: Boolean,
    val clearable: Boolean,
)

class AgentNotificationCenter(private val events: AgentEventHub) {
    private val _notifications = MutableStateFlow<List<AgentNotification>>(emptyList())
    val notifications: StateFlow<List<AgentNotification>> = _notifications.asStateFlow()

    fun posted(item: AgentNotification) {
        _notifications.value = (listOf(item) + _notifications.value.filterNot { it.key == item.key }).take(200)
        events.publish("notification.posted", item.toJson())
    }

    fun removed(key: String, packageName: String) {
        _notifications.value = _notifications.value.filterNot { it.key == key }
        events.publish("notification.removed", JSONObject().put("key", key).put("packageName", packageName))
    }

    private fun AgentNotification.toJson() = JSONObject()
        .put("key", key)
        .put("packageName", packageName)
        .put("title", title)
        .put("text", text)
        .put("postedAt", postedAt)
        .put("ongoing", ongoing)
        .put("clearable", clearable)
}

class AgentNotificationListenerService : NotificationListenerService() {
    private val center get() = (application as RootPilotApplication).container.notificationCenter

    override fun onListenerConnected() {
        super.onListenerConnected()
        activeNotifications.orEmpty().forEach(::onNotificationPosted)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        center.posted(
            AgentNotification(
                key = sbn.key,
                packageName = sbn.packageName,
                title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
                text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
                postedAt = sbn.postTime,
                ongoing = sbn.isOngoing,
                clearable = sbn.isClearable,
            ),
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        center.removed(sbn.key, sbn.packageName)
    }

    companion object {
        @Volatile var instance: AgentNotificationListenerService? = null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
