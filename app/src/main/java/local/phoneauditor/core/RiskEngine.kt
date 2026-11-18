package local.phoneauditor.core

/** Explainable review priorities, NOT malware probabilities or battery attribution. */
object RiskEngine {
    fun app(app: AppRecord, ownPackage: String = "local.phoneauditor"): List<Finding> {
        val out = mutableListOf<Finding>()
        fun add(id: String, priority: Priority, category: String, title: String,
                evidence: String, action: String, confidence: String, tradeoff: String) {
            out += Finding(id, priority, category, title, evidence, action, confidence, tradeoff, app.packageName)
        }
        val declared = app.permissions.map { it.name }.toSet()
        val sensitive = app.permissions.filter { it.grantedByPackageManager && !it.special }
            .mapNotNull { it.category }.toSet()
        if (app.accessibilityEnabled == true) add(
            "enabled_accessibility", Priority.REVIEW, "security", "Включена служба специальных возможностей",
            "AccessibilityManager сообщает включенную службу этого пакета.",
            "Проверь, что сам включил доступ и он нужен функциям приложения. Сверь разработчика и назначение службы.",
            "Факт доступа: высокий. Вредоносность не установлена.",
            "Отключение может нарушить экранный диктор, автоматизацию или помощь пользователю."
        )
        if (app.notificationsEnabled == true) add(
            "enabled_notification_listener", Priority.REVIEW, "security", "Есть доступ к уведомлениям",
            "Пакет присутствует в доступном системном списке включенных слушателей уведомлений.",
            "Проверь необходимость чтения уведомлений в настройках специального доступа.",
            "Системный список; не доказательство чтения конкретных сообщений.",
            "Отключение может нарушить работу часов, синхронизации и истории уведомлений."
        )
        if (app.deviceAdminActive == true) add(
            "active_device_admin", Priority.REVIEW, "security", "Активен администратор устройства",
            "DevicePolicyManager сообщает активный компонент администратора.",
            "Убедись, что это ожидаемое приложение организации или защиты. Для рабочего устройства согласуй изменения с администратором.",
            "Системный факт; не признак вредоносности сам по себе.",
            "Отключение может нарушить корпоративные политики и защиту устройства."
        )
        if (app.accessibilityEnabled == true &&
            "android.permission.SYSTEM_ALERT_WINDOW" in declared &&
            "android.permission.REQUEST_INSTALL_PACKAGES" in declared) add(
            "combined_sensitive_capabilities", Priority.HIGH, "security", "Сочетание возможностей требует проверки",
            "Служба специальных возможностей включена; наложения и установка пакетов объявлены. " +
                "Фактическая выдача двух последних доступов НЕ проверена.",
            "Вручную проверь три специальных доступа, источник приложения и необходимость такого сочетания.",
            "Эвристика по сочетанию доступов, не диагноз вируса.",
            "Законные средства автоматизации тоже используют это сочетание; не удаляй их автоматически."
        )
        if (sensitive.size >= 4) add(
            "many_sensitive_categories", Priority.REVIEW, "security", "Несколько категорий чувствительных разрешений",
            "PackageManager отмечает выданные разрешения в категориях: ${sensitive.sorted().joinToString()}. " +
                "AppOps и ограничения доступа в фоне отдельно не проверены.",
            "Сопоставь каждое разрешение с функциями приложения; отзови только ненужные.",
            "Факты разрешений; порог 4 категории — локальное правило приоритизации, не стандарт безопасности.",
            "Мессенджеры и другие многофункциональные приложения могут обоснованно иметь такие разрешения."
        )
        if (!app.system && app.debuggable && app.packageName != ownPackage) add(
            "debug_build", Priority.REVIEW, "security", "Установлена отладочная сборка",
            "В ApplicationInfo установлен FLAG_DEBUGGABLE.",
            "Уточни, ожидается ли тестовая версия; при необходимости замени официальной релизной сборкой.",
            "Факт манифеста; не доказательство эксплуатации или вредоносности.",
            "Твое собственное тестовое ПО может быть отладочным намеренно."
        )
        if (!app.system && app.targetSdk < 29) add(
            "legacy_target_sdk", Priority.REVIEW, "security", "Приложение ориентировано на старую версию Android",
            "targetSdk=${app.targetSdk}. Порог <29 — правило этого аудитора.",
            "Проверь наличие обновления у разработчика; устаревший targetSdk сам по себе не доказывает уязвимость.",
            "Факт версии API; оценка последствий требует анализа приложения.",
            "Замена может изменить привычные функции; сначала сохрани необходимые данные."
        )
        if (app.bootPermissionDeclared) add(
            "declared_boot_permission", Priority.INFO, "startup", "Объявлено разрешение на событие загрузки",
            "Манифест запрашивает RECEIVE_BOOT_COMPLETED. Прием события, фактический запуск и длительность работы не наблюдались.",
            "При жалобах на батарею сопоставь это с системной статистикой. Не отключай только за наличие разрешения.",
            "Факт декларации; вывод об автозапуске отсутствует.",
            "Изменение ограничений может мешать будильникам, напоминаниям и сообщениям."
        )
        val fgs = app.usage?.foregroundServiceMs
        if (fgs != null && fgs >= 6L * 60 * 60 * 1000) add(
            "long_foreground_service", Priority.INFO, "usage", "Длительная работа сервиса переднего плана",
            "В возвращенном интервале UsageStats: $fgs мс. Это не вся фоновая активность и не измерение энергии.",
            "Сравни назначение приложения и системный экран батареи за тот же период. Проверь, не работали музыка, навигация или VPN.",
            "Измерен агрегат Android; причина энергопотребления не установлена. Порог 6 часов — эвристика.",
            "Ограничение может остановить нужные постоянные функции."
        )
        if (app.batteryOptimizationExempt == true && !app.system && app.packageName != ownPackage) add(
            "battery_optimization_exemption", Priority.INFO, "power_policy", "Исключено из оптимизации батареи",
            "PowerManager.isIgnoringBatteryOptimizations вернул true. Это не доказывает повышенный расход.",
            "Проверь, нужно ли исключение. Для сообщений, будильников, часов и VPN оно может быть оправдано.",
            "Системный факт о политике, не статистика энергопотребления.",
            "Возврат ограничений может задерживать уведомления и синхронизацию."
        )
        return out.sortedByDescending { it.priority.rank }
    }

    fun system(
        freeFraction: Double?, lowMemory: Boolean?, thermal: Int?,
        patchAgeDays: Long?, secureLock: Boolean?
    ): List<Finding> {
        val out = mutableListOf<Finding>()
        if (freeFraction != null && freeFraction < 0.10) out += Finding(
            "storage_low", Priority.REVIEW, "storage", "Мало свободного места",
            "Свободно ${"%.1f".format(java.util.Locale.ROOT, freeFraction * 100)}%. Порог 10% выбран в правилах аудитора.",
            "Посмотри самые крупные приложения и загрузки. Удаляй только ненужное; очистка данных приложения может удалить учетные данные и файлы.",
            "Объем измерен StatFs; влияние на скорость не измерялось.", "После очистки кэша возможны повторные загрузки и временное замедление."
        )
        if (lowMemory == true) out += Finding(
            "system_low_memory", Priority.REVIEW, "memory", "Android сообщает нехватку оперативной памяти",
            "ActivityManager.MemoryInfo.lowMemory=true в момент проверки.",
            "Закрой только ненужные тяжелые задачи и проверь повторяемость проблемы. Не используй автоматическую постоянную очистку RAM.",
            "Системный моментальный сигнал; виновник не установлен.", "Принудительное закрытие задач может потерять несохраненные данные."
        )
        if (thermal != null && thermal >= 3) out += Finding(
            "thermal_pressure", Priority.REVIEW, "thermal", "Повышенный тепловой статус Android",
            "PowerManager.currentThermalStatus=$thermal (SEVERE или выше). Это не температура в градусах.",
            "Приостанови тяжелую нагрузку, убери телефон из прямого солнца и повтори проверку после охлаждения.",
            "Системный тепловой статус; конкретный источник нагрева не установлен.", "Не охлаждай телефон в холодильнике и не подвергай резкому перепаду температуры."
        )
        if (patchAgeDays != null && patchAgeDays > 180) out += Finding(
            "old_security_patch", Priority.REVIEW, "updates", "Проверь обновления безопасности",
            "Указанный системой патч старше $patchAgeDays дней. Порог 180 дней — эвристика; часы устройства могут быть неверны.",
            "Открой штатное обновление ПО и проверь поддержку модели у производителя.",
            "Возраст даты патча; не проверка конкретных уязвимостей или полноты исправлений.", "Перед обновлением сохрани важные данные; наличие обновления не гарантируется."
        )
        if (secureLock == false) out += Finding(
            "no_secure_lock", Priority.REVIEW, "security", "Не обнаружена защищенная блокировка экрана",
            "KeyguardManager.isDeviceSecure=false.",
            "Проверь настройку PIN, пароля или графического ключа в системных настройках.",
            "Системный факт текущего профиля.", "Сохрани способ восстановления; не выбирай легко угадываемый PIN."
        )
        return out
    }
}
