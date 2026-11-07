# API переноса музыки

Все запросы выполняются на свой HTTPS-сервер. Авторизация:
`Authorization: Bearer <native Navidrome JWT>`. JWT получают через штатный
`POST /auth/login`, подтверждают native API для текущего пользователя; API не
принимает просто декодированный неподтверждённый JWT. Чужая задача возвращает404.
Токены и пароли нельзя передавать в query, логировать или сохранять в клиентских
логах. Следовать HTTP redirects с credentials нельзя.

База: `/dedsec/import/v1/yandex/jobs`.

| Запрос | Результат |
|---|---|
| `POST` базы, JSON `{"oauthToken":"..."}` |202 `{"job":{...}}` |
| `GET /current` |200 `{"job":{...}}` либо `{"job":null}` |
| `GET /{id}` |200 `{"job":{...}}` |
| `POST /{id}/cancel` |202 `{"job":{...}}` |
| `POST /{id}/resume`, JSON `{"oauthToken":"..."}` |202 `{"job":{...}}` |

JSON входа ограничен16KiB. OAuth —8–8192 печатных ASCII-символов без пробелов.
401 означает необходимость заново войти в Navidrome;409 — уже активная задача или
неподходящее состояние;429 — очередь заполнена. Ошибка:
`{"error":{"code":"FINITE_CODE","message":"безопасное пояснение"}}`.
Ответ не содержит OAuth, native JWT, signed URL или сырых сетевых исключений.

Поля `job`:

| Поле | Тип / смысл |
|---|---|
| `id` | UUID |
| `state` | `queued`, `running`, `cancelling`, `cancelled`, `paused_auth`, `completed`, `finished_with_errors`, `failed` |
| `phase` | `queued`, `inventory`, `classification`, `download`, `indexing`, `playlists`, `paused`, `finished` |
| `discovered` | число уникальных найденных source ID |
| `total` | пригодная музыка после классификации, исключённые вне total |
| `downloaded`, `skipped`, `failed`, `unavailable`, `excluded` | абсолютные счётчики, не приращения |
| `processed` | downloaded+skipped+failed+unavailable; excluded не входит |
| `totalBytes` | размер готового аудио задачи, включая skipped |
| `current` | null либо `{sourceId,title,artist}`; строки могут быть пустыми |
| `createdAt`, `updatedAt` | ISO8601 UTC |
| `finishedAt` | ISO8601 UTC либо null |
| `errorCode` | null либо `AUTH_REQUIRED`, `SOURCE_UNAVAILABLE`, `NETWORK_ERROR`, `NO_SPACE`, `SOURCE_LIMIT`, `INTERNAL_ERROR`, `CANCELLED` |
| `playlistCount` | необязательный счётчик музыкальных исходных плейлистов |
| `completedPlaylistCount` | готовые приватные плейлисты |
| `pendingPlaylistCount` | ещё требующие индексирования/сверки |
| `missingPlaylistTracks` | отсутствующие музыкальные вхождения, включая повторы |

Счётчики —64-битные неотрицательные целые. `total` уточняется во время классификации,
не является исходным количеством всех записей. При resume не суммируйте старый и
новый ответ. Для отображения прогресса достаточно опроса раз в3–5 секунд.
В фазах indexing/playlists100% аудио ещё не означает завершение всей задачи.

Одна активная задача включает `paused_auth`; для следующего источника отмените её
либо продолжите с токеном того же исходного аккаунта. OAuth/native JWT находятся
только в памяти worker. Перезапуск переводит незавершённые задачи в paused_auth,
скрытое продолжение старым токеном невозможно. Отмена не удаляет готовую музыку
или созданные приватные списки. Полностью недоступные списки не создаются пустыми.
Незавершённый/конфликтный перенос не возвращает completed.

Обновляются лишь списки с сохранённой private marker-связью и неизменённым полным
составом/свойствами; чужие и вручную созданные списки не затрагиваются. При
неопределённом результате создания сохраняется intent: восстановление ищет
точный private marker; при его отсутствии не создаёт слепо второй список.
Во время активного переноса клиенту следует отключать редактирование своих
плейлистов. Native Navidrome не предоставляет атомарный conditional replace.
