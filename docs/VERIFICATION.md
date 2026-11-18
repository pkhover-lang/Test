# Фактически выполненные проверки

## Успешная сборка Android — 18.11.2026

- Репозиторий: [pkhover-lang/Test](https://github.com/pkhover-lang/Test).
- Запуск CI: [Build APK · 44296829731](https://github.com/pkhover-lang/Test/actions/runs/44296829731).
- Исходный коммит APK: `8d9cba4055f63c389c68875c520b8ef93ea80f5d`.
- Команда `gradle --no-daemon --stacktrace :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` завершилась с `BUILD SUCCESSFUL in 1m36s`.
- JUnit: **1 тест** (`allCoreChecks`), **0 failures**, **0 errors**, **0 skipped**. Этот тест вызывает набор проверок ядра.
- Android Lint: **0 ошибок, 5 предупреждений** — `OldTargetApi`, `GradleDependency` (compileSdk 35 вместо 36), `DrawAllocation`, два `SetTextI18n`. Проверка Lint пройдена; отсутствие предупреждений не заявляется.
- APK упакован; `apksigner` подтвердил подпись v2. Это debug-подпись, не публикационный релиз.
- В готовом APK подтверждены три разрешения: `android.permission.QUERY_ALL_PACKAGES`, `android.permission.PACKAGE_USAGE_STATS`, `android.permission.ACCESS_NETWORK_STATE`. Разрешения `android.permission.INTERNET` нет.

### Полученный файл

- Имя: **AI-Phone-Auditor-1.0.0-debug.apk**.
- Размер: **2 743 284 байта**.
- Артефакт запуска: **AI-Phone-Auditor-APK**, срок хранения — до **25.11.2026**. Скачать его можно в разделе **Artifacts** указанного запуска Actions; APK находится внутри ZIP.
- SHA-256:

```text
e90a5822bca49df23f67376ef47a8d276eaad0e94c666f89687ecf8b787070cd
```

Ключ debug-подписи не сохраняется между чистыми запусками CI. Следующая сборка может иметь другую подпись и не установиться поверх этой версии. Для регулярных обновлений нужен собственный стабильный ключ; приватного ключа в исходниках нет.

### Что пока не проверено

Установка и запуск на телефоне или эмуляторе, поведение интерфейса, фактическая выдача разрешений, ограничения производителей, реальные показатели UsageStats/StorageStats/сети и передача отчета через Sharesheet. Успешная сборка не заменяет эти проверки. План проверки устройства — [ACCEPTANCE.md](ACCEPTANCE.md).

## Предыдущий этап — 16.11.2026

### Пройдено в среде подготовки

- Платформонезависимые Kotlin-файлы `core/Models.kt`, `core/RiskEngine.kt`, `core/JsonCodec.kt` скомпилированы локальным Kotlin 1.9.0 для JVM 17. Это проверка ядра, не Android-приложения; CI использует Kotlin 2.2.21.
- Выполнены 30 проверок из `CoreChecks.runAll()`; все завершились успешно.
- Сформированный собственным Kotlin JSON writer документ разобран независимым Python `json` parser. Проверены кириллица, emoji, кавычки, управляющие символы, null, NaN и большое целое число.
- Kotlin PSI parser проверил синтаксис 13 файлов `.kt/.kts`: ошибок разбора не найдено. При этом Android-типы, методы API и типизация Android-кода не разрешались и не проверялись.
- XML-файлы разобраны XML-парсером. В исходном manifest ровно три ожидаемых разрешения. Нет INTERNET, service или receiver; provider не экспортирован.
- YAML workflow разобран; проверены `workflow_dispatch`, минимальные permissions и shell-синтаксис шагов сборки.

Лог проверок ядра: `core-tests.log` рядом с этим документом.

### Локальная попытка сборки Android в тот день

Команда `bash tools/build.sh` была запущена и завершилась с кодом 1 на проверке наличия Gradle. Вывод: `Gradle 8.13 is required. Use the included GitHub Actions workflow from your phone.` Android SDK также отсутствовал в той среде. До компиляции Android-кода выполнение не дошло; это не результат проверки компилятором. Лог: `android-build-attempt.log`.

На этом историческом этапе APK еще не был собран и GitHub workflow не запускался. Эти ограничения сборки устранены успешным запуском 18.11.2026, описанным выше; проверка на устройстве остается невыполненной.
