# Фактически выполненная проверка — 16.11.2026

## Пройдено в среде подготовки

- Платформонезависимые Kotlin-файлы `core/Models.kt`, `core/RiskEngine.kt`, `core/JsonCodec.kt` скомпилированы локальным Kotlin 1.9.0 для JVM 17. Это проверка ядра, не Android-приложения; CI использует Kotlin 2.2.21.
- Выполнены 30 проверок из `CoreChecks.runAll()`; все завершились успешно.
- Сформированный собственным Kotlin JSON writer документ разобран независимым Python `json` parser. Проверены кириллица, emoji, кавычки, управляющие символы, null, NaN и большое целое число.
- Kotlin PSI parser проверил синтаксис 13 файлов `.kt/.kts`: ошибок разбора не найдено. При этом Android-типы, методы API и типизация Android-кода не разрешались и не проверялись.
- XML-файлы разобраны XML-парсером. В исходном manifest ровно три ожидаемых разрешения. Нет INTERNET, service или receiver; provider не экспортирован.
- YAML workflow разобран; проверены `workflow_dispatch`, минимальные permissions и shell-синтаксис шагов сборки.

Лог проверок ядра: `core-tests.log` рядом с этим документом.

## Попытка сборки Android

Команда `bash tools/build.sh` была запущена и завершилась с кодом 1 на проверке наличия Gradle. Вывод: `Gradle 8.13 is required. Use the included GitHub Actions workflow from your phone.` Android SDK также отсутствует. До компиляции Android-кода выполнение не дошло; это не результат проверки компилятором. Лог: `android-build-attempt.log`.

## Не выполнено

Android SDK/Gradle сборка, manifest merge, проверка Android-типов компилятором, Android Lint, DEX, упаковка и подпись APK, эмулятор, запуск и установка на Samsung, проверка интерфейса, реальных разрешений, OEM-ограничений, сети, StorageStats и Sharesheet. GitHub workflow не запускался от имени пользователя.

**Установочного APK в поставке нет. Успешные проверки ядра не подтверждают, что Android-приложение скомпилируется и корректно заработает.** Для проверки сборки и получения APK предусмотрен `.github/workflows/build-apk.yml`; для проверки устройства — `ACCEPTANCE.md`.
