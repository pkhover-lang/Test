package local.phoneauditor

import local.phoneauditor.core.*

/** Same assertions run by JUnit in CI and kotlinc+JVM in an SDK-free environment. */
object CoreChecks {
    private fun base() = AppRecord(
        packageName = "example.app", label = "Example", uid = 10101, version = "1", versionCode = 1,
        targetSdk = 35, system = false, enabled = true, debuggable = false,
        firstInstallMs = 1, lastUpdateMs = 2, installer = null, installerStatus = Availability.NOT_COLLECTED,
        permissions = emptyList(), declaredServiceCount = 0, bootPermissionDeclared = false,
        accessibilityEnabled = false, notificationsEnabled = false, deviceAdminActive = false,
        batteryOptimizationExempt = false, usage = null, usageStatus = Availability.PERMISSION_REQUIRED,
        storage = null, storageStatus = Availability.PERMISSION_REQUIRED,
        traffic = null, trafficStatus = Availability.PERMISSION_REQUIRED
    )
    private fun permission(name: String, granted: Boolean = true, special: Boolean = false, category: String? = null) =
        PermissionRecord(name, name, granted, !special, special, category)
    private fun declaredCombo() = listOf(
        permission("android.permission.SYSTEM_ALERT_WINDOW", false, true),
        permission("android.permission.REQUEST_INSTALL_PACKAGES", false, true)
    )
    fun runAll(): List<String> {
        val passed = mutableListOf<String>()
        fun test(name: String, action: () -> Unit) { action(); passed += name }
        test("baseline_has_no_accusation") { check(RiskEngine.app(base()).isEmpty()) }
        test("unknown_access_is_not_false_positive") {
            check(RiskEngine.app(base().copy(accessibilityEnabled = null, notificationsEnabled = null, deviceAdminActive = null)).isEmpty())
        }
        test("declared_special_permissions_are_not_enabled_access") {
            check(RiskEngine.app(base().copy(permissions = declaredCombo())).none { it.priority == Priority.HIGH || it.id == "enabled_accessibility" })
        }
        test("enabled_accessibility_is_manual_review") {
            val result = RiskEngine.app(base().copy(accessibilityEnabled = true))
            check(result.single().priority == Priority.REVIEW)
        }
        test("combined_signals_have_explicit_uncertainty") {
            val result = RiskEngine.app(base().copy(accessibilityEnabled = true, permissions = declaredCombo()))
            val high = result.first { it.priority == Priority.HIGH }
            check("НЕ проверена" in high.evidence)
            check("не диагноз" in high.confidence)
        }
        test("four_granted_categories_are_review_not_virus") {
            val perms = listOf("camera", "microphone", "location", "contacts").map { permission(it, category = it) }
            val result = RiskEngine.app(base().copy(permissions = perms))
            check(result.single().priority == Priority.REVIEW)
        }
        test("denied_permissions_do_not_count_as_sensitive_grants") {
            val perms = listOf("camera", "microphone", "location", "contacts").map { permission(it, false, category = it) }
            check(RiskEngine.app(base().copy(permissions = perms)).isEmpty())
        }
        test("boot_declaration_does_not_prove_running") {
            val found = RiskEngine.app(base().copy(bootPermissionDeclared = true)).single()
            check(found.priority == Priority.INFO && "не наблюдались" in found.evidence)
        }
        test("own_debug_build_is_not_suspect") {
            check(RiskEngine.app(base().copy(packageName = "local.phoneauditor", debuggable = true)).isEmpty())
        }
        test("system_legacy_target_is_not_naively_flagged") {
            check(RiskEngine.app(base().copy(system = true, targetSdk = 22)).isEmpty())
        }
        test("unknown_installer_is_not_automatically_untrusted") {
            check(RiskEngine.app(base().copy(installer = null, installerStatus = Availability.ERROR)).isEmpty())
        }
        test("fgs_time_is_not_battery_attribution") {
            val usage = UsageRecord(0, 0, 8 * 3600000L, null, 1, 2)
            val f = RiskEngine.app(base().copy(usage = usage)).single()
            check(f.priority == Priority.INFO && "не измерение энергии" in f.evidence)
        }
        test("cache_is_not_double_counted") { check(StorageRecord(100, 200, 50).totalBytes == 300L) }
        test("missing_traffic_stays_unknown") { check(TrafficRecord(1, null, null, null, null).knownTotal == null) }
        test("partial_traffic_keeps_known_sum") { check(TrafficRecord(1, 10, 20, null, null).knownTotal == 30L) }
        test("shared_uid_is_explicit") {
            check(TrafficRecord(1, 10, 20, 0, 0, 2).toMap()["attribution"] == "SHARED_UID_DO_NOT_SUM_PER_PACKAGE")
        }
        test("json_escapes_untrusted_names") {
            check(JsonCodec.encode("a\"b\\c\n\t\u0001", false) == "\"a\\\"b\\\\c\\n\\t\\u0001\"")
        }
        test("json_no_nan_or_infinity") { check(JsonCodec.encode(listOf(Double.NaN, Double.POSITIVE_INFINITY), false) == "[null,null]") }
        test("json_surrogate_pairs_are_safe") { check(JsonCodec.encode("😀", false) == "\"\\ud83d\\ude00\"") }
        test("json_unknown_is_null_not_zero") { check(JsonCodec.encode(mapOf("battery" to null), false) == "{\"battery\":null}") }
        val usage = UsageRecord(1234567, 1234567, 36000000, 123456789, 100, 200)
        val record = base().copy(usage = usage, usageStatus = Availability.AVAILABLE,
            permissions = declaredCombo(), traffic = TrafficRecord(1, 11, 22, 33, 44))
        val app = record.copy(findings = RiskEngine.app(record))
        val raw = linkedMapOf<String, Any?>(
            "apps" to listOf(app.toMap()), "findings" to listOf(
                Finding("app", Priority.REVIEW, "security", "App", "example.app", "Check", "unknown", "none", "example.app").toMap()),
            "network" to mapOf("token" to "NETWORK_SENTINEL"),
            "collection_errors" to listOf("example.app: error")
        )
        test("redact_app_inventory_removes_identifiers_and_errors") {
            val encoded = JsonCodec.encode(ReportRedactor.redact(raw, ExportOptions(false, true, false)))
            check("example.app" !in encoded && "NETWORK_SENTINEL" !in encoded)
        }
        test("redact_usage_removes_derived_findings") {
            val encoded = JsonCodec.encode(ReportRedactor.redact(raw, ExportOptions(true, false, true)))
            check("123456789" !in encoded && "long_foreground_service" !in encoded)
        }
        test("redact_network_removes_nested_data") {
            val output = ReportRedactor.redact(raw, ExportOptions(true, true, false))
            val a = (output["apps"] as List<*>).first() as Map<*, *>
            check(a["network_usage"] == null && a["network_status"] == "REDACTED_BY_USER")
        }
        test("redaction_does_not_mutate_original") {
            ReportRedactor.redact(raw, ExportOptions(false, false, false))
            check((raw["apps"] as List<*>).size == 1)
            check("123456789" in JsonCodec.encode(raw))
        }
        test("full_export_keeps_selected_usage") {
            check("123456789" in JsonCodec.encode(ReportRedactor.redact(raw, ExportOptions(true, true, true))))
        }
        test("unknown_system_metrics_create_no_clean_bill") { check(RiskEngine.system(null, null, null, null, null).isEmpty()) }
        test("low_storage_threshold_is_documented") {
            check(RiskEngine.system(0.05, false, 0, 1, true).single().id == "storage_low")
        }
        test("normal_free_ram_does_not_trigger_cleaner") {
            check(RiskEngine.system(0.5, false, 0, 10, true).isEmpty())
        }
        test("future_patch_date_does_not_trigger_staleness") {
            check(RiskEngine.system(null, null, null, -20, null).isEmpty())
        }
        test("unknown_display_is_not_zero") { check(DisplayFormat.bytes(null) == "Недоступно") }
        return passed
    }
}
