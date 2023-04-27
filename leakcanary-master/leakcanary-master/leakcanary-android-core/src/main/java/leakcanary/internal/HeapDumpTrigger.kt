package leakcanary.internal

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.res.Resources.NotFoundException
import android.os.Handler
import android.os.SystemClock
import com.squareup.leakcanary.core.R
import leakcanary.AppWatcher
import leakcanary.GcTrigger
import leakcanary.KeyedWeakReference
import leakcanary.LeakCanary.Config
import leakcanary.ObjectWatcher
import leakcanary.internal.NotificationReceiver.Action.CANCEL_NOTIFICATION
import leakcanary.internal.NotificationReceiver.Action.DUMP_HEAP
import leakcanary.internal.NotificationType.LEAKCANARY_LOW
import shark.AndroidResourceIdNames
import shark.SharkLog

@Suppress("TooManyFunctions")
internal class HeapDumpTrigger(
        private val application: Application,
        private val backgroundHandler: Handler,
        private val objectWatcher: ObjectWatcher,
        private val gcTrigger: GcTrigger,
        private val heapDumper: HeapDumper,
        private val configProvider: () -> Config
) {

    private val notificationManager
        get() =
            application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val applicationVisible
        get() = applicationInvisibleAt == -1L

    @Volatile
    private var checkScheduledAt: Long = 0L

    private var lastDisplayedRetainedObjectCount = 0

    private var lastHeapDumpUptimeMillis = 0L

    private val scheduleDismissRetainedCountNotification = {
        dismissRetainedCountNotification()
    }

    private val scheduleDismissNoRetainedOnTapNotification = {
        dismissNoRetainedOnTapNotification()
    }

    /**
     * When the app becomes invisible, we don't dump the heap immediately. Instead we wait in case
     * the app came back to the foreground, but also to wait for new leaks that typically occur on
     * back press (activity destroy).
     */
    private val applicationInvisibleLessThanWatchPeriod: Boolean
        get() {
            val applicationInvisibleAt = applicationInvisibleAt
            return applicationInvisibleAt != -1L && SystemClock.uptimeMillis() - applicationInvisibleAt < AppWatcher.config.watchDurationMillis
        }

    @Volatile
    private var applicationInvisibleAt = -1L

    fun onApplicationVisibilityChanged(applicationVisible: Boolean) {
        if (applicationVisible) {
            applicationInvisibleAt = -1L
        } else {
            applicationInvisibleAt = SystemClock.uptimeMillis()
            // Scheduling for after watchDuration so that any destroyed activity has time to become
            // watch and be part of this analysis.
            scheduleRetainedObjectCheck(
                    reason = "app became invisible",
                    rescheduling = false,
                    delayMillis = AppWatcher.config.watchDurationMillis
            )
        }
    }

    //当有对象可能泄漏时会回调
    fun onObjectRetained() {
        scheduleRetainedObjectCheck(
                reason = "found new object retained",
                rescheduling = false
        )
    }

    private fun checkRetainedObjects(reason: String) {
        //获取config
        val config = configProvider()
        // A tick will be rescheduled when this is turned back on.
        if (!config.dumpHeap) {
            SharkLog.d { "Ignoring check for retained objects scheduled because $reason: LeakCanary.Config.dumpHeap is false" }
            return
        }

        //获取 watchedObjects 中可能泄漏对象的 个数
        var retainedReferenceCount = objectWatcher.retainedObjectCount

        //当可能泄漏的对象个数大于0的时候 尝试GC
        if (retainedReferenceCount > 0) {
            //尝试GC
            gcTrigger.runGc()
            //更新个数
            retainedReferenceCount = objectWatcher.retainedObjectCount
        }

        //只有当 检查的次数大于 阈值的时候（默认是5） 才会进行下面的步骤
        if (checkRetainedCount(retainedReferenceCount, config.retainedVisibleThreshold)) return

        //debug 的 时候不做内存 dump
        if (!config.dumpHeapWhenDebugging && DebuggerControl.isDebuggerAttached) {
            showRetainedCountNotification(
                    objectCount = retainedReferenceCount,
                    contentText = application.getString(
                            R.string.leak_canary_notification_retained_debugger_attached
                    )
            )
            scheduleRetainedObjectCheck(
                    reason = "debugger is attached",
                    rescheduling = true,
                    delayMillis = WAIT_FOR_DEBUG_MILLIS
            )
            return
        }

        val now = SystemClock.uptimeMillis()
        //距离上次dump的时间
        val elapsedSinceLastDumpMillis = now - lastHeapDumpUptimeMillis
        //两次dump时间太近 不做 处理
        if (elapsedSinceLastDumpMillis < WAIT_BETWEEN_HEAP_DUMPS_MILLIS) {
            showRetainedCountNotification(
                    objectCount = retainedReferenceCount,
                    contentText = application.getString(R.string.leak_canary_notification_retained_dump_wait)
            )
            scheduleRetainedObjectCheck(
                    reason = "previous heap dump was ${elapsedSinceLastDumpMillis}ms ago (< ${WAIT_BETWEEN_HEAP_DUMPS_MILLIS}ms)",
                    rescheduling = true,
                    delayMillis = WAIT_BETWEEN_HEAP_DUMPS_MILLIS - elapsedSinceLastDumpMillis
            )
            return
        }

        SharkLog.d { "Check for retained objects found $retainedReferenceCount objects, dumping the heap" }
        dismissRetainedCountNotification()
        //开始进行内存快照
        dumpHeap(retainedReferenceCount, retry = true)
    }

    //进行内存快照
    private fun dumpHeap(
            retainedReferenceCount: Int, //泄漏对象个数
            retry: Boolean //是否重试 默认为 true
    ) {
        saveResourceIdNamesToMemory()
        val heapDumpUptimeMillis = SystemClock.uptimeMillis()
        KeyedWeakReference.heapDumpUptimeMillis = heapDumpUptimeMillis

        //生成.hropf文件
        val heapDumpFile = heapDumper.dumpHeap()
        if (heapDumpFile == null) {
            if (retry) {
                SharkLog.d { "Failed to dump heap, will retry in $WAIT_AFTER_DUMP_FAILED_MILLIS ms" }
                scheduleRetainedObjectCheck(
                        reason = "failed to dump heap",
                        rescheduling = true,
                        delayMillis = WAIT_AFTER_DUMP_FAILED_MILLIS
                )
            } else {
                SharkLog.d { "Failed to dump heap, will not automatically retry" }
            }
            showRetainedCountNotification(
                    objectCount = retainedReferenceCount,
                    contentText = application.getString(
                            R.string.leak_canary_notification_retained_dump_failed
                    )
            )
            return
        }
        //重置变量
        lastDisplayedRetainedObjectCount = 0
        lastHeapDumpUptimeMillis = SystemClock.uptimeMillis()
        objectWatcher.clearObjectsWatchedBefore(heapDumpUptimeMillis)
        //进行 内存快照的 分析
        HeapAnalyzerService.runAnalysis(application, heapDumpFile)
    }

    //这是在干嘛，质感及时将 id 和 name的映射关系保存起来了？？
    private fun saveResourceIdNamesToMemory() {
        val resources = application.resources
        AndroidResourceIdNames.saveToMemory(
                getResourceTypeName = { id ->
                    try {
                        resources.getResourceTypeName(id)
                    } catch (e: NotFoundException) {
                        null
                    }
                },
                getResourceEntryName = { id ->
                    try {
                        resources.getResourceEntryName(id)
                    } catch (e: NotFoundException) {
                        null
                    }
                })
    }

    fun onDumpHeapReceived(forceDump: Boolean) {
        backgroundHandler.post {
            dismissNoRetainedOnTapNotification()
            gcTrigger.runGc()
            val retainedReferenceCount = objectWatcher.retainedObjectCount
            if (!forceDump && retainedReferenceCount == 0) {
                SharkLog.d { "Ignoring user request to dump heap: no retained objects remaining after GC" }
                @Suppress("DEPRECATION")
                val builder = Notification.Builder(application)
                        .setContentTitle(
                                application.getString(R.string.leak_canary_notification_no_retained_object_title)
                        )
                        .setContentText(
                                application.getString(
                                        R.string.leak_canary_notification_no_retained_object_content
                                )
                        )
                        .setAutoCancel(true)
                        .setContentIntent(NotificationReceiver.pendingIntent(application, CANCEL_NOTIFICATION))
                val notification =
                        Notifications.buildNotification(application, builder, LEAKCANARY_LOW)
                notificationManager.notify(
                        R.id.leak_canary_notification_no_retained_object_on_tap, notification
                )
                backgroundHandler.postDelayed(
                        scheduleDismissNoRetainedOnTapNotification,
                        DISMISS_NO_RETAINED_OBJECT_NOTIFICATION_MILLIS
                )
                lastDisplayedRetainedObjectCount = 0
                return@post
            }

            SharkLog.d { "Dumping the heap because user requested it" }
            dumpHeap(retainedReferenceCount, retry = false)
        }
    }

    private fun checkRetainedCount(
            retainedKeysCount: Int,
            retainedVisibleThreshold: Int
    ): Boolean {
        val countChanged = lastDisplayedRetainedObjectCount != retainedKeysCount
        lastDisplayedRetainedObjectCount = retainedKeysCount
        if (retainedKeysCount == 0) {
            SharkLog.d { "Check for retained object found no objects remaining" }
            if (countChanged) {
                showNoMoreRetainedObjectNotification()
            }
            return true
        }

        if (retainedKeysCount < retainedVisibleThreshold) {
            if (applicationVisible || applicationInvisibleLessThanWatchPeriod) {
                showRetainedCountNotification(
                        objectCount = retainedKeysCount,
                        contentText = application.getString(
                                R.string.leak_canary_notification_retained_visible, retainedVisibleThreshold
                        )
                )
                scheduleRetainedObjectCheck(
                        reason = "found only $retainedKeysCount retained objects (< $retainedVisibleThreshold while app visible)",
                        rescheduling = true,
                        delayMillis = WAIT_FOR_OBJECT_THRESHOLD_MILLIS
                )
                return true
            }
        }
        return false
    }

    private fun scheduleRetainedObjectCheck(
            reason: String,
            rescheduling: Boolean,
            delayMillis: Long = 0L
    ) {
        val checkCurrentlyScheduledAt = checkScheduledAt
        if (checkCurrentlyScheduledAt > 0) {
            val scheduledIn = checkCurrentlyScheduledAt - SystemClock.uptimeMillis()
            SharkLog.d { "Ignoring request to check for retained objects ($reason), already scheduled in ${scheduledIn}ms" }
            return
        } else {
            val verb = if (rescheduling) "Rescheduling" else "Scheduling"
            val delay = if (delayMillis > 0) " in ${delayMillis}ms" else ""
            SharkLog.d { "$verb check for retained objects${delay} because $reason" }
        }
        //更新 checkScheduledAt
        checkScheduledAt = SystemClock.uptimeMillis() + delayMillis
        //子线程运行 checkRetainedObjects 方法
        backgroundHandler.postDelayed({
            checkScheduledAt = 0
            checkRetainedObjects(reason)
        }, delayMillis)
    }

    private fun showNoMoreRetainedObjectNotification() {
        backgroundHandler.removeCallbacks(scheduleDismissRetainedCountNotification)
        if (!Notifications.canShowNotification) {
            return
        }
        val builder = Notification.Builder(application)
                .setContentTitle(
                        application.getString(R.string.leak_canary_notification_no_retained_object_title)
                )
                .setContentText(
                        application.getString(
                                R.string.leak_canary_notification_no_retained_object_content
                        )
                )
                .setAutoCancel(true)
                .setContentIntent(NotificationReceiver.pendingIntent(application, CANCEL_NOTIFICATION))
        val notification =
                Notifications.buildNotification(application, builder, LEAKCANARY_LOW)
        notificationManager.notify(R.id.leak_canary_notification_retained_objects, notification)
        backgroundHandler.postDelayed(
                scheduleDismissRetainedCountNotification, DISMISS_NO_RETAINED_OBJECT_NOTIFICATION_MILLIS
        )
    }

    private fun showRetainedCountNotification(
            objectCount: Int,
            contentText: String
    ) {
        backgroundHandler.removeCallbacks(scheduleDismissRetainedCountNotification)
        if (!Notifications.canShowNotification) {
            return
        }
        @Suppress("DEPRECATION")
        val builder = Notification.Builder(application)
                .setContentTitle(
                        application.getString(R.string.leak_canary_notification_retained_title, objectCount)
                )
                .setContentText(contentText)
                .setAutoCancel(true)
                .setContentIntent(NotificationReceiver.pendingIntent(application, DUMP_HEAP))
        val notification =
                Notifications.buildNotification(application, builder, LEAKCANARY_LOW)
        notificationManager.notify(R.id.leak_canary_notification_retained_objects, notification)
    }

    private fun dismissRetainedCountNotification() {
        backgroundHandler.removeCallbacks(scheduleDismissRetainedCountNotification)
        notificationManager.cancel(R.id.leak_canary_notification_retained_objects)
    }

    private fun dismissNoRetainedOnTapNotification() {
        backgroundHandler.removeCallbacks(scheduleDismissNoRetainedOnTapNotification)
        notificationManager.cancel(R.id.leak_canary_notification_no_retained_object_on_tap)
    }

    companion object {
        private const val WAIT_FOR_DEBUG_MILLIS = 20_000L
        private const val WAIT_AFTER_DUMP_FAILED_MILLIS = 5_000L
        private const val WAIT_FOR_OBJECT_THRESHOLD_MILLIS = 2_000L
        private const val DISMISS_NO_RETAINED_OBJECT_NOTIFICATION_MILLIS = 30_000L
        private const val WAIT_BETWEEN_HEAP_DUMPS_MILLIS = 60_000L
    }

}