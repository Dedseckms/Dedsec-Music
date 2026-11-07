# Обновления через GitHub

Android и Windows проверяют последний обычный Release в `Dedseckms/Dedsec-Music` при запуске. Черновики и prerelease не устанавливаются. Проверку можно повторить в настройках. Пока Releases пуст, программа не предлагает обновление.

Скачивание и установка запускаются пользователем. На Android окончательное подтверждение показывает системный установщик. На Windows приложение перезапускается после проверки и замены файлов; при неудачном старте предусмотрен откат.

## Проверки

| Android | Windows |
| --- | --- |
| HTTPS и ограниченный список GitHub-хостов. | HTTPS и ограниченный список GitHub-хостов. |
| Размер и SHA-256 APK из манифеста. | Ed25519-подпись исходных байтов манифеста. |
| Точный package name, больший versionCode, совпадение сертификатов с установленным приложением. | Размер и SHA-256 ZIP. Проверки путей при распаковке. |
| Android проверяет подпись APK и запрашивает согласие на установку. | Отдельный процесс применяет обновление и сохраняет предыдущую установку до проверки запуска. |

Манифест на Android доставляется по HTTPS; доверие к исполняемому коду обеспечивается подписью APK установленного приложения. Совпадения хеша из манифеста самого по себе недостаточно для доверия к чужому APK.

## Подготовка будущего выпуска

1. Увеличь версии в `app/build.gradle.kts` и `desktop/.../Appearance.kt`. Android требует увеличения `versionCode`.
2. Собери и проверь обе программы. Подписывай Android тем же ключом, что предыдущую установленную версию.
3. Подготовь Windows ZIP через `tools/export-desktop.ps1` и установщик через `tools/build-installer.ps1`.
4. Подготовь единый манифест для обеих платформ:

```powershell
.\tools\prepare-release-manifest.ps1 `
  -Tag 'v-next' `
  -WindowsArchive 'artifacts/desktop/DedSec-Music-VERSION-windows-x64.zip' `
  -WindowsVersion '0.3.0' `
  -AndroidApk 'artifacts/DedSec-Music-VERSION.apk' `
  -AndroidVersion '0.6.0' -AndroidVersionCode 9
```

Подставь реальные версии и пути. Скрипт только создаёт локальные `updates.json` и `updates.json.sig` в `artifacts/releases/`; он **ничего не публикует**. Приватный ключ Ed25519 находится в игнорируемой `.secrets/updates-private.pk8`; встроенный публичный ключ — в ресурсах desktop. Не заменяй ключ существующей линии обновлений.

При будущем выпуске в один обычный GitHub Release должны попасть APK, Windows ZIP, установщик, `updates.json` и `updates.json.sig`. Обе платформы используют `/releases/latest`, поэтому каждый такой выпуск должен содержать сведения об актуальных версиях **обоих** клиентов. Отдельно сохрани контрольные суммы и необходимые лицензии/исходники сторонних компонентов.

Схема манифеста:

```json
{
  "schema": 1,
  "windows-x64": {
    "version": "0.3.0",
    "url": "https://github.com/Dedseckms/Dedsec-Music/releases/download/v-next/DedSec-Music-0.3.0-windows-x64.zip",
    "sha256": "64 lowercase hex characters",
    "size": 123456
  },
  "android": {
    "version": "0.6.0",
    "versionCode": 9,
    "url": "https://github.com/Dedseckms/Dedsec-Music/releases/download/v-next/DedSec-Music-0.6.0.apk",
    "sha256": "64 lowercase hex characters",
    "size": 123456
  }
}
```

Подпись относится к точным байтам всего `updates.json`: после подписания не меняй даже пробелы. Скрипт `tools/ReleaseSigning.java` умеет подписывать и проверять манифест локально. Сторонняя сборка со своим ключом должна иметь собственную линию публикации и настройки доверия.
