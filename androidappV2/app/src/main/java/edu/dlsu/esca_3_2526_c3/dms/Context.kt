package edu.dlsu.esca_3_2526_c3.dms

import org.json.JSONObject
import kotlin.math.hypot

/*
 * Driving context: speed (GPS), motion (MPU6050), continuous driving time.
 * Kotlin port of dms/context/duration.py, gps.py and the JSON handling of providers.py.
 * Speed source is always stated: gps (fresh fix), fixed, or assumed (no usable GPS:
 * the risk model uses context.assumedSpeedKmh, i.e. moving, so a missing sensor
 * never weakens an alert).
 */

/** Continuous driving time: pauses while stopped, resets after a 15-min break. */
class DrivingDurationTracker(private val movingSpeedKmh: Double = 5.0, private val breakResetS: Double = 900.0, private val maxStepS: Double = 5.0) {
    var continuousS = 0.0
        private set
    var totalDrivingS = 0.0
        private set
    private var stoppedS = 0.0
    private var lastT: Double? = null
    private var stopStart: Double? = null
    val breaks = mutableListOf<Pair<Double, Double>>()

    fun update(t: Double, speedKmh: Double?): Double {
        val last = lastT
        if (last == null) {
            lastT = t
            return continuousS
        }
        val dt = minOf(maxOf(0.0, t - last), maxStepS)
        lastT = t
        val moving = speedKmh == null || speedKmh >= movingSpeedKmh
        if (moving) {
            val ss = stopStart
            if (ss != null && stoppedS >= breakResetS) breaks.add(Pair(ss, t))
            stopStart = null; stoppedS = 0.0
            continuousS += dt
            totalDrivingS += dt
        } else {
            if (stopStart == null) stopStart = t - dt
            stoppedS += dt
            if (stoppedS >= breakResetS) continuousS = 0.0 // a real rest break
        }
        return continuousS
    }
}

class GpsFix(val t: Double, val lat: Double?, val lon: Double?, val speedKmh: Double?, val fix: Boolean, val hdop: Double?, val satellites: Int?) {
    val usable: Boolean get() = fix && lat != null && lon != null
}

/**
 * Readings from the sensor board's /data (06_devkit_wifi.ino) or any JSON with
 * speed_kmh / motion_g / lat / lon. Board names are accepted: kmh -> speed_kmh,
 * lng -> lon, jolt -> motion_g ("motion_g", without the vertical part, wins over "jolt").
 * With no satellite fix the board reports 0 km/h: that is NOT used as "stopped".
 */
class SensorContext(private val cfg: ContextConfig) {
    val duration = DrivingDurationTracker(cfg.movingSpeedKmh, cfg.breakResetS)
    private var lastValid: GpsFix? = null
    private var motion: Double? = null
    private var motionT = -1e9
    var fixes = 0
        private set
    var lastReadingT: Double? = null
        private set

    fun ingest(raw: JSONObject, t: Double) {
        lastReadingT = t
        fun num(vararg keys: String): Double? {
            for (k in keys) if (raw.has(k) && !raw.isNull(k)) return raw.optDouble(k).takeUnless { it.isNaN() }
            return null
        }
        num("motion_g", "jolt")?.let { motion = it; motionT = t }
        val hasGps = listOf("speed_kmh", "kmh", "lat", "lon", "lng").any { raw.has(it) }
        if (hasGps) {
            val lat = num("lat")
            val lon = num("lon", "lng")
            val speed = num("speed_kmh", "kmh")
            val fix = if (raw.has("fix")) raw.optBoolean("fix") else (lat != null || speed != null)
            val f = GpsFix(t, lat, lon, speed, fix, num("hdop"), num("sats", "satellites")?.toInt())
            fixes += 1
            if (f.usable) lastValid = f
        }
    }

    /** Context for a frame at monitoring time t (same clock as ingest). */
    fun read(t: Double): ContextReading {
        val fix = lastValid?.takeIf { t - it.t <= cfg.gpsStaleS }
        val speed = fix?.speedKmh
        val m = if (t - motionT <= cfg.gpsStaleS) motion else null
        return ContextReading(
            speedKmh = speed, motionG = m, drivingS = duration.update(t, speed),
            speedSource = if (speed != null) "gps" else "assumed",
            gpsAgeS = fix?.let { Num.pyRound(t - it.t, 2) }, latitude = fix?.lat, longitude = fix?.lon,
        )
    }
}

/** Camera aiming (dms/camera/aiming.py): angle vs the calibration limit and which way to move the camera. */
class AimAdvice(val yaw: Double?, val pitch: Double?, val offset: Double?, val limit: Double, val ok: Boolean, val horizontal: String, val vertical: String) {
    companion object {
        const val GOOD_AXIS_DEG = 10.0

        fun of(yaw: Double?, pitch: Double?, limit: Double): AimAdvice {
            if (yaw == null || pitch == null) return AimAdvice(null, null, null, limit, false, "-", "-")
            val offset = hypot(yaw, pitch)
            // yaw > 0: the face looks toward the image's right -> the camera sits to the driver's right -> move it LEFT
            val h = if (kotlin.math.abs(yaw) <= GOOD_AXIS_DEG) "good" else "move %s %.0f deg".format(if (yaw > 0) "LEFT" else "RIGHT", kotlin.math.abs(yaw))
            // pitch > 0: the face looks up -> the camera sits below the face -> move it UP
            val v = if (kotlin.math.abs(pitch) <= GOOD_AXIS_DEG) "good" else "move %s %.0f deg".format(if (pitch > 0) "UP" else "DOWN", kotlin.math.abs(pitch))
            return AimAdvice(yaw, pitch, offset, limit, offset <= limit, h, v)
        }
    }
}

/** Median over the last `window` measurements (about 1 s), so the aiming numbers do not jump. */
class CameraAimer(private val limitDeg: Double = 25.0, private val window: Int = 15) {
    private val yaws = ArrayDeque<Double>()
    private val pitches = ArrayDeque<Double>()

    fun update(yaw: Double?, pitch: Double?): AimAdvice {
        if (yaw == null || pitch == null) {
            yaws.clear(); pitches.clear()
            return AimAdvice.of(null, null, limitDeg)
        }
        yaws.addLast(yaw); pitches.addLast(pitch)
        if (yaws.size > window) { yaws.removeFirst(); pitches.removeFirst() }
        return AimAdvice.of(Num.median(yaws.toList()), Num.median(pitches.toList()), limitDeg)
    }
}
