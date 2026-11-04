package com.codebox.installer

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

class EngineService : Service() {
    private fun runOnUiThread(action: () -> Unit) { android.os.Handler(android.os.Looper.getMainLooper()).post { action() } }

    private var wakeLock: PowerManager.WakeLock? = null
    private var monitorThread: Thread? = null
    private var isRunning = false

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(1, buildNotification("Codebox engine running"))
        
        acquireWakeLock()
        startMemoryMonitor()

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        monitorThread?.interrupt()
        releaseWakeLock()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                "engine",
                "Engine Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun buildNotification(title: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notificationBuilder = Notification.Builder(this, "engine")
            .setContentTitle(title)
            .setContentText("Engine is active")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .setContentIntent(pendingIntent)

        return notificationBuilder.build()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Codebox:EngineService")
        wakeLock?.acquire()
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    private fun startMemoryMonitor() {
        isRunning = true
        monitorThread = Thread {
            var lowMemCount = 0
            while (isRunning) {
                try {
                    Thread.sleep(5000)
                    val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    val memInfo = ActivityManager.MemoryInfo()
                    activityManager.getMemoryInfo(memInfo)

                    val totalMem = memInfo.totalMem
                    val availMem = memInfo.availMem
                    val threshold = totalMem * 0.10

                    if (availMem < threshold) {
                        lowMemCount++
                        if (lowMemCount >= 6) {
                            runOnUiThread {
                                // Post second notification
                                val lowMemNotification = buildNotification("Low RAM engine stopped")
                                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                                manager.notify(2, lowMemNotification)
                            }
                            stopSelf()
                            break
                        }
                    } else {
                        lowMemCount = 0
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e("EngineService", "Error checking memory", e)
                }
            }
        }
        monitorThread?.start()
    }
}
