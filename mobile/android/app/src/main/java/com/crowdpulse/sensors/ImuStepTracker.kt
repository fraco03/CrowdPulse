package com.crowdpulse.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

interface ImuStepListener {
    fun onStep(dx: Double, dy: Double, azimuthRad: Double, timestamp: Double)
    fun onOrientationUpdate(azimuthDeg: Double, pitchDeg: Double, rollDeg: Double)
}

/**
 * High-frequency IMU sensor fusion engine for dead-reckoning user displacements.
 */
class ImuStepTracker(
    context: Context,
    private val listener: ImuStepListener
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val stepDetectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    private var currentAzimuthRad: Double = 0.0
    private var defaultStepLengthMeters: Double = 0.75

    // Fallback step detector using accelerometer magnitude peak detection
    private var lastAccelMagnitude = 9.8
    private var lastStepTimestamp: Long = 0
    private val stepCooldownMs: Long = 300

    fun start() {
        rotationVectorSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        if (stepDetectorSensor != null) {
            sensorManager.registerListener(this, stepDetectorSensor, SensorManager.SENSOR_DELAY_FASTEST)
        } else {
            // Fallback to accelerometer
            accelSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)

                // Azimuth in radians [-PI, PI], 0 = North, PI/2 = East
                currentAzimuthRad = orientationAngles[0].toDouble()
                var azimuthDeg = Math.toDegrees(currentAzimuthRad)
                if (azimuthDeg < 0) azimuthDeg += 360.0

                val pitchDeg = Math.toDegrees(orientationAngles[1].toDouble())
                val rollDeg = Math.toDegrees(orientationAngles[2].toDouble())

                listener.onOrientationUpdate(azimuthDeg, pitchDeg, rollDeg)
            }

            Sensor.TYPE_STEP_DETECTOR -> {
                handleStepDetected(defaultStepLengthMeters)
            }

            Sensor.TYPE_ACCELEROMETER -> {
                if (stepDetectorSensor == null) {
                    val ax = event.values[0]
                    val ay = event.values[1]
                    val az = event.values[2]
                    val mag = sqrt((ax * ax + ay * ay + az * az).toDouble())

                    val now = System.currentTimeMillis()
                    // Peak detection: crossing 11.5 m/s^2 upwards
                    if (mag > 11.5 && lastAccelMagnitude <= 11.5 && (now - lastStepTimestamp) > stepCooldownMs) {
                        lastStepTimestamp = now
                        handleStepDetected(defaultStepLengthMeters)
                    }
                    lastAccelMagnitude = mag
                }
            }
        }
    }

    private fun handleStepDetected(stepLength: Double) {
        // Convert polar displacement to local Cartesian coordinates
        // dx = East component, dy = North component
        val dx = stepLength * sin(currentAzimuthRad)
        val dy = stepLength * cos(currentAzimuthRad)
        val timestamp = System.currentTimeMillis() / 1000.0

        listener.onStep(dx, dy, currentAzimuthRad, timestamp)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }
}
