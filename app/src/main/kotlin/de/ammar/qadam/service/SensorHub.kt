// UNBUILT: never built with the Android SDK. Type-checked only (android-all 17 + Compose desktop + stubs), 21 Oct 2026.
package de.ammar.qadam.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import de.ammar.qadam.SensorStatus
import de.ammar.qadam.core.config.Thresholds
import de.ammar.qadam.core.logger.RowAccumulator
import de.ammar.qadam.core.signals.AccelStepDetector
import de.ammar.qadam.core.signals.GravityFilter
import de.ammar.qadam.qlog
import kotlin.math.sqrt

/**
 * Signals 4–8 of SPEC §4: steps, motion, orientation, proximity, light.
 *
 * At start it checks which sensors the phone has (the debug view shows the result).
 * Missing step sensor → steps from the accelerometer ([AccelStepDetector] in `core`).
 * Missing gravity / linear-acceleration sensors → both from the accelerometer with a
 * low-pass filter ([GravityFilter]).
 *
 * Sensors 5–8 and the accelerometer run only while the screen is on or audio plays
 * ([start] / [stop]); a hardware step detector, if present, runs always.
 * Callbacks arrive on the main thread, like everything else in the service.
 */
class SensorHub(
    private val context: Context,
    private val t: Thresholds,
    private val acc: RowAccumulator,
    /** Called with (wall-clock ms, number of steps). */
    private val onSteps: (Long, Int) -> Unit,
) : SensorEventListener {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val accel: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gravity: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val linear: Sensor? = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val proximity: Sensor? = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val light: Sensor? = sm.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val stepDetector: Sensor? = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

    private val detector = AccelStepDetector(t)
    private val gravityFilter = GravityFilter(t["gravity.tau_s"])
    private val periodUs = (1_000_000 / t["sensors.rate_hz"]).toInt()
    private val nearCm = t["proximity.near_cm"].toFloat()

    /** True if the hardware step detector is used (present and permission granted). */
    val hardwareSteps: Boolean
        get() = stepDetector != null &&
            context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    var running = false
        private set
    private var batched = false
    private var hwStepsRegistered = false

    // Latest values, for the debug view.
    var lastMotion: Double? = null
        private set
    var lastGravity: Triple<Double, Double, Double>? = null
        private set
    var lastNear: Boolean? = null
        private set
    var lastLux: Double? = null
        private set

    fun report(): List<SensorStatus> = listOf(
        SensorStatus("Accelerometer", accel != null, if (accel != null) "used for fallbacks" else "MISSING: no steps, no motion"),
        SensorStatus(
            "Step detector",
            stepDetector != null,
            when {
                stepDetector == null -> "missing → steps from accelerometer"
                !hardwareSteps -> "present, but Activity recognition not granted → accelerometer"
                else -> "used"
            },
        ),
        SensorStatus("Gravity", gravity != null, if (gravity != null) "used" else "missing → from accelerometer"),
        SensorStatus("Linear acceleration", linear != null, if (linear != null) "used" else "missing → from accelerometer"),
        SensorStatus("Proximity", proximity != null, if (proximity != null) "used" else "missing"),
        SensorStatus("Light", light != null, if (light != null) "used" else "missing"),
    )

    /** The accelerometer is needed whenever a fallback is in use. */
    private val needAccel: Boolean get() = !hardwareSteps || gravity == null || linear == null

    /** Always-on part: the hardware step detector (cheap, works with the screen off). */
    fun startAlwaysOn() {
        if (hardwareSteps && !hwStepsRegistered && stepDetector != null) {
            hwStepsRegistered = sm.registerListener(this, stepDetector, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    /**
     * Starts sensors 5–8 (and the accelerometer fallback). With the screen off (audio only)
     * events are batched for up to 2 s, so the processor can sleep in between.
     */
    fun start(screenOn: Boolean) {
        val wantBatched = !screenOn
        if (running && batched == wantBatched) return
        if (running) unregisterTransient()
        val latencyUs = if (wantBatched) 2_000_000 else 0
        if (needAccel) accel?.let { sm.registerListener(this, it, periodUs, latencyUs) }
        gravity?.let { sm.registerListener(this, it, periodUs, latencyUs) }
        linear?.let { sm.registerListener(this, it, periodUs, latencyUs) }
        proximity?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        light?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        running = true
        batched = wantBatched
        qlog("sensors on (batched=$wantBatched, accel=$needAccel)")
    }

    fun stop() {
        if (!running) return
        unregisterTransient()
        running = false
        acc.sensorsStopped(SystemClock.elapsedRealtimeNanos())
        lastMotion = null; lastNear = null; lastLux = null
        qlog("sensors off")
    }

    private fun unregisterTransient() {
        listOfNotNull(accel, gravity, linear, proximity, light).forEach { sm.unregisterListener(this, it) }
    }

    fun shutdown() {
        stop()
        sm.unregisterListener(this)
        hwStepsRegistered = false
    }

    /** Converts a sensor timestamp (ns since boot) to wall-clock ms. */
    private fun wallMs(eventNs: Long): Long =
        System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - eventNs) / 1_000_000

    override fun onSensorChanged(e: SensorEvent) {
        val ts = e.timestamp
        val v = e.values
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val x = v[0].toDouble(); val y = v[1].toDouble(); val z = v[2].toDouble()
                if (!hardwareSteps) {
                    val n = detector.onSample(ts, x, y, z)
                    if (n > 0) onSteps(wallMs(ts), n)
                }
                if (gravity == null || linear == null) {
                    gravityFilter.update(ts, x, y, z)
                    if (gravity == null) onGravity(ts, gravityFilter.gx, gravityFilter.gy, gravityFilter.gz)
                    if (linear == null) onLinear(ts, gravityFilter.lx, gravityFilter.ly, gravityFilter.lz)
                }
            }
            Sensor.TYPE_GRAVITY -> onGravity(ts, v[0].toDouble(), v[1].toDouble(), v[2].toDouble())
            Sensor.TYPE_LINEAR_ACCELERATION -> onLinear(ts, v[0].toDouble(), v[1].toDouble(), v[2].toDouble())
            Sensor.TYPE_PROXIMITY -> {
                val range = e.sensor.maximumRange
                val near = v[0] < minOf(nearCm, range)
                lastNear = near
                acc.onProximity(ts, near)
            }
            Sensor.TYPE_LIGHT -> {
                lastLux = v[0].toDouble()
                acc.onLight(ts, v[0].toDouble())
            }
            Sensor.TYPE_STEP_DETECTOR -> onSteps(wallMs(ts), 1)
        }
    }

    private fun onGravity(ts: Long, x: Double, y: Double, z: Double) {
        lastGravity = Triple(x, y, z)
        acc.onGravity(ts, x, y, z)
    }

    private fun onLinear(ts: Long, x: Double, y: Double, z: Double) {
        lastMotion = sqrt(x * x + y * y + z * z)
        acc.onLinear(ts, x, y, z)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
}
