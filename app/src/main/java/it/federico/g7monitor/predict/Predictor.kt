package it.federico.g7monitor.predict

import it.federico.g7monitor.G7App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Keeps the personal forecast model up to date: it learns again from the stored readings every
 * few hours (a fraction of a second on a phone), and answers with the forecast for the newest
 * reading. Only readings from this phone are used, and nothing leaves it.
 */
class Predictor(private val app: G7App) {
    private val mutex = Mutex()
    private var trainedAt = 0L

    private val _result = MutableStateFlow<GlucoseModel.Result?>(null)
    /** Learning progress or the model in use (with its measured accuracy); null before the first run. */
    val result: StateFlow<GlucoseModel.Result?> = _result

    /** The next hour after the newest reading, or empty (still learning, old or missing readings). */
    suspend fun forecast(): List<GlucoseModel.Point> = mutex.withLock {
        withContext(Dispatchers.Default) {
            val now = System.currentTimeMillis()
            val recent = app.readings.since(now - 2 * 3_600_000L)
            if (_result.value == null || now - trainedAt > RETRAIN_MS) {
                val history = app.readings.since(now - GlucoseModel.TRAIN_DAYS * 24 * 3_600_000L)
                _result.value = GlucoseModel.train(
                    LongArray(history.size) { history[it].timestamp }, IntArray(history.size) { history[it].mgdl },
                )
                trainedAt = now
            }
            val model = (_result.value as? GlucoseModel.Result.Ready)?.model ?: return@withContext emptyList()
            GlucoseModel.forecast(model, LongArray(recent.size) { recent[it].timestamp }, IntArray(recent.size) { recent[it].mgdl }, now)
        }
    }

    private companion object {
        const val RETRAIN_MS = 6 * 3_600_000L
    }
}
