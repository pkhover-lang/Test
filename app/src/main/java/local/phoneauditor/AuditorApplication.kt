package local.phoneauditor

import android.app.Application
import android.os.Handler
import android.os.Looper
import local.phoneauditor.core.*
import local.phoneauditor.platform.*
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class AuditorApplication : Application() {
    lateinit var controller: AuditController
        private set
    override fun onCreate() {
        super.onCreate()
        controller = AuditController(this)
    }
}

/** Application-scoped state survives rotation, not process death. Disk history survives process death. */
class AuditController(private val app: Application) {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val cancelled = AtomicBoolean(false)
    private val observers = CopyOnWriteArraySet<() -> Unit>()
    private val store = HistoryStore(app)
    var report: AuditReport? = null; private set
    var history: List<HistoryEntry> = emptyList(); private set
    var busy: Boolean = false; private set
    var progress: String = "Готов к проверке"; private set
    var error: String? = null; private set
    val batterySamples = mutableListOf<BatterySample>()

    init { refreshHistory() }
    fun observe(callback: () -> Unit) { observers.add(callback) }
    fun removeObserver(callback: () -> Unit) { observers.remove(callback) }
    private fun notifyChanged() { observers.forEach { it() } }

    fun scan() {
        if (busy) return
        busy = true; cancelled.set(false); error = null; progress = "Начало проверки"
        val samples = batterySamples.toList()
        notifyChanged()
        executor.execute {
            try {
                val result = AndroidCollector(app).collect(cancelled) { label, done, total ->
                    main.post {
                        progress = if (total > 0) "$label: $done / $total" else label
                        notifyChanged()
                    }
                }.copy(batterySamples = samples)
                if (cancelled.get()) throw CancellationException()
                var saveWarning: String? = null
                try { store.save(result) } catch (e: Exception) { saveWarning = "Отчет получен, но история не сохранена: ${e.javaClass.simpleName}" }
                val updatedHistory = store.list()
                main.post {
                    report = result; history = updatedHistory; busy = false
                    error = saveWarning; progress = "Проверка завершена"; notifyChanged()
                }
            } catch (_: CancellationException) {
                main.post { busy = false; progress = "Проверка отменена; предыдущий результат сохранен"; notifyChanged() }
            } catch (e: Exception) {
                main.post {
                    busy = false; error = "Проверка не завершена: ${e.javaClass.simpleName}. " +
                        "Повтори проверку или отключи дополнительный доступ и проверь базовый режим."
                    progress = "Ошибка проверки"; notifyChanged()
                }
            }
        }
    }
    fun cancel() { cancelled.set(true); progress = "Остановка после текущего запроса Android…"; notifyChanged() }
    fun sampleBattery(): BatterySample = BatteryReader.read(app).also {
        batterySamples.add(it)
        if (batterySamples.size > 120) batterySamples.removeAt(0)
    }
    fun clearSamples() { batterySamples.clear() }
    fun refreshHistory() {
        executor.execute {
            val value = store.list()
            main.post { history = value; notifyChanged() }
        }
    }
    fun deleteHistory(onComplete: (Boolean) -> Unit) {
        if (busy) return
        executor.execute {
            val success = runCatching { store.clear(); ExportFiles.clear(app) }.isSuccess
            val remaining = store.list()
            main.post {
                history = remaining
                if (success) { report = null; batterySamples.clear() }
                else { error = "Не все локальные файлы удалось удалить. Повтори удаление или очисти данные аудитора в настройках Android." }
                notifyChanged(); onComplete(success)
            }
        }
    }
    fun prepareExport(historyName: String?, options: ExportOptions, text: Boolean, callback: (Result<ExportArtifact>) -> Unit) {
        val snapshot = report?.copy(batterySamples = batterySamples.toList())
        executor.execute {
            val result = runCatching {
                val data = if (historyName != null) store.read(historyName) else
                    snapshot?.toMap() ?: error("Сначала выполни диагностику")
                ExportFiles.prepare(app, data, options, text)
            }
            main.post { callback(result) }
        }
    }
}
