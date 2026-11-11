package com.nemesis.apk

import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object ApprovalBridge {
    private var host = WeakReference<MainActivity>(null)
    fun attach(activity: MainActivity) { host = WeakReference(activity) }
    fun detach(activity: MainActivity) { if (host.get() === activity) host.clear() }

    @JvmStatic fun confirm(action: String, details: String): Boolean {
        val activity = host.get() ?: return false
        val answer = AtomicBoolean(false)
        val completed = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) { latch.countDown(); return@runOnUiThread }
            try {
                activity.confirmAction(action, details) { approved ->
                    if (completed.compareAndSet(false, true)) { answer.set(approved); latch.countDown() }
                }
            } catch (_: Exception) { latch.countDown() }
        }
        val responded = latch.await(120, TimeUnit.SECONDS)
        completed.set(true)
        return responded && answer.get()
    }
}
