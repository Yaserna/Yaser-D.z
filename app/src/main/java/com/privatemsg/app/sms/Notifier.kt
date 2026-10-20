package com.privatemsg.app.sms

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.privatemsg.app.R
import com.privatemsg.app.data.ContactsHelper
import com.privatemsg.app.data.SecureStore
import com.privatemsg.app.data.SmsRepository
import com.privatemsg.app.ui.ConversationActivity
import com.privatemsg.app.ui.MainActivity

object Notifier {

    private const val CHANNEL_ID = "incoming_sms"
    private const val DECOY_CHANNEL_ID = "decoy_sms_silent"
    private const val DECOY_SOUND_CHANNEL_ID = "decoy_sms_sound"

    /** MMS notices are plain and one at a time: a fixed id keeps the shade free of stale copies. */
    private const val MMS_NOTIF_ID = 900_002

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_HIGH
            )
            channel.enableVibration(true)
            context.getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    private fun ensureDecoyChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                DECOY_CHANNEL_ID,
                context.getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_HIGH
            )
            channel.setSound(null, null)
            channel.enableVibration(true)
            context.getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    private fun ensureDecoySoundChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                DECOY_SOUND_CHANNEL_ID,
                context.getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_HIGH
            )
            channel.enableVibration(true)
            context.getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    private fun accentColor(context: Context): Int =
        androidx.core.content.ContextCompat.getColor(context, R.color.accent)

    private fun notify(context: Context, id: Int, builder: NotificationCompat.Builder) {
        try {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        } catch (_: SecurityException) {}
    }

    /**
     * The thread of a sender, or -1 when the system cannot resolve one (an operator
     * name, a short code). This call throws for those, and every caller here runs
     * inside a broadcast where one exception drops the notification for a real message.
     */
    private fun threadIdFor(context: Context, address: String): Long = try {
        Telephony.Threads.getOrCreateThreadId(context, address)
    } catch (e: Exception) {
        android.util.Log.e("Notifier", "no thread for this address", e)
        -1L
    }

    /**
     * A request code for one notification action. The action name and the message id
     * both go in: without them two different messages produce intent-equal PendingIntents
     * and FLAG_UPDATE_CURRENT silently rewrites the older one, so a "delete" button
     * would act on the newest message instead of the one it was built for.
     */
    private fun actionCode(variant: String, address: String, messageId: Long): Int =
        ("$variant|$address|$messageId").hashCode() and 0x7FFFFFFF

    fun showIncoming(context: Context, address: String, body: String, messageId: Long, subId: Int = -1) {
        if (SecureStore(context).isMuted(address)) {
            return
        }

        ensureChannel(context)
        val notifId = address.hashCode()
        val threadId = threadIdFor(context, address)

        val tapIntent = Intent(context, ConversationActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("address", address)
            putExtra("thread_id", threadId)
        }
        val tapPi = PendingIntent.getActivity(
            context, actionCode("tap", address, messageId), tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        fun actionIntent(action: String): Intent =
            Intent(context, NotificationActionReceiver::class.java).apply {
                setAction(action)
                // A distinct data Uri per action and message, so the system keeps this
                // button's callback instead of replacing another message's.
                setData(android.net.Uri.parse("com.privatemsg.app:notif/$action/$messageId"))
                putExtra("address", address)
                putExtra("thread_id", threadId)
                putExtra("message_id", messageId)
                putExtra("notif_id", notifId)
                putExtra("sub_id", subId)
            }

        val replyOpenIntent = Intent(context, com.privatemsg.app.ui.QuickReplyActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            data = android.net.Uri.parse("com.privatemsg.app:notif/reply/$messageId")
            putExtra("address", address)
            putExtra("thread_id", threadId)
            putExtra("notif_id", notifId)
                putExtra("sub_id", subId)
        }
        val replyPi = PendingIntent.getActivity(
            context, actionCode("reply", address, messageId), replyOpenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val readPi = PendingIntent.getBroadcast(
            context, actionCode("read", address, messageId),
            actionIntent(NotificationActionReceiver.ACTION_MARK_READ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val deletePi = PendingIntent.getBroadcast(
            context, actionCode("delete", address, messageId),
            actionIntent(NotificationActionReceiver.ACTION_DELETE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val replyAction = NotificationCompat.Action.Builder(
            R.drawable.ic_send_up, context.getString(R.string.notif_reply), replyPi
        ).build()

        val title = ContactsHelper(context).displayFor(address)
        // Reading the unread rows is a provider query and the permission can be revoked
        // between the message arriving and this notification being built.
        val unread = if (threadId > 0) runCatching { SmsRepository(context).unreadBodies(threadId) }
            .getOrDefault(emptyList())
        else emptyList()
        val count = if (unread.isEmpty()) 1 else unread.size

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_message)
            .setColor(accentColor(context))
            .setContentTitle(title)
            .setAutoCancel(true)
            .setContentIntent(tapPi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(R.drawable.ic_mark_read, context.getString(R.string.notif_mark_read), readPi)
            .addAction(R.drawable.ic_delete, context.getString(R.string.notif_delete), deletePi)
            .addAction(replyAction)

        if (count > 1) {
            val inbox = NotificationCompat.InboxStyle()
            unread.takeLast(7).forEach { inbox.addLine(it) }
            inbox.setSummaryText(context.getString(R.string.n_new_messages, count))
            builder.setStyle(inbox)
                .setContentText(context.getString(R.string.n_new_messages, count))
                .setNumber(count)
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentText(body)
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            builder.setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
        }

        notify(context, notifId, builder)
    }

    fun cancelIncoming(context: Context, address: String) {
        NotificationManagerCompat.from(context).cancel(address.hashCode())
    }

    /**
     * An MMS arrived but this build cannot open media messages yet. Say so plainly,
     * without a sender name or any content, so the notice is identical for every number.
     */
    fun showMmsArrived(context: Context) {
        ensureChannel(context)
        // One fixed id: every new notice replaces the previous one instead of a fresh
        // hour getting its own, which used to leave stale notices in the shade.
        val tapPi = PendingIntent.getActivity(
            context, MMS_NOTIF_ID,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_message)
            .setColor(accentColor(context))
            .setContentTitle(context.getString(R.string.mms_arrived_title))
            .setContentText(context.getString(R.string.mms_arrived_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.mms_arrived_text)))
            .setAutoCancel(true)
            .setContentIntent(tapPi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            builder.setDefaults(NotificationCompat.DEFAULT_VIBRATE)
        }
        notify(context, MMS_NOTIF_ID, builder)
        vibrateTiny(context)
    }

    fun showSendFailed(context: Context, address: String) {
        ensureChannel(context)
        val notifId = ("failed_" + address).hashCode()
        val title = ContactsHelper(context).displayFor(address)
        val threadId = threadIdFor(context, address)
        val tapIntent = Intent(context, ConversationActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("address", address)
            putExtra("thread_id", threadId)
        }
        val tapPi = PendingIntent.getActivity(
            context, actionCode("failed", address, threadId), tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_message)
            .setColor(accentColor(context))
            .setContentTitle(title)
            .setContentText(context.getString(R.string.send_failed_notif))
            .setAutoCancel(true)
            .setContentIntent(tapPi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            builder.setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
        }
        notify(context, notifId, builder)
    }

    fun vibrateTiny(context: Context) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                    as? android.os.VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            } ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    android.os.VibrationEffect.createOneShot(
                        45, android.os.VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            } else {
                @Suppress("DEPRECATION") vibrator.vibrate(45)
            }
        } catch (_: Exception) {}
    }

    fun showDecoy(context: Context, secure: SecureStore, address: String) {
        if (secure.isMuted(address)) {
            return
        }

        val soundOn = secure.decoySoundEnabled
        if (soundOn) ensureDecoySoundChannel(context) else ensureDecoyChannel(context)
        val channelId = if (soundOn) DECOY_SOUND_CHANNEL_ID else DECOY_CHANNEL_ID

        val name = secure.decoyNameFor(address).ifBlank { context.getString(R.string.app_name) }
        val rawText = secure.decoyTextFor(address)
        val text = rawText.ifBlank { " " }
        val target = secure.decoyTargetFor(address)
        val now = System.currentTimeMillis()
        val notifId = decoyNotifId(address)

        val intent = if (target.isNotBlank()) {
            Intent(context, ConversationActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("address", target)
                putExtra(
                    "thread_id",
                    threadIdFor(context, target)
                )
                if (rawText.isNotBlank()) {
                    putExtra("decoy_fake_text", rawText)
                    putExtra("decoy_fake_time", now)
                }
            }
        } else {
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        }

        val pi = PendingIntent.getActivity(
            context, notifId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_message)
            .setColor(accentColor(context))
            .setContentTitle(name)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            if (soundOn) {
                builder.setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
            } else {
                builder.setDefaults(NotificationCompat.DEFAULT_VIBRATE).setSound(null)
            }
        }

        notify(context, notifId, builder)
    }

    private fun decoyNotifId(address: String): Int =
        ("decoy_" + SecureStore.normalize(address)).hashCode()

    fun cancelDecoy(context: Context, address: String) {
        NotificationManagerCompat.from(context).cancel(decoyNotifId(address))
    }
}
