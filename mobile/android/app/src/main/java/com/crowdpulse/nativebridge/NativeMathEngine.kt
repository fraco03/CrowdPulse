package com.crowdpulse.nativebridge

import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.atan2

data class TrackerResult(
    val filteredDistance: Double,
    val filteredBearingDeg: Double,
    val filteredBearingRad: Double,
    val confidence: Double,
    val isConverged: Boolean,
    val relativeSpeed: Double,
    val rawTrilatX: Double,
    val rawTrilatY: Double
)

/**
 * Bridge to high-performance Rust core-math library.
 * Includes a pure Kotlin fallback engine when running on emulators or before NDK compilation.
 */
class NativeMathEngine {

    companion object {
        private var isNativeLoaded = false

        init {
            try {
                System.loadLibrary("crowdpulse_math")
                isNativeLoaded = true
            } catch (e: UnsatisfiedLinkError) {
                // Fallback to pure Kotlin implementation
                isNativeLoaded = false
            }
        }
    }

    private var nativeHandle: Long = 0
    private val fallbackEngine = KotlinFallbackTracker()

    init {
        if (isNativeLoaded) {
            try {
                nativeHandle = nativeCreateTracker()
            } catch (e: Exception) {
                nativeHandle = 0
            }
        }
    }

    fun reset() {
        if (nativeHandle != 0L) {
            nativeResetTracker(nativeHandle)
        } else {
            fallbackEngine.reset()
        }
    }

    fun update(dx: Double, dy: Double, rttDistance: Double, timestamp: Double): TrackerResult {
        if (nativeHandle != 0L) {
            try {
                val jsonStr = nativeUpdateTracker(nativeHandle, dx, dy, rttDistance, timestamp)
                val json = JSONObject(jsonStr)
                return TrackerResult(
                    filteredDistance = json.optDouble("filtered_distance", rttDistance),
                    filteredBearingDeg = json.optDouble("filtered_bearing_deg", 0.0),
                    filteredBearingRad = json.optDouble("filtered_bearing_rad", 0.0),
                    confidence = json.optDouble("confidence", 0.0),
                    isConverged = json.optBoolean("is_converged", false),
                    relativeSpeed = json.optDouble("relative_speed", 0.0),
                    rawTrilatX = json.optDouble("raw_trilat_x", 0.0),
                    rawTrilatY = json.optDouble("raw_trilat_y", 0.0)
                )
            } catch (e: Exception) {
                // Fallback on error
            }
        }
        return fallbackEngine.update(dx, dy, rttDistance, timestamp)
    }

    fun destroy() {
        if (nativeHandle != 0L) {
            nativeDestroyTracker(nativeHandle)
            nativeHandle = 0
        }
    }

    // Native JNI functions
    private external fun nativeCreateTracker(): Long
    private external fun nativeDestroyTracker(ptr: Long)
    private external fun nativeResetTracker(ptr: Long)
    private external fun nativeUpdateTracker(
        ptr: Long,
        dx: Double,
        dy: Double,
        rttDistance: Double,
        timestamp: Double
    ): String
}

/**
 * Pure Kotlin mathematical implementation mirroring the Rust engine.
 */
class KotlinFallbackTracker {
    private var px = 0.0
    private var py = 10.0
    private var cumX = 0.0
    private var cumY = 0.0
    private val history = mutableListOf<Triple<Double, Double, Double>>() // (cumX, cumY, rtt)
    private var initialized = false

    fun reset() {
        px = 0.0
        py = 10.0
        cumX = 0.0
        cumY = 0.0
        history.clear()
        initialized = false
    }

    fun update(dx: Double, dy: Double, rttDistance: Double, timestamp: Double): TrackerResult {
        cumX += dx
        cumY += dy
        px -= dx
        py -= dy

        if (rttDistance > 0) {
            if (!initialized) {
                px = 0.0
                py = rttDistance
                initialized = true
            }
            history.add(Triple(cumX, cumY, rttDistance))
            if (history.size > 25) {
                history.removeAt(0)
            }
        }

        var confidence = 0.0
        var isConverged = false

        if (history.size >= 5) {
            val (x0, y0, r0) = history[0]
            var sumA11 = 0.0
            var sumA12 = 0.0
            var sumA22 = 0.0
            var sumB1 = 0.0
            var sumB2 = 0.0
            var maxDispSq = 0.0

            for (i in 1 until history.size) {
                val (xi, yi, ri) = history[i]
                val ui = xi - x0
                val vi = yi - y0
                val dispSq = ui * ui + vi * vi
                if (dispSq > maxDispSq) maxDispSq = dispSq

                val a1 = 2.0 * ui
                val a2 = 2.0 * vi
                val b = dispSq + (r0 * r0 - ri * ri)

                sumA11 += a1 * a1
                sumA12 += a1 * a2
                sumA22 += a2 * a2
                sumB1 += a1 * b
                sumB2 += a2 * b
            }

            val det = sumA11 * sumA22 - sumA12 * sumA12
            val trace = sumA11 + sumA22
            val maxDisp = sqrt(maxDispSq)

            if (det > 1e-4 && trace > 1e-4 && maxDisp >= 1.5) {
                val cond = ((4.0 * det) / (trace * trace)).coerceIn(0.0, 1.0)
                confidence = (cond * (maxDisp / 8.0).coerceAtMost(1.0)).coerceIn(0.0, 1.0)

                val xtOrigin = (sumA22 * sumB1 - sumA12 * sumB2) / det
                val ytOrigin = (sumA11 * sumB2 - sumA12 * sumB1) / det

                val targetWorldX = x0 + xtOrigin
                val targetWorldY = y0 + ytOrigin

                val solvedRelX = targetWorldX - cumX
                val solvedRelY = targetWorldY - cumY

                // Smooth blending into state
                val alpha = (0.25 * confidence).coerceIn(0.05, 0.5)
                px = (1.0 - alpha) * px + alpha * solvedRelX
                py = (1.0 - alpha) * py + alpha * solvedRelY
                isConverged = confidence > 0.6
            }
        }

        // Adjust distance scale with direct RTT if available
        if (rttDistance > 0) {
            val currentNorm = sqrt(px * px + py * py)
            if (currentNorm > 0.1) {
                val rttAlpha = 0.3
                val adjustedNorm = (1.0 - rttAlpha) * currentNorm + rttAlpha * rttDistance
                val scale = adjustedNorm / currentNorm
                px *= scale
                py *= scale
            }
        }

        val distance = sqrt(px * px + py * py)
        val bearingRad = atan2(py, px)
        var bearingDeg = Math.toDegrees(bearingRad)
        if (bearingDeg < 0) bearingDeg += 360.0

        return TrackerResult(
            filteredDistance = distance,
            filteredBearingDeg = bearingDeg,
            filteredBearingRad = bearingRad,
            confidence = confidence,
            isConverged = isConverged,
            relativeSpeed = 0.0,
            rawTrilatX = px,
            rawTrilatY = py
        )
    }
}
