# Локальная проверка

Проверено7 ноября2026 года. Это отчёт об исходниках и изолированных тестах,
а не утверждение о развёртывании на чьём-либо сервере.

- Python3.11 / Pillow13.0.0 / yandex-music3.2.2:42 regression tests,
  все прошли, один Unix dirfd test пропущен на Windows. Проверяются native JWT,
  ограничения upload/avatar, actual/chunked byte limits, no-overwrite, private
  playlists, исходный порядок/повторы, lost-response resume, manual edit guards,
  cancellation, auth pause, глобальный source-ID cache, explicit legacy seed,
  orphan staging, согласованный WAL backup и ограничения setup.
- Настоящий Navidrome0.64.2 Windows amd64 загружен из official release;
  SHA256 архива проверен по official checksums. Изолированный localhost fixture
  `tests/check_native_import.py`: настоящий owner/JWT, точные relative paths и
  libraryId, приватный sync=false список,12 записей с повторами и намеренно
  неалфавитным порядком ID, повторная полная замена без дубликатов, сохранение
  остальных стабильных свойств и восстановление по private marker — PASS.
  Синтетические WAV, аккаунт и БД находятся в отдельной временной папке;
  остановлен только собственный Popen child.
- Настоящий SDK3.2.2 с подменённым сетевым transport: Authorization передаётся
  через Request.get/post в API session, отдельная CDN session не получает OAuth.
- Официальный standalone Compose2.39.4, Windows x86_64,
  SHA256 `6b3bccfabcdd172e1d9e15d011b54c9b5b13b93b1153148108f55e4349055955`:
  `--env-file .env.example -f compose.yml config --quiet` — exit0.

Docker daemon отсутствует: API-образ не собирался, контейнеры не запускались,
выдача настоящего HTTPS-сертификата и Linux container resource limits здесь не
проверены. FFprobe допускаемые/отклоняемые структуры покрыты fixtures; реальный
процесс FFprobe в новом контейнере нужно проверить после сборки на Docker-хосте.
Реальный OAuth Яндекс Музыки не использовался, сетевое скачивание из источника
и доступность подписки не подтверждены этим тестом. Ограничения native playlist
conditional replace описаны в README. Production изменения не выполнялись.
