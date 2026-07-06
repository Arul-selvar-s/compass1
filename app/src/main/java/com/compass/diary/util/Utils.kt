package com.compass.diary.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// ── COMPASS SENSOR ────────────────────────────────────────────────
@Singleton
class CompassSensorManager @Inject constructor(@ApplicationContext private val ctx: Context) {
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotVec = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val mag    = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val accel  = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    fun headingFlow(): Flow<Float> = callbackFlow {
        val grav = FloatArray(3)
        val geo  = FloatArray(3)
        var smooth = 0f

        val listener = object : SensorEventListener {
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
            override fun onSensorChanged(ev: SensorEvent) {
                when (ev.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        val rm = FloatArray(9); SensorManager.getRotationMatrixFromVector(rm, ev.values)
                        val or = FloatArray(3); SensorManager.getOrientation(rm, or)
                        val h  = (Math.toDegrees(or[0].toDouble()).toFloat() + 360f) % 360f
                        smooth = lpf(smooth, h); trySend(smooth)
                    }
                    Sensor.TYPE_ACCELEROMETER -> { lpf3(ev.values, grav); emit2(grav, geo) }
                    Sensor.TYPE_MAGNETIC_FIELD -> { System.arraycopy(ev.values, 0, geo, 0, 3); emit2(grav, geo) }
                }
            }
            private fun emit2(g: FloatArray, m: FloatArray) {
                if (g.all { it == 0f }) return
                val R = FloatArray(9); val I = FloatArray(9)
                if (SensorManager.getRotationMatrix(R, I, g, m)) {
                    val or = FloatArray(3); SensorManager.getOrientation(R, or)
                    val h = (Math.toDegrees(or[0].toDouble()).toFloat() + 360f) % 360f
                    smooth = lpf(smooth, h); trySend(smooth)
                }
            }
        }
        if (rotVec != null) sm.registerListener(listener, rotVec, SensorManager.SENSOR_DELAY_UI)
        else { mag?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
               accel?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) } }
        awaitClose { sm.unregisterListener(listener) }
    }

    private fun lpf(cur: Float, nv: Float, a: Float = 0.15f): Float {
        var d = nv - cur; if (d > 180f) d -= 360f; if (d < -180f) d += 360f
        return (cur + a * d + 360f) % 360f
    }
    private fun lpf3(inp: FloatArray, out: FloatArray, a: Float = 0.15f) {
        for (i in inp.indices) out[i] = out[i] + a * (inp[i] - out[i])
    }
}

// ── AUTO-SAVE ─────────────────────────────────────────────────────
enum class SaveState { IDLE, SAVING, SAVED, ERROR }

@Singleton
class AutoSaveManager @Inject constructor() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _state = MutableStateFlow(SaveState.IDLE)
    val saveState: StateFlow<SaveState> = _state
    private var job: Job? = null

    fun onContentChanged(block: suspend () -> Unit) {
        job?.cancel(); _state.value = SaveState.SAVING
        job = scope.launch {
            delay(800)
            try {
                block(); _state.value = SaveState.SAVED; delay(2000); _state.value = SaveState.IDLE
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                _state.value = SaveState.ERROR
            }
        }
    }

    suspend fun forceSave(block: suspend () -> Unit) {
        job?.cancel()
        try {
            _state.value = SaveState.SAVING
            kotlinx.coroutines.withContext(Dispatchers.IO) { block() }
            _state.value = SaveState.SAVED
        } catch (e: Exception) {
            _state.value = SaveState.ERROR
        }
    }

    fun dispose() { job?.cancel(); scope.cancel() }
}

// ── BOOT + REMINDER RECEIVERS ─────────────────────────────────────
class BootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: android.content.Context, intent: android.content.Intent) {
        // Reminders are simply re-shown via DataStore-backed state; nothing to reschedule here.
    }
}

class ReminderReceiver : android.content.BroadcastReceiver() {
    companion object {
        const val CHANNEL_ID  = "compass_reminders"
        const val EXTRA_TITLE = "r_title"
        const val EXTRA_NOTE  = "r_note"
        const val EXTRA_ID    = "r_id"
    }
    override fun onReceive(ctx: android.content.Context, intent: android.content.Intent) {
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Compass Reminder"
        val note  = intent.getStringExtra(EXTRA_NOTE)  ?: ""
        val id    = intent.getLongExtra(EXTRA_ID, 0L)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val ch = android.app.NotificationChannel(CHANNEL_ID, "Compass Reminders", android.app.NotificationManager.IMPORTANCE_DEFAULT)
            ctx.getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(ch)
        }
        val n = androidx.core.app.NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(note.ifBlank { "Time to write!" })
            .setAutoCancel(true).build()
        ctx.getSystemService(android.app.NotificationManager::class.java).notify(id.toInt(), n)
    }
}
