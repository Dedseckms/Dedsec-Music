# Сборка из исходников

Android Studio необязательна: обе программы собираются через Gradle. Она полезна для эмулятора, отладки и визуального инспектора.

## Windows: подготовка

Нужны Windows x64, Python 3.9+, PowerShell и Git. Из корня проекта:

```powershell
python -m pip install requests
python tools/bootstrap.py
.\build.ps1 -Tasks @(':app:assembleDebug', ':app:testDebugUnitTest', ':shared:protocol:test', ':desktop:test')
```

Скрипт скачивает локальные инструменты в игнорируемую `.tools/`: JDK 21, Gradle 8.13 и Android SDK. Android использует compile/target SDK 36, min SDK 26. Если лицензии SDK ещё не приняты, прими их через `sdkmanager --licenses` из установленного Android SDK.

Debug APK находится в `app/build/outputs/apk/debug/`. Он подписан отладочным ключом и не обновляет установленный release APK с другой подписью.

## Windows-клиент и установщик

```powershell
.\tools\prepare-desktop.ps1
.\build.ps1 -Tasks @(':desktop:test', ':desktop:createDistributable')
.\tools\build-installer.ps1
```

Приложение включает собственные Java runtime и libVLC. Для установки готовой программы отдельная Java не нужна. Установщик сохраняется в `artifacts/desktop/` и устанавливает приложение в профиль пользователя.

Проверка установщика и обновления выполняется отдельно:

```powershell
.\tools\test-installer.ps1
.\tools\test-update.ps1
```

Они создают временную установку в `.tools/` и не предназначены для проверки поверх основной рабочей установки.

## Android release

```powershell
.\tools\initialize-signing.ps1
.\build.ps1 -Tasks @(':app:testReleaseUnitTest', ':app:lintRelease', ':app:assembleRelease')
.\tools\export-apk.ps1
```

Ключ создаётся в игнорируемой `.secrets/`. Храни его резервную копию отдельно: для обновления нужен тот же ключ. Новый ключ не заменит подпись уже установленной сборки.

На Linux/macOS установи JDK 21, Android SDK 36 и build-tools 35.0.0, укажи `ANDROID_HOME`, затем используй `./gradlew`. Для подписи положи свой PKCS12 в `.secrets/release.p12` с alias `dedsec-music`, а пароль — в `.secrets/signing-password.txt`. Не добавляй их в Git.

## Проверки

```powershell
.\build.ps1 -Tasks @(':shared:protocol:test', ':desktop:test', ':app:testReleaseUnitTest', ':app:lintDebug')
```

`desktop:renderGallery` создаёт скриншоты настоящих composable-экранов с демонстрационными данными в `docs/assets/`. Это не скриншоты личной коллекции.

[Подготовка манифеста обновлений](UPDATES.md) не создаёт GitHub Release и ничего не публикует.
