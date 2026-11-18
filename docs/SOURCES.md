# Документация

Официальные страницы открыты при подготовке проекта 16 ноября 2026 года. Они обосновывают используемые API и процесс сборки, но не являются доказательством компиляции этого проекта.

## Android

1. Видимость установленных пакетов и QUERY_ALL_PACKAGES:
   https://developer.android.com/training/package-visibility/declaring
2. BatteryManager: свойства аккумулятора и единицы измерения:
   https://developer.android.com/reference/android/os/BatteryManager
3. UsageStatsManager: доступ к статистике и границы возвращаемых агрегатов:
   https://developer.android.com/reference/android/app/usage/UsageStatsManager
4. UsageStats: активность и время foreground services:
   https://developer.android.com/reference/android/app/usage/UsageStats
5. StorageStatsManager: размеры приложений и Usage Access:
   https://developer.android.com/reference/android/app/usage/StorageStatsManager
6. ActivityManager: память и ограничение статистики чужих UID:
   https://developer.android.com/reference/android/app/ActivityManager
7. NetworkStatsManager: статистика по UID, разрешения и агрегаты:
   https://developer.android.com/reference/android/app/usage/NetworkStatsManager
8. Android Gradle Plugin 8.11, версии Gradle/JDK/Build Tools:
   https://developer.android.com/build/releases/agp-8-11-0-release-notes
9. Совместимость Kotlin Gradle Plugin:
   https://kotlinlang.org/docs/gradle-configure-project.html

## Сборка с телефона через GitHub

10. Создание workflow Gradle и публикация артефактов:
    https://docs.github.com/en/actions/tutorials/build-and-test-code/java-with-gradle
11. Загрузка файла в репозиторий через браузер:
    https://docs.github.com/en/repositories/working-with-files/managing-files/adding-a-file-to-a-repository
12. Скачивание артефактов в браузере:
    https://docs.github.com/en/actions/how-tos/manage-workflow-runs/download-workflow-artifacts
13. Состав Ubuntu 24.04 runner, включая Android SDK:
    https://github.com/actions/runner-images/blob/main/images/ubuntu/Ubuntu2404-Readme.md
14. Официальные Gradle Actions:
    https://github.com/gradle/actions
15. Публикация артефактов GitHub:
    https://github.com/actions/upload-artifact

Облачный workflow обрабатывает только исходный код. Запуск CI в аккаунте пользователя и тестирование APK на устройстве не выполнялись при подготовке этих файлов.
