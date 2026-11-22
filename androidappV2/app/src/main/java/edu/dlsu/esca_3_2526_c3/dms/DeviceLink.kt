package edu.dlsu.esca_3_2526_c3.dms

import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/*
 * Links to the sensor board (06_devkit_wifi.ino) on DMS-NET (Kotlin port of
 * dms/alerts/sinks.py HttpAlertSink style "tier" and dms/context/providers.py HttpContext).
 * The HTTP call itself is passed in, so the app can send it over the Wi-Fi network
 * (DMS-NET has no internet) and the tests can fake it.
 */

/**
 * Buzzer: one GET <base>/tier?level=N per alert command (0 off, 1 LOW, 2 MEDIUM, 3 HIGH).
 * Requests go out one at a time and in order (the ESP32 serves one client at a time;
 * a stop overtaken by an older start would leave the buzzer on). A failed stop is
 * retried 3 times. close() switches the buzzer off.
 */
class BuzzerLink(private val baseUrl: String, private val http: (String) -> Unit, private val onError: (String) -> Unit = {}) {
    private val queue = LinkedBlockingQueue<Pair<String, Int>>()
    private val pending = AtomicInteger(0)
    private val running = AtomicBoolean(true)
    @Volatile var lastLevel: Int? = null
        private set
    @Volatile var failures = 0
        private set

    private val worker = Thread({
        while (running.get() || queue.isNotEmpty()) {
            val (url, tries) = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
            for (attempt in 0 until tries) {
                try {
                    http(url)
                    break
                } catch (e: Exception) {
                    if (attempt == tries - 1) {
                        failures += 1
                        onError("buzzer: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
            }
            pending.decrementAndGet()
        }
    }, "buzzer-link").apply { isDaemon = true; start() }

    fun send(cmd: AlertCommand) {
        val level = TierProtocol.level(cmd) ?: return
        val tries = if (cmd.event == "stop" || cmd.event == "unavailable") 4 else 1
        enqueue(level, tries)
    }

    fun setLevel(level: Int, tries: Int = 1) = enqueue(level, tries)

    private fun enqueue(level: Int, tries: Int) {
        lastLevel = level
        pending.incrementAndGet()
        queue.put(Pair("${baseUrl.trimEnd('/')}/tier?level=$level", tries))
    }

    /** Wait until queued requests are sent (or timeoutMs). */
    fun flush(timeoutMs: Long = 5000) {
        val end = System.currentTimeMillis() + timeoutMs
        while (pending.get() > 0 && System.currentTimeMillis() < end) Thread.sleep(20)
    }

    /** Switch the buzzer off (quitting during an alarm must not leave it sounding) and stop. */
    fun close() {
        enqueue(0, 4)
        flush()
        running.set(false)
        worker.join(1000)
    }
}

/** Polls <base>/data about once per interval and feeds the readings to a SensorContext. */
class SensorPoller(
    private val baseUrl: String,
    private val intervalMs: Long,
    private val http: (String) -> String,
    private val clock: () -> Double,
    private val context: SensorContext,
    private val lock: Any,
    private val onError: (String) -> Unit = {},
) {
    private val running = AtomicBoolean(true)
    @Volatile var lastJson: JSONObject? = null
        private set
    @Volatile var errors = 0
        private set

    private val worker = Thread({
        while (running.get()) {
            try {
                val json = JSONObject(http("${baseUrl.trimEnd('/')}/data"))
                lastJson = json
                synchronized(lock) { context.ingest(json, clock()) }
            } catch (e: Exception) {
                errors += 1 // a missed poll just makes the data older; staleness is checked on read
                if (errors % 10 == 1) onError("sensors: ${e.message ?: e.javaClass.simpleName}")
            }
            try { Thread.sleep(intervalMs) } catch (_: InterruptedException) { break }
        }
    }, "sensor-poller").apply { isDaemon = true; start() }

    fun close() {
        running.set(false)
        worker.interrupt()
    }
}
