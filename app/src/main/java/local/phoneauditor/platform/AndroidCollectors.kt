@file:Suppress("DEPRECATION")
package local.phoneauditor.platform

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import local.phoneauditor.core.*
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

private inline fun <reified T> Context.service(): T =
    getSystemService(T::class.java) ?: throw UnsupportedOperationException("Служба ${T::class.java.simpleName} недоступна")

object AccessCheck {
    fun usage(context: Context): Boolean = runCatching {
        val mode = context.service<AppOpsManager>().unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)
}

object BatteryReader {
    fun read(context: Context): BatterySample {
        val broadcast = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
        val manager = context.getSystemService(BatteryManager::class.java)
        fun extra(name: String): Int? = broadcast?.takeIf { it.hasExtra(name) }?.getIntExtra(name, Int.MIN_VALUE)
            ?.takeUnless { it == Int.MIN_VALUE }
        fun property(id: Int): Int? = runCatching { manager?.getIntProperty(id) }.getOrNull()
            ?.takeUnless { it == Int.MIN_VALUE }
        val scale = extra(BatteryManager.EXTRA_SCALE)
        val level = extra(BatteryManager.EXTRA_LEVEL)
        val percentage = if (scale != null && scale > 0 && level != null && level in 0..scale)
            (level.toLong() * 100 / scale).toInt() else null
        val power = context.getSystemService(PowerManager::class.java)
        return BatterySample(
            timestampMs = System.currentTimeMillis(), levelPercent = percentage,
            temperatureC = extra(BatteryManager.EXTRA_TEMPERATURE)?.div(10.0),
            voltageMv = extra(BatteryManager.EXTRA_VOLTAGE)?.takeIf { it > 0 },
            currentUa = property(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            averageCurrentUa = property(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE),
            chargeCounterUah = property(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)?.takeIf { it >= 0 },
            chargingStatus = when (extra(BatteryManager.EXTRA_STATUS)) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
                BatteryManager.BATTERY_STATUS_FULL -> "FULL"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT_CHARGING"
                else -> "UNKNOWN"
            },
            plugged = when (extra(BatteryManager.EXTRA_PLUGGED)) {
                0 -> "UNPLUGGED"; 1 -> "AC"; 2 -> "USB"; 4 -> "WIRELESS"; 8 -> "DOCK"; else -> "UNKNOWN"
            },
            health = when (extra(BatteryManager.EXTRA_HEALTH)) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "GOOD"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "OVERHEAT"
                BatteryManager.BATTERY_HEALTH_COLD -> "COLD"
                BatteryManager.BATTERY_HEALTH_DEAD -> "DEAD"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "OVER_VOLTAGE"
                BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "UNSPECIFIED_FAILURE"
                else -> "UNKNOWN"
            },
            thermalStatus = runCatching { power?.currentThermalStatus }.getOrNull(),
            powerSave = runCatching { power?.isPowerSaveMode }.getOrNull()
        )
    }
}

private data class SpecialAccess(
    val accessibility: Set<String>?, val notifications: Set<String>?, val admins: Set<String>?
)
private data class UsageResult(val records: Map<String, UsageRecord>, val status: Availability)
private data class TrafficResult(
    val wifi: Map<Int, Pair<Long, Long>>, val mobile: Map<Int, Pair<Long, Long>>,
    val wifiStatus: Availability, val mobileStatus: Availability
) {
    fun record(uid: Int, shared: Int): TrafficRecord? {
        if (wifiStatus != Availability.AVAILABLE && mobileStatus != Availability.AVAILABLE) return null
        val w = wifi[uid] ?: (0L to 0L); val m = mobile[uid] ?: (0L to 0L)
        return TrafficRecord(uid, w.first.takeIf { wifiStatus == Availability.AVAILABLE },
            w.second.takeIf { wifiStatus == Availability.AVAILABLE },
            m.first.takeIf { mobileStatus == Availability.AVAILABLE },
            m.second.takeIf { mobileStatus == Availability.AVAILABLE }, shared)
    }
    val overall: Availability get() = when {
        wifiStatus == Availability.AVAILABLE && mobileStatus == Availability.AVAILABLE -> Availability.AVAILABLE
        wifiStatus == Availability.AVAILABLE || mobileStatus == Availability.AVAILABLE -> Availability.PARTIAL
        wifiStatus == Availability.PERMISSION_REQUIRED && mobileStatus == Availability.PERMISSION_REQUIRED -> Availability.PERMISSION_REQUIRED
        else -> Availability.ERROR
    }
}

class AndroidCollector(private val context: Context) {
    private val pm = context.packageManager
    private val errors = mutableListOf<String>()

    fun collect(cancelled: AtomicBoolean, onProgress: (String, Int, Int) -> Unit): AuditReport {
        val start = System.currentTimeMillis()
        val since = start - 7L * 24 * 60 * 60 * 1000
        val granted = AccessCheck.usage(context)
        errors.clear()
        fun checkCancelled() { if (cancelled.get()) throw CancellationException("Проверка отменена") }
        onProgress("Системные показатели", 0, 0)
        val battery = BatteryReader.read(context)
        val memory = safeMap("RAM") {
            val info = ActivityManager.MemoryInfo()
            context.service<ActivityManager>().getMemoryInfo(info)
            val own = Debug.MemoryInfo(); Debug.getMemoryInfo(own)
            linkedMapOf("status" to "AVAILABLE", "total_bytes" to info.totalMem,
                "available_bytes" to info.availMem, "low_memory" to info.lowMemory,
                "low_memory_threshold_bytes" to info.threshold,
                "auditor_own_pss_kib" to own.totalPss,
                "note" to "Низкий свободный объем сам по себе не доказывает проблему: Android использует RAM для кэша. " +
                    "PSS доступен только для самого аудитора; чужая RAM не измерялась.")
        }
        val storage = safeMap("Накопитель") {
            val fs = StatFs(Environment.getDataDirectory().absolutePath)
            linkedMapOf("status" to "AVAILABLE", "total_bytes" to fs.totalBytes,
                "available_bytes" to fs.availableBytes,
                "scope" to "Раздел данных, не сумма всех физических накопителей")
        }
        val special = readSpecialAccess()
        val secure = runCatching { context.service<KeyguardManager>().isDeviceSecure }.getOrNull()
        val security = linkedMapOf<String, Any?>(
            "secure_screen_lock" to secure,
            "enabled_accessibility_packages_count" to special.accessibility?.size,
            "enabled_notification_listener_packages_count" to special.notifications?.size,
            "active_admin_packages_count" to special.admins?.size,
            "malware_engine" to "NOT_IMPLEMENTED",
            "root_or_firmware_integrity_verified" to false
        )
        checkCancelled()
        onProgress("Статистика использования и сети", 0, 0)
        val usage = readUsage(granted, since, start)
        val traffic = readTraffic(granted, since, start)
        val network = currentNetwork().toMutableMap().apply {
            put("requested_start_ms", since); put("requested_end_ms", start)
            put("wifi_stats_status", traffic.wifiStatus.name)
            put("mobile_stats_status", traffic.mobileStatus.name)
            put("attribution", "UID; данные общих UID нельзя складывать по пакетам")
            put("packet_or_domain_inspection", "NOT_IMPLEMENTED")
        }
        checkCancelled()
        onProgress("Инвентаризация приложений", 0, 0)
        val packages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES)
        val uidCounts = packages.mapNotNull { it.applicationInfo?.uid }.groupingBy { it }.eachCount()
        val appStats = context.getSystemService(StorageStatsManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val apps = mutableListOf<AppRecord>()
        var skipped = 0
        packages.forEachIndexed { index, pkg ->
            checkCancelled()
            if (index % 5 == 0 || index == packages.lastIndex) {
                onProgress("Проверка приложений", index + 1, packages.size)
            }
            try {
                val info = pkg.applicationInfo ?: run { skipped++; return@forEachIndexed }
                val notes = mutableListOf<String>()
                val perms = (pkg.requestedPermissions ?: emptyArray()).mapIndexed { pIndex, name ->
                    val meta = runCatching { pm.getPermissionInfo(name, 0) }.getOrNull()
                    val flags = pkg.requestedPermissionsFlags?.getOrNull(pIndex) ?: 0
                    PermissionRecord(name, permissionLabel(name, meta),
                        flags and android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED != 0,
                        meta?.let { (it.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE) == PermissionInfo.PROTECTION_DANGEROUS } ?: false,
                        name in SPECIAL_PERMISSIONS, category(name))
                }
                var installerStatus = Availability.AVAILABLE
                val installer = try {
                    pm.getInstallSourceInfo(pkg.packageName).installingPackageName.also {
                        if (it == null) installerStatus = Availability.NOT_COLLECTED
                    }
                } catch (_: Exception) { installerStatus = Availability.ERROR; null }
                var storageStatus = if (!granted) Availability.PERMISSION_REQUIRED else if (appStats == null) Availability.UNSUPPORTED else Availability.NOT_COLLECTED
                val space = if (granted && appStats != null) {
                    try {
                        val st = appStats.queryStatsForPackage(info.storageUuid ?: StorageManager.UUID_DEFAULT,
                            pkg.packageName, Process.myUserHandle())
                        storageStatus = Availability.AVAILABLE
                        StorageRecord(st.appBytes, st.dataBytes, st.cacheBytes)
                    } catch (_: SecurityException) {
                        storageStatus = Availability.PERMISSION_REQUIRED
                        notes += "Android отклонил доступ к статистике накопителя этого пакета."; null
                    } catch (e: Exception) {
                        storageStatus = Availability.ERROR
                        notes += "Статистика накопителя: ${e.javaClass.simpleName}"; null
                    }
                } else null
                val record = AppRecord(
                    packageName = pkg.packageName,
                    label = runCatching { info.loadLabel(pm).toString().take(200) }.getOrDefault(pkg.packageName),
                    uid = info.uid, version = pkg.versionName, versionCode = pkg.longVersionCode,
                    targetSdk = info.targetSdkVersion,
                    system = info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                    enabled = info.enabled, debuggable = info.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
                    firstInstallMs = pkg.firstInstallTime, lastUpdateMs = pkg.lastUpdateTime,
                    installer = installer, installerStatus = installerStatus,
                    permissions = perms, declaredServiceCount = pkg.services?.size ?: 0,
                    bootPermissionDeclared = perms.any { it.name == Manifest.permission.RECEIVE_BOOT_COMPLETED },
                    accessibilityEnabled = special.accessibility?.contains(pkg.packageName),
                    notificationsEnabled = special.notifications?.contains(pkg.packageName),
                    deviceAdminActive = special.admins?.contains(pkg.packageName),
                    batteryOptimizationExempt = runCatching { power?.isIgnoringBatteryOptimizations(pkg.packageName) }.getOrNull(),
                    usage = usage.records[pkg.packageName],
                    usageStatus = if (usage.status == Availability.AVAILABLE && !usage.records.containsKey(pkg.packageName))
                        Availability.NOT_COLLECTED else usage.status,
                    storage = space, storageStatus = storageStatus,
                    traffic = traffic.record(info.uid, uidCounts[info.uid] ?: 1),
                    trafficStatus = traffic.overall, notes = notes
                )
                apps += record.copy(findings = RiskEngine.app(record, context.packageName))
            } catch (e: Exception) {
                skipped++
                // Do not include arbitrary exception messages or private file paths in the report.
                errors += "${pkg.packageName}: ${e.javaClass.simpleName} (пакет пропущен)"
            }
        }
        checkCancelled()
        onProgress("Формирование отчета", packages.size, packages.size)
        val patchAge = runCatching {
            ChronoUnit.DAYS.between(LocalDate.parse(Build.VERSION.SECURITY_PATCH), LocalDate.now())
        }.getOrNull()
        val available = (storage["available_bytes"] as? Number)?.toDouble()
        val total = (storage["total_bytes"] as? Number)?.toDouble()
        val free = if (available != null && total != null && total > 0) available / total else null
        val findings = RiskEngine.system(free, memory["low_memory"] as? Boolean,
            battery.thermalStatus, patchAge, secure)
        return AuditReport(
            id = UUID.randomUUID().toString(), startedMs = start, completedMs = System.currentTimeMillis(),
            device = linkedMapOf("manufacturer" to Build.MANUFACTURER, "model" to Build.MODEL,
                "android_release" to Build.VERSION.RELEASE, "api_level" to Build.VERSION.SDK_INT,
                "security_patch" to Build.VERSION.SECURITY_PATCH, "patch_age_days" to patchAge,
                "supported_abis" to Build.SUPPORTED_ABIS.toList(),
                "processors_available_to_process" to Runtime.getRuntime().availableProcessors(),
                "cpu_load" to null, "cpu_load_status" to "UNSUPPORTED",
                "scope" to "Текущий пользователь/профиль Android"),
            memory = memory, storage = storage, network = network, security = security,
            battery = battery,
            apps = apps.sortedWith(compareByDescending<AppRecord> { it.priority.rank }.thenBy { it.label.lowercase() }),
            findings = findings,
            coverage = linkedMapOf("usage_access_granted_at_start" to granted,
                "package_inventory_status" to if (skipped == 0) "AVAILABLE_CURRENT_PROFILE" else "PARTIAL",
                "packages_returned_by_android" to packages.size, "packages_processed" to apps.size,
                "packages_skipped" to skipped, "usage_status" to usage.status.name,
                "usage_requested_start_ms" to since, "usage_requested_end_ms" to start,
                "network_status" to traffic.overall.name,
                "accessibility_status" to if (special.accessibility != null) "AVAILABLE" else "UNKNOWN",
                "notification_listeners_status" to if (special.notifications != null) "AVAILABLE" else "UNKNOWN",
                "device_admins_status" to if (special.admins != null) "AVAILABLE" else "UNKNOWN"),
            errors = errors.toList()
        )
    }

    private fun safeMap(name: String, block: () -> Map<String, Any?>): Map<String, Any?> = try {
        block()
    } catch (e: Exception) {
        errors += "$name: ${e.javaClass.simpleName}"
        mapOf("status" to "ERROR", "reason" to e.javaClass.simpleName)
    }

    private fun readSpecialAccess(): SpecialAccess {
        val accessibility = try {
            context.service<AccessibilityManager>()
                .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .mapNotNull { it.resolveInfo?.serviceInfo?.packageName }.toSet()
        } catch (e: Exception) { errors += "Список специальных возможностей: ${e.javaClass.simpleName}"; null }
        val notifications = try {
            Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                ?.split(':')?.mapNotNull { ComponentName.unflattenFromString(it)?.packageName }?.toSet()
        } catch (e: Exception) { errors += "Список слушателей уведомлений: ${e.javaClass.simpleName}"; null }
        val admins = try {
            context.service<DevicePolicyManager>().activeAdmins.orEmpty().map { it.packageName }.toSet()
        } catch (e: Exception) { errors += "Администраторы устройства: ${e.javaClass.simpleName}"; null }
        return SpecialAccess(accessibility, notifications, admins)
    }

    private fun readUsage(granted: Boolean, start: Long, end: Long): UsageResult {
        if (!granted) return UsageResult(emptyMap(), Availability.PERMISSION_REQUIRED)
        return try {
            val raw = context.service<UsageStatsManager>().queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
                ?: return UsageResult(emptyMap(), Availability.NOT_COLLECTED)
            val merged = mutableMapOf<String, UsageStats>()
            raw.forEach { stat ->
                merged[stat.packageName]?.add(stat) ?: run { merged[stat.packageName] = UsageStats(stat) }
            }
            UsageResult(merged.mapValues { (_, s) -> UsageRecord(
                s.totalTimeInForeground, s.totalTimeVisible, s.totalTimeForegroundServiceUsed,
                s.lastTimeUsed.takeIf { it > 0 }, s.firstTimeStamp, s.lastTimeStamp)
            }, Availability.AVAILABLE)
        } catch (e: Exception) {
            errors += "UsageStats: ${e.javaClass.simpleName}"
            UsageResult(emptyMap(), if (e is SecurityException) Availability.PERMISSION_REQUIRED else Availability.ERROR)
        }
    }

    private fun readTraffic(granted: Boolean, start: Long, end: Long): TrafficResult {
        if (!granted) return TrafficResult(emptyMap(), emptyMap(), Availability.PERMISSION_REQUIRED, Availability.PERMISSION_REQUIRED)
        fun query(type: Int): Pair<Map<Int, Pair<Long, Long>>, Availability> {
            var stats: NetworkStats? = null
            return try {
                stats = context.service<NetworkStatsManager>().querySummary(type, null, start, end)
                val active = stats ?: return emptyMap<Int, Pair<Long, Long>>() to Availability.NOT_COLLECTED
                val result = mutableMapOf<Int, Pair<Long, Long>>()
                val bucket = NetworkStats.Bucket()
                while (active.hasNextBucket()) {
                    if (!active.getNextBucket(bucket)) break
                    if (bucket.uid < 0) continue
                    val old = result[bucket.uid] ?: (0L to 0L)
                    result[bucket.uid] = (old.first + bucket.rxBytes) to (old.second + bucket.txBytes)
                }
                result to Availability.AVAILABLE
            } catch (e: Exception) {
                errors += "NetworkStats($type): ${e.javaClass.simpleName}"
                emptyMap<Int, Pair<Long, Long>>() to if (e is SecurityException) Availability.PERMISSION_REQUIRED else Availability.ERROR
            } finally { stats?.close() }
        }
        val wifi = query(ConnectivityManager.TYPE_WIFI)
        // null subscriberId requests all eligible mobile subscriptions without collecting IMSI.
        val mobile = query(ConnectivityManager.TYPE_MOBILE)
        return TrafficResult(wifi.first, mobile.first, wifi.second, mobile.second)
    }

    private fun currentNetwork(): Map<String, Any?> = safeMap("Текущее подключение") {
        val cm = context.service<ConnectivityManager>()
        val net = cm.activeNetwork
        val caps = net?.let { cm.getNetworkCapabilities(it) }
        linkedMapOf("status" to "AVAILABLE", "connected" to (caps != null),
            "transports" to if (caps == null) emptyList<String>() else buildList {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("WIFI")
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("CELLULAR")
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ETHERNET")
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
            },
            "system_validated" to caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            "metered" to if (caps != null) cm.isActiveNetworkMetered else null,
            "note" to "SSID, IP-адреса, DNS, IMSI и содержимое трафика не собираются. Подключение к VPN само по себе не риск.")
    }

    private fun permissionLabel(name: String, meta: PermissionInfo?): String = when (name) {
        Manifest.permission.CAMERA -> "Камера"
        Manifest.permission.RECORD_AUDIO -> "Микрофон"
        Manifest.permission.READ_CONTACTS -> "Чтение контактов"
        Manifest.permission.WRITE_CONTACTS -> "Изменение контактов"
        Manifest.permission.READ_SMS -> "Чтение SMS"
        Manifest.permission.SEND_SMS -> "Отправка SMS"
        Manifest.permission.RECEIVE_SMS -> "Получение SMS"
        Manifest.permission.ACCESS_FINE_LOCATION -> "Точное местоположение"
        Manifest.permission.ACCESS_COARSE_LOCATION -> "Приблизительное местоположение"
        Manifest.permission.ACCESS_BACKGROUND_LOCATION -> "Местоположение в фоне"
        Manifest.permission.SYSTEM_ALERT_WINDOW -> "Поверх других окон — декларация"
        Manifest.permission.REQUEST_INSTALL_PACKAGES -> "Установка APK — декларация"
        Manifest.permission.MANAGE_EXTERNAL_STORAGE -> "Все файлы — декларация"
        Manifest.permission.PACKAGE_USAGE_STATS -> "Статистика использования — декларация"
        Manifest.permission.RECEIVE_BOOT_COMPLETED -> "Событие загрузки — декларация"
        else -> runCatching { meta?.loadLabel(pm)?.toString()?.take(160) }.getOrNull() ?: name.substringAfterLast('.')
    }
    private fun category(name: String): String? = when (name) {
        Manifest.permission.CAMERA -> "camera"
        Manifest.permission.RECORD_AUDIO -> "microphone"
        Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS, Manifest.permission.GET_ACCOUNTS -> "contacts"
        Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS,
        Manifest.permission.RECEIVE_MMS, Manifest.permission.RECEIVE_WAP_PUSH -> "sms"
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION -> "location"
        Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG, Manifest.permission.CALL_PHONE -> "calls"
        Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR -> "calendar"
        Manifest.permission.BODY_SENSORS, "android.permission.BODY_SENSORS_BACKGROUND" -> "body_sensors"
        Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE,
        "android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_AUDIO" -> "media"
        else -> null
    }
    companion object {
        val SPECIAL_PERMISSIONS = setOf(Manifest.permission.SYSTEM_ALERT_WINDOW,
            Manifest.permission.REQUEST_INSTALL_PACKAGES, Manifest.permission.MANAGE_EXTERNAL_STORAGE,
            Manifest.permission.PACKAGE_USAGE_STATS, Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            "android.permission.SCHEDULE_EXACT_ALARM", "android.permission.USE_EXACT_ALARM",
            "android.permission.WRITE_SETTINGS")
    }
}
