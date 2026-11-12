# Third-party libraries

## Windows components

| Project | License | Source |
| --- | --- | --- |
| Compose Multiplatform and Skiko | Apache-2.0 | https://github.com/JetBrains/compose-multiplatform and https://github.com/JetBrains/skiko |
| Skia | BSD-3-Clause and component notices | https://skia.googlesource.com/skia/ |
| vlcj 4.12.1 and vlcj-natives 4.12.0 | GPL-3.0-or-later | https://github.com/caprica/vlcj and https://github.com/caprica/vlcj-natives |
| VLC / libVLC 3.0.24 | GPL-2.0-or-later / LGPL-2.1-or-later by component | https://downloads.videolan.org/videolan/vlc/3.0.24/vlc-3.0.24.tar.xz |
| JNA 5.18.1 | LGPL-2.1-or-later or Apache-2.0 | https://github.com/java-native-access/jna |
| Amazon Corretto 21 | GPL-2.0 with Classpath exception and component notices | https://github.com/corretto/corretto-21 |
| JSON-java 20250107 | Public domain | https://github.com/stleary/JSON-java |

Original notices remain in the bundled JARs, `runtime/legal/` and `app/resources/vlc/`. Windows packages also carry the collected copyright, license and notice texts in `licenses/`, including Skiko, Skia and vlcj notices absent from their runtime JARs. Corresponding source archives for redistributed native components accompany the release; exact revisions are listed in `licenses/windows/README.md` in the source tree.

This software is based in part on the work of the Independent JPEG Group.

Portions of this software are copyright © 2024 The FreeType Project (www.freetype.org). All rights reserved.

The Windows installer is built with Inno Setup 7.1.1 (Copyright Jordan Russell and Martijn Laan), https://jrsoftware.org/ and https://github.com/jrsoftware/issrc . Its original setup engine notices are retained. The compiler is a local build tool and is not installed with DedSec Music.

## Android and shared components

This application uses these independently distributed open-source libraries:

| Project | License | Source and license |
| --- | --- | --- |
| AndroidX, Jetpack Compose, Material Icons, Media3, WorkManager | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| Kotlin and kotlinx.coroutines | Apache-2.0 | https://github.com/JetBrains/kotlin and https://github.com/Kotlin/kotlinx.coroutines |
| OkHttp and Okio | Apache-2.0 | https://github.com/square/okhttp and https://github.com/square/okio |
| Coil | Apache-2.0 | https://github.com/coil-kt/coil |
| Guava | Apache-2.0 | https://github.com/google/guava |

Copyright and license notices embedded in library artifacts remain with their respective authors. The Apache License 2.0 is available at https://www.apache.org/licenses/LICENSE-2.0 . Android platform components are supplied separately by the operating system. JUnit and MockWebServer are test-only dependencies.

## Self-hosted server components

The server kit references upstream container images and installs separately licensed Python dependencies. It does not include account data or upstream music files.

| Project | License | Source and license |
| --- | --- | --- |
| Navidrome | GPL-3.0 | https://github.com/navidrome/navidrome |
| Caddy | Apache-2.0 | https://github.com/caddyserver/caddy |
| Pillow | MIT-CMU | https://github.com/python-pillow/Pillow/blob/main/LICENSE |
| yandex-music-api | LGPL-3.0 | https://github.com/MarshalX/yandex-music-api |
| Mutagen | GPL-2.0-or-later | https://github.com/quodlibet/mutagen |
| Requests | Apache-2.0 | https://github.com/psf/requests |
| urllib3 | MIT | https://github.com/urllib3/urllib3 |
| certifi | MPL-2.0 | https://github.com/certifi/python-certifi |
| charset-normalizer | MIT | https://github.com/jawah/charset_normalizer |
| idna | BSD-3-Clause | https://github.com/kjd/idna |
| PySocks | BSD-3-Clause | https://github.com/Anorov/PySocks |
| typing-extensions | Python Software Foundation license | https://github.com/python/typing_extensions |
| FFmpeg / FFprobe | LGPL / GPL depending on distribution build | https://ffmpeg.org/legal.html |

Original copyright and license files remain in installed packages and container images. Their authors are independent from this project. When redistributing binaries or images, retain the required notices and provide corresponding source where their licenses require it.
