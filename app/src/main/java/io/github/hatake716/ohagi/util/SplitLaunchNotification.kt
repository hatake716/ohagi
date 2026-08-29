package io.github.hatake716.ohagi.util

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import androidx.core.content.ContextCompat
import io.github.hatake716.ohagi.R
import io.github.hatake716.ohagi.SplitLaunchActivity
import io.github.hatake716.ohagi.data.AppRef

/**
 * ohagiから通常起動したアプリを1つ目として、通知に選択導線を表示する。
 *
 * 通知タップをReceiverやServiceで中継するとAndroid 12以降のnotification trampoline
 * 制限に抵触するため、PendingIntentは非公開のSplitLaunchActivityを直接指す。
 * Activity内のカテゴリー式ドロワーで2つ目を選んだ後、そのActivityを有効な起点として
 * OS標準の分割画面へ移る。通知は常駐させ、同じ1つ目に対して繰り返し利用できる。
 */
object SplitLaunchNotification {

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.split_notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.split_notification_channel_description)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    fun appLabel(context: Context, ref: AppRef): String {
        val packageManager = context.packageManager
        return try {
            @Suppress("DEPRECATION")
            packageManager.getActivityInfo(
                ComponentName(ref.packageName, ref.className),
                PackageManager.MATCH_DISABLED_COMPONENTS,
            ).loadLabel(packageManager).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            ref.packageName
        }
    }

    @SuppressLint("MissingPermission")
    fun post(context: Context, first: AppRef): Boolean {
        createChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!canPost(context, manager)) return false

        val pickerIntent = SplitLaunchActivity.intent(
            context = context,
            first = first,
        )
        val contentIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            pickerIntent,
            PendingIntent.FLAG_CANCEL_CURRENT or
                PendingIntent.FLAG_IMMUTABLE,
        )
        val firstLabel = appLabel(context, first)
        val message = context.getString(R.string.split_notification_text, firstLabel)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.split_notification_title))
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(contentIntent)
            .setAutoCancel(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setLocalOnly(true)
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_launcher_monochrome),
                    context.getString(R.string.split_notification_action),
                    contentIntent,
                ).build()
            )
            .build()

        return try {
            manager.notify(NOTIFICATION_ID, notification)
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun canPost(context: Context, manager: NotificationManager): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        if (!manager.areNotificationsEnabled()) return false
        return manager.getNotificationChannel(CHANNEL_ID)?.importance !=
            NotificationManager.IMPORTANCE_NONE
    }

    private const val CHANNEL_ID = "split_launch"
    private const val NOTIFICATION_ID = 7162
    private const val REQUEST_CODE = 7162
}
