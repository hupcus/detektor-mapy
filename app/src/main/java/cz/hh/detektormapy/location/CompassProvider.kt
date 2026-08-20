package cz.hh.detektormapy.location

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Device heading in **true** degrees (0 = north), already declination-corrected when a
 * position is known. Rotating the map to the walking direction is one of the few things
 * that make an overlaid 19th century map readable while standing in a field.
 */
@Singleton
class CompassProvider @Inject constructor(@param:ApplicationContext private val context: Context) {

    private val sensorManager: SensorManager? =
        ContextCompat.getSystemService(context, SensorManager::class.java)

    fun isAvailable(): Boolean = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null

    fun headings(currentFix: () -> Fix?): Flow<Float> = callbackFlow {
        val manager = sensorManager
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (manager == null || sensor == null) {
            close()
            return@callbackFlow
        }

        val rotation = FloatArray(9)
        val orientation = FloatArray(3)
        var last = Float.NaN

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                SensorManager.getOrientation(rotation, orientation)
                var deg = Math.toDegrees(orientation[0].toDouble()).toFloat()

                val fix = currentFix()
                if (fix != null) {
                    val field = GeomagneticField(
                        fix.lat.toFloat(),
                        fix.lon.toFloat(),
                        (fix.altitude ?: 0.0).toFloat(),
                        fix.timestamp,
                    )
                    deg += field.declination
                }
                deg = (deg + 360f) % 360f

                // Suppress jitter: the magnetometer is noisy and a twitching map is unusable.
                if (last.isNaN() || angularDistance(last, deg) > MIN_DELTA_DEG) {
                    last = deg
                    trySend(deg)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        awaitClose { manager.unregisterListener(listener) }
    }.buffer(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private companion object {
        const val MIN_DELTA_DEG = 1.5f

        fun angularDistance(a: Float, b: Float): Float {
            val d = abs(a - b) % 360f
            return if (d > 180f) 360f - d else d
        }
    }
}
