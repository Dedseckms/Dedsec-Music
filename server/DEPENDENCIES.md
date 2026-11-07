# Зависимости

Собственные скрипты распространяются под GPL-3.0-or-later. Контейнеры и библиотеки
сохраняют свои лицензии; файл `api/requirements.txt` закрепляет Python-пакеты и
допустимые SHA256 колёс для Linux amd64/arm64. FFmpeg устанавливается из
репозитория Debian Bookworm внутри API-образа, без изменения хоста.

| Компонент | Версия | Исходники / лицензия |
|---|---|---|
| Navidrome |0.64.2 |[repo](https://github.com/navidrome/navidrome/tree/v0.64.2), GPL-3.0 |
| Caddy |2.11.9 |[repo](https://github.com/caddyserver/caddy), Apache-2.0 |
| Python |3.12.16 |[repo](https://github.com/python/cpython), PSF |
| Pillow |13.0.0 |[repo](https://github.com/python-pillow/Pillow), HPND |
| yandex-music |3.2.2 |[repo](https://github.com/MarshalX/yandex-music-api/tree/v3.2.2), LGPL-3.0 |
| Mutagen |1.48.1 |[repo](https://github.com/quodlibet/mutagen), GPL-2.0-or-later |
| Requests |2.34.2 |[repo](https://github.com/psf/requests), Apache-2.0 |
| certifi |2026.10.23 |[repo](https://github.com/certifi/python-certifi), MPL-2.0 |
| charset-normalizer |3.5.2 |[repo](https://github.com/jawah/charset_normalizer), MIT |
| idna |3.20 |[repo](https://github.com/kjd/idna), BSD-3-Clause |
| urllib3 |2.8.0 |[repo](https://github.com/urllib3/urllib3), MIT |
| PySocks |1.7.1 |[repo](https://github.com/Anorov/PySocks), BSD |
| typing-extensions |4.16.0 |[repo](https://github.com/python/typing_extensions), PSF |

Самостоятельный provider использует публичные методы SDK. Сторонний загрузчик,
код обхода шифрования или клиентские OAuth-секреты в комплект не включены.
