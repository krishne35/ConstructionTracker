package com.example.constructiontracker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** day: 1 = Monday ... 7 = Sunday (only used when weekly). */
data class Schedule(val weekly: Boolean = false, val day: Int = 1, val hour: Int = 0, val minute: Int = 0)

object Notifier {
    private const val CHANNEL = "backup"

    fun show(ctx: Context, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Backups", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true).build()
        nm.notify(1001, n)
    }
}

class BackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val prefs = SmbPrefs(applicationContext)
        if (!prefs.autoOn()) return Result.success()
        val cfg = prefs.load()
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy h:mm a"))
        return try {
            val dao = AppDb.get(applicationContext).dao()
            val bytes = BackupJson.encode(dao.projectsNow(), dao.categoriesNow(), dao.partiesNow(), dao.txnsNow())
            val name = SmbBackup.newName()
            withContext(Dispatchers.IO) {
                SmbBackup.upload(cfg, name, bytes)
                SmbBackup.prune(cfg, 30)
            }
            prefs.setLastAuto("$stamp, saved $name")
            Notifier.show(applicationContext, "Backup complete", "Saved $name to the share")
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry()
            else {
                val msg = e.message ?: e.javaClass.simpleName
                prefs.setLastAuto("$stamp, failed: $msg")
                Notifier.show(applicationContext, "Backup failed", msg)
                Result.failure()
            }
        }
    }
}

object BackupScheduler {
    private const val NAME = "scheduled_backup"

    fun nextRun(s: Schedule, now: LocalDateTime = LocalDateTime.now()): LocalDateTime {
        var t = now.toLocalDate().atTime(s.hour, s.minute)
        if (s.weekly) {
            while (t.dayOfWeek.value != s.day || !t.isAfter(now)) t = t.plusDays(1)
        } else if (!t.isAfter(now)) {
            t = t.plusDays(1)
        }
        return t
    }

    fun schedule(ctx: Context, s: Schedule) {
        val delay = Duration.between(LocalDateTime.now(), nextRun(s)).toMillis()
        val req = PeriodicWorkRequestBuilder<BackupWorker>(if (s.weekly) 7L else 1L, TimeUnit.DAYS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(ctx)
            .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, req)
    }

    fun cancel(ctx: Context) { WorkManager.getInstance(ctx).cancelUniqueWork(NAME) }
}
