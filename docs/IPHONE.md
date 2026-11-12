# iPhone через AltStore

Нативный клиент для iOS 17 и новее: библиотека Navidrome, поиск треков и исполнителей, личные плейлисты, избранное, офлайн-копии, персональный микс и серверный импорт Яндекс Музыки. Воспроизведение работает в фоне, управление — на экране блокировки и через системные медиакнопки.

## Установка с Windows

1. Установи AltServer и AltStore Classic по [официальной инструкции для Windows](https://faq.altstore.io/altstore-classic/how-to-install-altstore-windows). Используй указанные там версии iTunes и iCloud.
2. Скачай [`DedSec-Music-0.1.0-ios-unsigned.ipa`](https://github.com/Dedseckms/Dedsec-Music/releases/download/ios-v0.1.0/DedSec-Music-0.1.0-ios-unsigned.ipa) из [выпуска iPhone Preview](https://github.com/Dedseckms/Dedsec-Music/releases/tag/ios-v0.1.0).
3. Открой IPA через AltStore. AltStore подпишет приложение твоим Apple ID и установит его на телефон. Пароль Apple вводится только в установленный AltStore/AltServer; музыкальному серверу он не нужен.
4. В DedSec Music введи HTTPS-адрес своего Navidrome, логин и пароль музыкального аккаунта. Для загрузки файлов используется отдельный аккаунт загрузчика.

При бесплатном Apple ID подпись действует семь дней. Обновляй её через AltStore при доступном AltServer на компьютере. Установка через AltStore не является публикацией в App Store или TestFlight. Обновления приложения устанавливаются новой IPA; кнопка в настройках открывает GitHub Releases.

## Собрать самостоятельно

Открой вкладку Actions и запусти workflow **Build iPhone IPA**. После успешной сборки скачай артефакт `DedSec-Music-iOS-unsigned`: внутри IPA и контрольная сумма SHA-256. Сертификаты Apple для сборки не требуются — подпись добавляет AltStore при установке. Для собственного репозитория доступен [шаблон workflow](../ios/github-actions.yml).

На Mac с Xcode и XcodeGen:

```bash
swift test --package-path ios/Core
xcodegen generate --spec ios/project.yml
xcodebuild -project ios/DedSecMusic.xcodeproj -scheme DedSecMusic \
  -configuration Release -sdk iphoneos -destination 'generic/platform=iOS' \
  -derivedDataPath .tools/ios-build CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO build
```

Логин по желанию сохраняется в Keychain. Токен Яндекса хранится в оперативной памяти и отправляется только своему серверу. Задача импорта продолжает выполняться на сервере после закрытия приложения. Офлайн-копии разделены по музыкальным аккаунтам.

В первой версии оформление использует SwiftUI и системные материалы iOS. Рекомендации основаны на тегах собственной библиотеки. Работа на физическом iPhone проверяется отдельно от успешной сборки.
