package com.example.myandroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

class NotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_ID_CHAT = "chat_message_channel"
        const val CHANNEL_NAME_CHAT = "聊天消息"
        const val CHANNEL_DESC_CHAT = "接收新聊天消息通知"
        const val NOTIFICATION_ID_PREFIX = 1000
    }

    private val notificationManager = NotificationManagerCompat.from(context)

    fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID_CHAT,
                CHANNEL_NAME_CHAT,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = CHANNEL_DESC_CHAT
                enableLights(true)
                lightColor = Color.GREEN
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                
                val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build()
                setSound(soundUri, audioAttributes)
            }
            
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun showChatMessageNotification(
        title: String,
        message: String,
        senderId: String?,
        senderName: String?,
        conversationId: String?,
        badgeCount: Int = 1
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("action", "open_chat")
            putExtra("senderId", senderId)
            putExtra("senderName", senderName)
            putExtra("conversationId", conversationId)
        }

        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            (senderId ?: "default").hashCode(),
            intent,
            pendingIntentFlags
        )

        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        
        val notification = NotificationCompat.Builder(context, CHANNEL_ID_CHAT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(message)
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setSound(soundUri)
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .setLights(Color.GREEN, 1000, 1000)
            .setNumber(badgeCount)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_ID_CHAT)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("新消息")
                    .setContentText("您有${badgeCount}条未读消息")
                    .setNumber(badgeCount)
                    .build()
            )
            .build()

        val notificationId = NOTIFICATION_ID_PREFIX + (conversationId ?: senderId ?: "default").hashCode()
        
        notificationManager.notify(notificationId, notification)
    }

    fun cancelNotification(conversationId: String?) {
        val notificationId = NOTIFICATION_ID_PREFIX + (conversationId ?: "default").hashCode()
        notificationManager.cancel(notificationId)
    }

    fun cancelAllNotifications() {
        notificationManager.cancelAll()
    }

    fun updateBadgeCount(count: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = notificationManager.getNotificationChannel(CHANNEL_ID_CHAT)
            channel?.let {
                val newChannel = NotificationChannel(
                    CHANNEL_ID_CHAT,
                    CHANNEL_NAME_CHAT,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = CHANNEL_DESC_CHAT
                    enableLights(true)
                    lightColor = Color.GREEN
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 500, 200, 500)
                    setShowBadge(true)
                }
                notificationManager.createNotificationChannel(newChannel)
            }
        }
    }
}
