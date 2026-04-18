package com.lagradost.cloudstream3.utils

import android.os.Handler
import android.os.Looper
import com.lagradost.cloudstream3.mvvm.launchSafe
import com.lagradost.cloudstream3.mvvm.logError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.util.Collections.synchronizedList

/**
 * Merged KMP common + androidMain `Coroutines` helpers from upstream
 * CloudStream v4.4.0. See LICENSES/CLOUDSTREAM3.md — GPL-3 vendored into
 * arvio's sideload flavor only.
 */
fun runOnMainThreadNative(work: (() -> Unit)) {
    val mainHandler = Handler(Looper.getMainLooper())
    mainHandler.post { work() }
}

object Coroutines {
    fun <T> T.main(work: suspend ((T) -> Unit)): Job {
        val value = this
        return CoroutineScope(Dispatchers.Main).launchSafe { work(value) }
    }

    fun <T> T.ioSafe(work: suspend (CoroutineScope.(T) -> Unit)): Job {
        val value = this
        return CoroutineScope(Dispatchers.IO).launchSafe { work(value) }
    }

    suspend fun <T, V> V.ioWorkSafe(work: suspend (CoroutineScope.(V) -> T)): T? {
        val value = this
        return withContext(Dispatchers.IO) {
            try { work(value) } catch (e: Exception) { logError(e); null }
        }
    }

    suspend fun <T, V> V.ioWork(work: suspend (CoroutineScope.(V) -> T)): T {
        val value = this
        return withContext(Dispatchers.IO) { work(value) }
    }

    suspend fun <T, V> V.mainWork(work: suspend (CoroutineScope.(V) -> T)): T {
        val value = this
        return withContext(Dispatchers.Main) { work(value) }
    }

    fun runOnMainThread(work: (() -> Unit)) {
        runOnMainThreadNative(work)
    }

    fun <T> threadSafeListOf(vararg items: T): MutableList<T> {
        return synchronizedList(items.toMutableList())
    }
}
