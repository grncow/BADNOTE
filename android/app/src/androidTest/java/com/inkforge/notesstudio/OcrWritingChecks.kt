package com.inkforge.notesstudio

import android.os.Debug
import android.os.Process
import android.os.Bundle
import android.view.MotionEvent
import org.json.JSONArray
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Same deterministic workload for the PR #10 baseline and the contact-gated build. */
object OcrWritingChecks {
    fun run(activity: MainActivity, motion: (Int) -> Unit, baseline: Boolean, result: Bundle) {
        val coordinator = MainActivity::class.java.getDeclaredField("pageOcr").apply { isAccessible = true }
            .get(activity) as PageOcrCoordinator
        val service = PageOcrCoordinator::class.java.getDeclaredField("recognition").apply { isAccessible = true }
            .get(coordinator) as RecognitionService
        val queue = PageOcrCoordinator::class.java.getDeclaredField("queue").apply { isAccessible = true }
            .get(coordinator) as OcrRequestQueue
        val priority = FutureTask { Process.getThreadPriority(Process.myTid()) }
        service.executor.execute(priority)
        val nativePriority = priority.get(5, TimeUnit.SECONDS)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(9)
        service.executor.execute { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        check(entered.await(5, TimeUnit.SECONDS))
        var admitted = -1
        try {
            motion(MotionEvent.ACTION_DOWN)
            // Missing page IDs isolate admission from SDK/model availability. No release data is used.
            repeat(8) { index -> coordinator.request("writing-probe", "writing-$index", "ko-primary", true) {
                check(!it.applied); completed.countDown()
            } }
            coordinator.request("writing-probe", "writing-manual", "ko-primary", false) { completed.countDown() }
            val lock = OcrRequestQueue::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(queue)
            val pending = OcrRequestQueue::class.java.getDeclaredField("pending").apply { isAccessible = true }
            synchronized(lock) {
                @Suppress("UNCHECKED_CAST")
                val requests = (pending.get(queue) as Map<String, OcrRequestQueue.Request>).values
                admitted = requests.count { it.automatic }
                check(requests.any { !it.automatic }) { "Writing rejected an explicit manual request" }
            }
        } finally { release.countDown(); motion(MotionEvent.ACTION_UP) }
        check(completed.await(10, TimeUnit.SECONDS))
        val resumed = CountDownLatch(1)
        coordinator.request("writing-probe", "resumed", "ko-primary", true) { resumed.countDown() }
        check(resumed.await(5, TimeUnit.SECONDS)) { "Automatic requests did not resume after contact" }
        val layout = layoutWorkload(service)
        result.putInt("writingAutoAdmitted", admitted)
        result.putInt("writingWorkerPriority", nativePriority)
        result.putInt("writingLayoutJobs", layout.first)
        result.putLong("writingLayoutCpuUs", layout.second / 1_000)
        result.putString("writingWorkload", "24_requests_300_strokes_3600_points_contact_held")
        if (baseline) check(admitted == 8 && layout.first == 24) { "Baseline workload changed" }
        else {
            check(admitted == 0 && layout.first == 0) { "Automatic OCR competed with active contact" }
            check(nativePriority == Process.THREAD_PRIORITY_BACKGROUND) { "OCR worker is not background priority" }
        }
    }

    private fun layoutWorkload(service: RecognitionService): Pair<Int, Long> {
        val strokes = (0 until 300).map { index ->
            val x = (index % 20) * 43f + 20f
            val y = (index / 20) * 70f + 20f
            json("id" to "probe-$index", "type" to "stroke", "brush" to "fountain", "width" to 3,
                "points" to JSONArray((0 until 12).map { sample ->
                    InkPoint(x + sample * 2f, y + (sample % 3) * 5f).json()
                }))
        }
        val jobs = AtomicInteger()
        var cpuNs = 0L
        lateinit var queue: OcrRequestQueue
        queue = OcrRequestQueue(service.executor) { request ->
            if (!queue.current(request)) PageOcrCoordinator.Receipt(false, null, "cancelled")
            else {
                val start = Debug.threadCpuTimeNanos()
                check(PageOcrLayout.analyze(strokes, 1000f, 1414f).regions.isNotEmpty())
                cpuNs += Debug.threadCpuTimeNanos() - start
                jobs.incrementAndGet()
                PageOcrCoordinator.Receipt(true, null)
            }
        }
        // Reflective lookup keeps this exact test source runnable before the production fix exists.
        val contact = OcrRequestQueue::class.java.methods.firstOrNull { it.name == "setWriting" }
        try {
            val warm = FutureTask { PageOcrLayout.analyze(strokes, 1000f, 1414f); Unit }
            service.executor.execute(warm); warm.get(10, TimeUnit.SECONDS)
            contact?.invoke(queue, true)
            repeat(24) {
                val completed = CountDownLatch(1)
                queue.request("probe", "page", "ko-primary", true, { _, _, _ -> }) { completed.countDown() }
                check(completed.await(10, TimeUnit.SECONDS))
            }
            contact?.invoke(queue, false)
            return jobs.get() to cpuNs
        } finally { queue.close() }
    }
}
