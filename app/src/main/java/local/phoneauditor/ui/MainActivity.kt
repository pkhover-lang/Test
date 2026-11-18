@file:Suppress("DEPRECATION")
package local.phoneauditor.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.*
import local.phoneauditor.AuditController
import local.phoneauditor.AuditorApplication
import local.phoneauditor.core.*
import local.phoneauditor.platform.*
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val bg = Color.rgb(11, 18, 32)
    private val panel = Color.rgb(21, 33, 52)
    private val ink = Color.rgb(235, 242, 250)
    private val muted = Color.rgb(161, 178, 202)
    private val accent = Color.rgb(101, 224, 206)
    private val warning = Color.rgb(255, 196, 112)
    private val danger = Color.rgb(255, 145, 145)
    private lateinit var controller: AuditController
    private lateinit var content: FrameLayout
    private lateinit var nav: LinearLayout
    private lateinit var statusLine: TextView
    private var tab = 0
    private var search = ""
    private var showSystem = false
    private var sortOrder = 0
    private var selectedHistory: String? = null
    private var pendingExportName: String? = null
    private var includeApps = true
    private var includeUsage = false
    private var includeNetwork = false
    private var exportBusy = false
    private var monitoring = false
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val observer: () -> Unit = {
        statusLine.text = controller.progress
        if (!controller.busy || tab == 0) render()
    }
    private val sampler = object : Runnable {
        override fun run() {
            if (!monitoring) return
            controller.sampleBattery()
            if (tab == 2) render()
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = (application as AuditorApplication).controller
        tab = savedInstanceState?.getInt("tab", 0) ?: 0
        selectedHistory = savedInstanceState?.getString("history")
        pendingExportName = savedInstanceState?.getString("pendingExport")
        search = savedInstanceState?.getString("search").orEmpty()
        showSystem = savedInstanceState?.getBoolean("system", false) ?: false
        sortOrder = savedInstanceState?.getInt("sort", 0) ?: 0
        includeApps = savedInstanceState?.getBoolean("includeApps", true) ?: true
        includeUsage = savedInstanceState?.getBoolean("includeUsage", false) ?: false
        includeNetwork = savedInstanceState?.getBoolean("includeNetwork", false) ?: false
        window.setDecorFitsSystemWindows(false)
        val root = vertical().apply { setBackgroundColor(bg) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val header = vertical(20).apply {
            addView(text("AI  /  PHONE AUDITOR", 13, accent, true))
            addView(text("Твой телефон. Твои данные.", 23, ink, true))
            addView(text("Без сети · без root · без автоматической очистки", 12, muted))
        }
        root.addView(header)
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        statusLine = text(controller.progress, 11, muted).apply { setPadding(dp(16), dp(5), dp(16), dp(5)) }
        root.addView(statusLine)
        nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(4), dp(8), dp(8)) }
        val strip = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(nav) }
        root.addView(strip)
        setContentView(root)
        render()
    }
    override fun onStart() { super.onStart(); controller.observe(observer); render() }
    override fun onResume() { super.onResume(); if (::content.isInitialized) render() }
    override fun onStop() { controller.removeObserver(observer); super.onStop() }
    override fun onPause() {
        monitoring = false; handler.removeCallbacks(sampler)
        super.onPause()
    }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); io.shutdown(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("tab", tab); outState.putString("history", selectedHistory)
        outState.putString("pendingExport", pendingExportName); outState.putString("search", search)
        outState.putBoolean("system", showSystem); outState.putInt("sort", sortOrder)
        outState.putBoolean("includeApps", includeApps); outState.putBoolean("includeUsage", includeUsage)
        outState.putBoolean("includeNetwork", includeNetwork)
        super.onSaveInstanceState(outState)
    }

    private fun render() {
        if (!::content.isInitialized || isDestroyed) return
        content.removeAllViews()
        nav.removeAllViews()
        listOf("Обзор", "Приложения", "Батарея", "Действия", "История", "Отчет").forEachIndexed { index, title ->
            nav.addView(button(title, index == tab) { tab = index; render() },
                LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        }
        statusLine.text = controller.progress
        when (tab) {
            0 -> overview()
            1 -> appList()
            2 -> batteryPage()
            3 -> actionsPage()
            4 -> historyPage()
            else -> exportPage()
        }
    }

    private fun overview() {
        val page = page("Обзор устройства", "Реальные данные Android. Выводы — только в пределах доступного.")
        val c = card(page)
        c.addView(text(if (controller.busy) "Идет диагностика" else "Локальная проверка", 21, ink, true))
        c.addView(text("Собирается список приложений и техническая статистика текущего профиля. " +
            "До 10 проверок сохраняются только здесь. Личные сообщения, фото и учетные записи не читаются.", 14))
        if (controller.busy) {
            c.addView(ProgressBar(this).apply { isIndeterminate = true })
            c.addView(text(controller.progress, 14, accent))
            c.addView(button("Остановить проверку") { controller.cancel() })
        } else c.addView(button(if (controller.report == null) "Начать диагностику" else "Повторить диагностику", true) { beginScan() })
        controller.error?.let { note(page, it, warning) }
        if (!AccessCheck.usage(this)) {
            val access = card(page)
            access.addView(text("Дополнительные показатели", 17, ink, true))
            access.addView(text("Доступ к статистике использования нужен для времени активности, размеров данных " +
                "приложений и статистики трафика. Без него базовая проверка продолжит работать. " +
                "После выдачи доступа запусти проверку заново.", 14))
            access.addView(button("Открыть доступ к статистике") { usageSettings() })
        }
        val report = controller.report
        if (report == null) {
            note(page, "Это аудитор разрешений и ресурсов, а не антивирус с базой вредоносных файлов. " +
                "Надписи «вирусов нет» здесь не будет: отсутствие сигналов ничего не гарантирует.", muted)
            if (controller.history.isNotEmpty()) page.addView(button("Открыть сохраненную историю") { tab = 4; render() })
            return
        }
        val summary = card(page)
        summary.addView(text("Результат от ${date(report.completedMs)}", 13, muted))
        val review = report.apps.count { it.priority.rank > 0 }
        summary.addView(text("$review приложений для проверки", 24, if (review > 0) warning else ink, true))
        summary.addView(text("Это приоритет ручной проверки, не количество вирусов. Проверено ${report.apps.size} пакетов.", 14))
        summary.addView(button("Посмотреть приложения") { tab = 1; render() })
        val system = card(page)
        system.addView(text("${report.device["manufacturer"]} ${report.device["model"]}", 19, ink, true))
        row(system, "Система", "Android ${report.device["android_release"]} · API ${report.device["api_level"]}")
        row(system, "Патч безопасности", report.device["security_patch"].toString())
        row(system, "Заряд", report.battery.levelPercent?.let { "$it%" } ?: "Недоступно")
        row(system, "Доступная RAM", DisplayFormat.bytes(number(report.memory, "available_bytes")))
        row(system, "Всего RAM", DisplayFormat.bytes(number(report.memory, "total_bytes")))
        row(system, "Свободно на накопителе", DisplayFormat.bytes(number(report.storage, "available_bytes")))
        row(system, "Всего на разделе данных", DisplayFormat.bytes(number(report.storage, "total_bytes")))
        row(system, "Ядер доступно процессу", report.device["processors_available_to_process"].toString())
        row(system, "Защищенная блокировка", DisplayFormat.yesNo(report.security["secure_screen_lock"] as? Boolean))
        note(page, "Показатели выше — снимок на момент проверки. Свободная RAM не равна оценке скорости. " +
            "Точная загрузка CPU и память чужих процессов не измеряются.", muted)
        report.findings.forEach { findingCard(page, it) }
        if (report.errors.isNotEmpty()) note(page, "Не все данные получены: ${report.errors.size} замечаний. " +
            "Причины и полнота проверки включены в отчет.", warning)
        page.addView(button("Подготовить отчет", true) { selectedHistory = null; tab = 5; render() })
    }

    private fun beginScan() {
        val prefs = getSharedPreferences("consent", MODE_PRIVATE)
        if (prefs.getBoolean("local_scan", false)) { controller.scan(); return }
        AlertDialog.Builder(this).setTitle("Локальная диагностика")
            .setMessage("Приложение прочитает доступные сведения об установленных программах и сохранит отчет во внутренней памяти. " +
                "Список программ может раскрывать личные сведения. Автоматической отправки нет. " +
                "Отчеты удаляются в разделе «История» или при удалении приложения.")
            .setNegativeButton("Отмена", null).setPositiveButton("Начать") { _, _ ->
                prefs.edit().putBoolean("local_scan", true).apply(); controller.scan()
            }.show()
    }

    private fun appList() {
        val report = controller.report
        if (report == null) { val p = page("Приложения", "Сначала запусти диагностику."); p.addView(button("Перейти к проверке", true) { tab = 0; render() }); return }
        val layout = vertical(16)
        layout.addView(text("Приложения", 25, ink, true))
        layout.addView(text("Разрешения, место, активность. Нажми на приложение для деталей.", 13, muted))
        val input = EditText(this).apply {
            hint = "Название или пакет"; setTextColor(ink); setHintTextColor(muted)
            setSingleLine(true); setText(search); textSize = 15f
            background = rounded(panel); setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        layout.addView(input, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val system = CheckBox(this).apply { text = "Показывать системные (${report.apps.count { it.system }})"; setTextColor(muted); isChecked = showSystem }
        layout.addView(system)
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Приоритет проверки", "Размер на накопителе", "Время на экране", "Сервис переднего плана", "Трафик UID", "Название"))
        spinner.setSelection(sortOrder)
        layout.addView(spinner, LinearLayout.LayoutParams(-1, dp(48)))
        val counter = text("", 12, muted); layout.addView(counter)
        val list = ListView(this).apply { dividerHeight = dp(7); setBackgroundColor(bg) }
        val adapter = AppAdapter()
        list.adapter = adapter
        layout.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(layout, FrameLayout.LayoutParams(-1, -1))
        fun update() {
            var values = report.apps.filter {
                (showSystem || !it.system) && (search.isBlank() || it.label.contains(search, true) || it.packageName.contains(search, true))
            }
            values = when (sortOrder) {
                1 -> values.sortedByDescending { it.storage?.totalBytes ?: -1L }
                2 -> values.sortedByDescending { it.usage?.foregroundMs ?: -1L }
                3 -> values.sortedByDescending { it.usage?.foregroundServiceMs ?: -1L }
                4 -> values.sortedByDescending { it.traffic?.knownTotal ?: -1L }
                5 -> values.sortedBy { it.label.lowercase(Locale.ROOT) }
                else -> values.sortedWith(compareByDescending<AppRecord> { it.priority.rank }.thenBy { it.label.lowercase(Locale.ROOT) })
            }
            adapter.items = values; adapter.notifyDataSetChanged()
            counter.text = "Показано ${values.size} из ${report.apps.size}. Значения без доступа не заменяются нулями."
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { search = s.toString(); update() }
            override fun afterTextChanged(s: Editable?) {}
        })
        system.setOnCheckedChangeListener { _, checked -> showSystem = checked; update() }
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { sortOrder = position; update() }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        list.setOnItemClickListener { _, _, position, _ -> appDetails(adapter.items[position]) }
        update()
    }

    private inner class AppAdapter : BaseAdapter() {
        var items: List<AppRecord> = emptyList()
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val box = (convertView as? LinearLayout) ?: vertical(14).apply { background = rounded(panel) }
            box.removeAllViews()
            val item = items[position]
            box.addView(text(item.label, 17, ink, true))
            box.addView(text(item.packageName, 11, muted))
            box.addView(text(item.priority.label, 12, priorityColor(item.priority), true))
            val detail = when (sortOrder) {
                2 -> "На экране: ${DisplayFormat.duration(item.usage?.foregroundMs)}"
                3 -> "Foreground service: ${DisplayFormat.duration(item.usage?.foregroundServiceMs)}"
                4 -> "Трафик UID: ${DisplayFormat.bytes(item.traffic?.knownTotal)}" +
                    if ((item.traffic?.sharedUidPackages ?: 1) > 1) " · общий UID" else ""
                else -> "Накопитель: ${DisplayFormat.bytes(item.storage?.totalBytes)} · ${item.permissions.size} разрешений"
            }
            box.addView(text(detail, 12, muted))
            return box
        }
    }

    private fun appDetails(app: AppRecord) {
        val body = vertical(16)
        body.addView(text(app.packageName, 12, muted))
        body.addView(text(app.priority.label, 16, priorityColor(app.priority), true))
        note(body, "Приоритет — не диагноз вируса. Даже отсутствие сигналов не подтверждает безопасность.", muted)
        val meta = card(body)
        row(meta, "Версия", "${app.version ?: "?"} (${app.versionCode})")
        row(meta, "Системное", DisplayFormat.yesNo(app.system))
        row(meta, "Включено", DisplayFormat.yesNo(app.enabled))
        row(meta, "targetSdk", app.targetSdk.toString())
        row(meta, "Источник установки", app.installer ?: "Android не сообщил")
        row(meta, "Установлено", date(app.firstInstallMs))
        row(meta, "Обновлено", date(app.lastUpdateMs))
        body.addView(button("Открыть системные настройки приложения", true) { appSettings(app.packageName) })
        val resources = card(body)
        resources.addView(text("Ресурсы", 18, ink, true))
        row(resources, "Приложение на диске", DisplayFormat.bytes(app.storage?.appBytes))
        row(resources, "Данные на диске", DisplayFormat.bytes(app.storage?.dataBytes))
        row(resources, "Кэш (внутри данных)", DisplayFormat.bytes(app.storage?.cacheBytes))
        row(resources, "На экране", DisplayFormat.duration(app.usage?.foregroundMs))
        row(resources, "Сервис переднего плана", DisplayFormat.duration(app.usage?.foregroundServiceMs))
        row(resources, "Трафик UID", DisplayFormat.bytes(app.traffic?.knownTotal))
        row(resources, "Точный расход батареи", "Недоступно без системных прав")
        row(resources, "Оперативная память", "Недоступно для чужих процессов")
        if (app.usage != null) resources.addView(text("Границы возвращенных корзин: ${date(app.usage.bucketStartMs)} — ${date(app.usage.bucketEndMs)}. " +
            "Запрашиваются последние 7 дней; корзины Android могут выходить за границы.", 12, muted))
        if ((app.traffic?.sharedUidPackages ?: 1) > 1) resources.addView(text("Общий UID у ${app.traffic?.sharedUidPackages} пакетов. " +
            "Показанный трафик относится ко всему UID — не суммируй его по этим приложениям.", 13, warning))
        resources.addView(text("Время на экране и foreground service не показывают расход энергии или все фоновые процессы.", 13, muted))
        val special = card(body)
        special.addView(text("Особые возможности", 18, ink, true))
        row(special, "Специальные возможности", DisplayFormat.yesNo(app.accessibilityEnabled))
        row(special, "Чтение уведомлений", DisplayFormat.yesNo(app.notificationsEnabled))
        row(special, "Администратор", DisplayFormat.yesNo(app.deviceAdminActive))
        row(special, "Исключено из оптимизации", DisplayFormat.yesNo(app.batteryOptimizationExempt))
        row(special, "RECEIVE_BOOT_COMPLETED", if (app.bootPermissionDeclared) "Объявлено; запуск не наблюдался" else "Не объявлено")
        row(special, "Сервисов в манифесте", "${app.declaredServiceCount} · не число запущенных")
        special.addView(button("Проверить наложение поверх окон") { settings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, app.packageName) })
        special.addView(button("Проверить установку неизвестных APK") { settings(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, app.packageName) })
        app.findings.forEach { findingCard(body, it) }
        val permissions = card(body)
        permissions.addView(text("Все разрешения (${app.permissions.size})", 18, ink, true))
        permissions.addView(text("«PM: выдано» — флаг PackageManager. Он не гарантирует эффективный доступ: " +
            "AppOps, доступ «только при использовании» и другие ограничения отдельно не проверены. " +
            "Для специальных доступов требуется ручная проверка.", 12, muted))
        app.permissions.sortedWith(compareByDescending<PermissionRecord> { it.special || it.dangerous }
            .thenBy { it.name }).forEach { permission ->
            val color = if (permission.special || (permission.dangerous && permission.grantedByPackageManager)) warning else muted
            permissions.addView(text(permission.label, 14, color, true))
            permissions.addView(text(permission.name, 11, muted))
            permissions.addView(text(if (permission.special) "Объявлено · выдача не определена этим флагом" else
                "PM: ${if (permission.grantedByPackageManager) "выдано" else "не выдано"}" +
                    if (permission.dangerous) " · чувствительное разрешение" else "", 11, muted))
            space(permissions, 8)
        }
        if (app.notes.isNotEmpty()) note(body, app.notes.joinToString("\n"), warning)
        showBody(app.label, body)
    }

    private fun batteryPage() {
        val page = page("Батарея", "Измерения устройства, без выдуманного процента износа.")
        val sample = controller.batterySamples.lastOrNull() ?: controller.report?.battery ?: BatteryReader.read(this)
        val c = card(page)
        c.addView(text(sample.levelPercent?.let { "$it%" } ?: "Недоступно", 42, accent, true))
        row(c, "Измерение", date(sample.timestampMs))
        row(c, "Температура аккумулятора", sample.temperatureC?.let { "%.1f °C".format(Locale("ru"), it) } ?: "Недоступно")
        row(c, "Напряжение", sample.voltageMv?.let { "$it мВ" } ?: "Недоступно")
        row(c, "Ток контроллера", sample.currentUa?.let { "%.0f мА".format(Locale("ru"), it / 1000.0) } ?: "Не поддерживается")
        row(c, "Оставшийся заряд", sample.chargeCounterUah?.let { "%.0f мА·ч".format(Locale("ru"), it / 1000.0) } ?: "Не поддерживается")
        row(c, "Статус зарядки", when (sample.chargingStatus) { "CHARGING" -> "Заряжается"; "DISCHARGING" -> "Разряжается"; "FULL" -> "Полный заряд"; "NOT_CHARGING" -> "Не заряжается"; else -> "Неизвестно" })
        row(c, "Подключение", sample.plugged)
        row(c, "Статус контроллера", sample.health)
        row(c, "Тепловой статус Android", sample.thermalStatus?.let { thermalName(it) } ?: "Недоступно")
        row(c, "Процент износа", "Не измеряется")
        note(page, "GOOD — не утверждение «батарея как новая». Оставшийся заряд — не полная емкость. " +
            "Ток и температура относятся ко всему телефону; по ним нельзя назвать виновное приложение.", muted)
        val monitoringCard = card(page)
        monitoringCard.addView(text("Наблюдение в открытом приложении", 18, ink, true))
        monitoringCard.addView(text("Один снимок каждые 30 секунд, не более 120 точек. При сворачивании, блокировке " +
            "экрана или повороте наблюдение останавливается. Никаких фоновых служб и удержания экрана.", 13, muted))
        monitoringCard.addView(button(if (monitoring) "Остановить наблюдение" else "Начать наблюдение", !monitoring) {
            monitoring = !monitoring
            handler.removeCallbacks(sampler)
            if (monitoring) sampler.run() else render()
        })
        monitoringCard.addView(text("${controller.batterySamples.size} точек · ${if (monitoring) "наблюдение включено" else "остановлено"}", 13, accent))
        if (controller.batterySamples.size >= 2) {
            monitoringCard.addView(BatteryChart(controller.batterySamples.toList()), LinearLayout.LayoutParams(-1, dp(150)))
            monitoringCard.addView(text("График уровня заряда, шкала 0–100%. Не прогноз времени работы.", 11, muted))
        }
        if (controller.batterySamples.isNotEmpty()) monitoringCard.addView(button("Очистить точки") {
            controller.clearSamples(); render()
        })
        page.addView(button("Системная статистика батареи") { settings("android.intent.action.POWER_USAGE_SUMMARY") })
        note(page, "Для подробного разбора расхода по приложениям используй штатную статистику батареи. " +
            "Аудитор не может получить ее полный эквивалент через обычные API.", warning)
    }

    private inner class BatteryChart(private val samples: List<BatterySample>) : View(this@MainActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        init { contentDescription = "График заряда: ${samples.firstOrNull()?.levelPercent ?: "?"} — ${samples.lastOrNull()?.levelPercent ?: "?"} процентов" }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat() - dp(14)
            paint.color = Color.rgb(49, 66, 89); paint.strokeWidth = dp(1).toFloat()
            for (i in 0..4) canvas.drawLine(0f, h * i / 4, w, h * i / 4, paint)
            val first = samples.first().timestampMs; val span = (samples.last().timestampMs - first).coerceAtLeast(1)
            val path = Path(); var started = false
            samples.forEach { s ->
                val level = s.levelPercent
                if (level == null) { started = false; return@forEach }
                val x = (s.timestampMs - first).toFloat() / span * w
                val y = h - level / 100f * h
                if (started) path.lineTo(x, y) else { path.moveTo(x, y); started = true }
            }
            paint.color = accent; paint.strokeWidth = dp(3).toFloat(); paint.style = Paint.Style.STROKE
            canvas.drawPath(path, paint); paint.style = Paint.Style.FILL
        }
    }

    private fun actionsPage() {
        val page = page("Проверить и настроить", "Никаких автоматических удалений и отключений.")
        val c = card(page)
        c.addView(text("Дополнительный доступ аудитора", 18, ink, true))
        c.addView(text("Статистика использования: ${if (AccessCheck.usage(this)) "разрешена" else "не разрешена"}. " +
            "Это единственный необязательный специальный доступ, который нужен самому аудитору.", 14))
        c.addView(button("Разрешить или отозвать статистику") { usageSettings() })
        val targets = card(page)
        targets.addView(text("Штатные настройки Android", 18, ink, true))
        targets.addView(button("Специальные возможности") { settings(Settings.ACTION_ACCESSIBILITY_SETTINGS) })
        targets.addView(button("Доступ к уведомлениям") { settings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) })
        targets.addView(button("Поверх других окон") { settings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION) })
        targets.addView(button("Исключения оптимизации батареи") { settings(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) })
        targets.addView(button("Безопасность и блокировка") { settings(Settings.ACTION_SECURITY_SETTINGS) })
        targets.addView(button("Накопитель") { settings(Settings.ACTION_INTERNAL_STORAGE_SETTINGS) })
        targets.addView(button("Обновление ПО / об устройстве") { settings(Settings.ACTION_DEVICE_INFO_SETTINGS) })
        note(page, "На Samsung и других оболочках названия и маршруты могут отличаться. Если отдельный экран недоступен, " +
            "откроются общие настройки. Не отключай Play Protect и другие средства защиты ради диагностики.", muted)
        controller.report?.findings?.forEach { findingCard(page, it) }
        val report = controller.report
        if (report != null) {
            val largest = report.apps.filter { !it.system && it.storage != null }.sortedByDescending { it.storage!!.totalBytes }.take(5)
            if (largest.isNotEmpty()) {
                val disk = card(page)
                disk.addView(text("Крупнейшие пользовательские приложения", 18, ink, true))
                largest.forEach { item ->
                    disk.addView(button("${item.label} · ${DisplayFormat.bytes(item.storage?.totalBytes)}") { appDetails(item) })
                }
                disk.addView(text("Размер не равен мусору. Кэш входит в объем данных. Не очищай данные приложений без резервной копии.", 12, muted))
            }
        }
        note(page, "Неиспользованная RAM — не цель оптимизации. Ограничения фона могут задерживать уведомления, " +
            "будильники, банковские подтверждения, синхронизацию часов и VPN. Меняй по одной настройке и проверяй результат.", muted)
    }

    private fun historyPage() {
        val page = page("История", "До 10 локальных отчетов, без автоматической синхронизации.")
        if (controller.history.isEmpty()) note(page, "Пока нет сохраненных проверок.", muted)
        controller.history.forEach { entry ->
            val c = card(page)
            c.addView(text(date(entry.timestampMs), 18, ink, true))
            c.addView(text("${entry.apps} пакетов · ${entry.review} для проверки · ${DisplayFormat.bytes(entry.bytes)}", 13, muted))
            c.addView(button("Подготовить этот отчет") { selectedHistory = entry.name; tab = 5; render() })
        }
        page.addView(button("Обновить список") { controller.refreshHistory() })
        page.addView(button("Удалить все локальные отчеты") {
            if (controller.busy) { toast("Сначала останови проверку"); return@button }
            AlertDialog.Builder(this).setTitle("Удалить историю?")
                .setMessage("Будут удалены история, текущий результат, точки батареи и подготовленные файлы обмена. " +
                    "Копии, уже переданные другим приложениям или сохраненные в «Загрузки», останутся там.")
                .setNegativeButton("Отмена", null).setPositiveButton("Удалить") { _, _ ->
                    controller.deleteHistory { success ->
                        if (success) selectedHistory = null
                        if (!isDestroyed) { render(); toast(if (success) "Локальные данные удалены" else "Не все файлы удалось удалить") }
                    }
                }.show()
        })
    }

    private fun exportPage() {
        val page = page("Отчет для анализа", "Предпросмотр и ручная передача. API-ключ не нужен.")
        if (controller.report == null && selectedHistory == null) {
            note(page, "Сначала запусти диагностику или выбери сохраненный отчет в истории.", muted)
            page.addView(button("К диагностике", true) { tab = 0; render() }); return
        }
        val c = card(page)
        c.addView(text(if (selectedHistory == null) "Текущая проверка" else "Отчет из истории", 20, ink, true))
        selectedHistory?.let { c.addView(text(it, 11, muted)) }
        if (selectedHistory != null && controller.report != null) c.addView(button("Выбрать текущую проверку") { selectedHistory = null; render() })
        val apps = CheckBox(this).apply { text = "Список приложений, разрешения и размеры"; setTextColor(ink); isChecked = includeApps }
        val usage = CheckBox(this).apply { text = "Время использования и производные выводы"; setTextColor(ink); isChecked = includeUsage; isEnabled = includeApps }
        val network = CheckBox(this).apply { text = "Сетевые счетчики и тип подключения"; setTextColor(ink); isChecked = includeNetwork }
        c.addView(apps); c.addView(usage); c.addView(network)
        apps.setOnCheckedChangeListener { _, checked -> includeApps = checked; usage.isEnabled = checked }
        usage.setOnCheckedChangeListener { _, checked -> includeUsage = checked }
        network.setOnCheckedChangeListener { _, checked -> includeNetwork = checked }
        c.addView(text("Время использования и сеть по умолчанию исключены. Для разбора активности отметь нужные пункты. " +
            "Даже список программ может раскрывать личные сведения. IMEI, серийный номер, Android ID, " +
            "аккаунты, сообщения, контакты и геолокация не собираются.", 13, warning))
        c.addView(button("Предпросмотр JSON") { prepareExport(false) { artifact ->
            val view = vertical(12)
            view.addView(text(artifact.preview, 11, ink).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) })
            showBody("Предпросмотр · ${DisplayFormat.bytes(artifact.file.length())}", view)
        } })
        c.addView(button("Поделиться JSON", true) { prepareExport(false) { share(it) } })
        c.addView(button("Сохранить JSON в файл") { prepareExport(false) { saveDocument(it) } })
        c.addView(button("Сохранить TXT с полным отчетом") { prepareExport(true) { saveDocument(it) } })
        note(page, "Кнопка «Поделиться» открывает системный выбор приложения. Отчет не отправляется в текущий чат автоматически. " +
            "Можно сохранить JSON или TXT и затем прикрепить файл к сообщению.", muted)
        note(page, "Техническая схема: ${AuditVersion.SCHEMA}. Правила: ${AuditVersion.RULES}. " +
            "Встроенные выводы формируются правилами, а не нейросетью. ИИ-анализ выполняется после ручной передачи отчета.", muted)
    }

    private fun prepareExport(text: Boolean, done: (ExportArtifact) -> Unit) {
        if (exportBusy) { toast("Отчет уже подготавливается"); return }
        exportBusy = true
        toast("Подготовка отчета…")
        controller.prepareExport(selectedHistory, ExportOptions(includeApps, includeUsage, includeNetwork), text) callback@ { result ->
            exportBusy = false
            if (isDestroyed) return@callback
            result.fold(done) { toast("Не удалось подготовить отчет: ${it.javaClass.simpleName}") }
        }
    }
    private fun share(artifact: ExportArtifact) {
        val uri = ExportFiles.uri(this, artifact.file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = artifact.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "AI Phone Auditor — диагностический отчет")
            clipData = ClipData.newUri(contentResolver, "Диагностический отчет", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(Intent.createChooser(send, "Кому передать отчет?").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        catch (_: Exception) { toast("Нет подходящего получателя. Используй «Сохранить в файл».") }
    }
    private fun saveDocument(artifact: ExportArtifact) {
        pendingExportName = artifact.file.name
        try {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = artifact.mime
                putExtra(Intent.EXTRA_TITLE, "AI-Phone-Auditor-${System.currentTimeMillis()}.${artifact.file.extension}")
            }, 86)
        } catch (_: Exception) { toast("Системное сохранение недоступно. Попробуй «Поделиться».") }
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 86) return
        val name = pendingExportName; pendingExportName = null
        if (resultCode != RESULT_OK || data?.data == null || name == null) return
        val target = data.data!!
        io.execute {
            val outcome = runCatching {
                require(Regex("report-[a-f0-9-]+\\.(json|txt)").matches(name))
                val input = File(File(cacheDir, "exports"), name)
                contentResolver.openOutputStream(target, "wt")?.use { output -> input.inputStream().use { it.copyTo(output) } }
                    ?: error("Поставщик документов не открыл файл")
            }
            runOnUiThread { if (!isDestroyed) toast(if (outcome.isSuccess) "Отчет сохранен" else "Не удалось сохранить отчет") }
        }
    }

    private fun findingCard(parent: LinearLayout, finding: Finding) {
        val c = card(parent)
        c.addView(text(finding.priority.label.uppercase(Locale("ru")), 11, priorityColor(finding.priority), true))
        c.addView(text(finding.title, 18, ink, true))
        c.addView(text(finding.evidence, 14))
        c.addView(text(finding.action, 14, accent))
        c.addView(text("Уверенность: ${finding.confidence}", 12, muted))
        c.addView(text("Последствия: ${finding.tradeoff}", 12, muted))
    }
    private fun page(title: String, description: String): LinearLayout {
        val body = vertical(16)
        body.addView(text(title, 26, ink, true)); body.addView(text(description, 14, muted))
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(body) }
        content.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        return body
    }
    private fun card(parent: LinearLayout): LinearLayout = vertical(16).also {
        it.background = rounded(panel)
        parent.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }
    private fun note(parent: LinearLayout, message: String, color: Int) {
        parent.addView(text(message, 13, color).apply { setPadding(dp(2), dp(12), dp(2), dp(4)) })
    }
    private fun text(value: String, size: Int, color: Int = ink, bold: Boolean = false): TextView = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f); setPadding(0, dp(3), 0, dp(3))
    }
    private fun button(label: String, primary: Boolean = false, action: () -> Unit): Button = Button(this).apply {
        text = label; textSize = 14f; isAllCaps = false; minHeight = dp(48)
        setTextColor(if (primary) bg else ink)
        background = rounded(if (primary) accent else Color.rgb(35, 51, 73))
        setPadding(dp(14), dp(10), dp(14), dp(10))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        setOnClickListener { action() }
    }
    private fun vertical(padding: Int = 0) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        if (padding > 0) setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
    }
    private fun row(parent: LinearLayout, label: String, value: String) {
        parent.addView(text(label, 12, muted))
        parent.addView(text(value, 15, ink).apply { setPadding(0, 0, 0, dp(7)) })
    }
    private fun space(parent: LinearLayout, size: Int) { parent.addView(View(this), LinearLayout.LayoutParams(1, dp(size))) }
    private fun rounded(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(16).toFloat() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun number(map: Map<String, Any?>, key: String): Long? = (map[key] as? Number)?.toLong()
    private fun date(timestamp: Long) = if (timestamp <= 0) "Нет данных" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))
    private fun priorityColor(priority: Priority) = when (priority) { Priority.HIGH -> danger; Priority.REVIEW -> warning; else -> muted }
    private fun thermalName(value: Int) = when (value) { 0 -> "NONE · нет ограничений"; 1 -> "LIGHT"; 2 -> "MODERATE"; 3 -> "SEVERE"; 4 -> "CRITICAL"; 5 -> "EMERGENCY"; 6 -> "SHUTDOWN"; else -> "Неизвестный: $value" }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    private fun showBody(title: String, body: LinearLayout) {
        val scroll = ScrollView(this).apply { addView(body); setBackgroundColor(bg) }
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(scroll).setPositiveButton("Закрыть", null).create()
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.96).toInt(), (resources.displayMetrics.heightPixels * 0.88).toInt())
    }
    private fun usageSettings() { settings(Settings.ACTION_USAGE_ACCESS_SETTINGS, packageName) }
    private fun appSettings(pkg: String) { settings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg) }
    private fun settings(action: String, pkg: String? = null) {
        val attempts = listOfNotNull(
            pkg?.let { Intent(action, Uri.parse("package:$it")) }, Intent(action), Intent(Settings.ACTION_SETTINGS))
        for (intent in attempts) {
            try { startActivity(intent); return } catch (_: Exception) { /* OEM route absent; try a public fallback. */ }
        }
        toast("Открой настройки Android вручную")
    }
}
