package com.grinthas.szmarket

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Раз в ~15 минут проверяет избранное и уведомляет, если лот дешевле порога. */
class AlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store = Store(applicationContext)
        val watched = store.favorites.filterValues { it > 0 }
        if (watched.isEmpty() || !store.hasKeys) return Result.success()
        val api = Api(store)
        val names = runCatching { Items(applicationContext).load(store.region.realm).associateBy { it.id } }
            .getOrDefault(emptyMap())

        for ((id, threshold) in watched) {
            val cheapest = runCatching { api.lots(id, limit = 5).second }.getOrNull()
                ?.filter { it.buyoutPrice > 0 }
                ?.minByOrNull { it.buyoutPrice / it.amount.coerceAtLeast(1) }
                ?: continue
            val unit = cheapest.buyoutPrice / cheapest.amount.coerceAtLeast(1)
            if (unit <= threshold && store.notified(id) != unit) {
                store.setNotified(id, unit)
                notify(id.hashCode(), names[id]?.name ?: id, "Выкуп ${money(unit)} за шт. (порог ${money(threshold)})")
            } else if (unit > threshold) {
                store.setNotified(id, 0)
            }
        }
        return Result.success()
    }

    private fun notify(id: Int, title: String, text: String) {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Цены аукциона", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(id, n)
    }

    companion object {
        private const val CHANNEL = "prices"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AlertWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("alerts", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
