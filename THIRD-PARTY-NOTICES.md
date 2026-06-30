# Third-party notices

Dink bundles the following open-source libraries. The in-app list lives at
**Settings → About**; this file is the canonical, fuller version.

Most are licensed under the **Apache License 2.0**
(https://www.apache.org/licenses/LICENSE-2.0); jAudioTagger is **LGPL 2.1**.

| Library | Used for | License |
|---------|----------|---------|
| AndroidX — Core, AppCompat, Activity, Lifecycle, DataStore, Security-Crypto, WorkManager | App framework, encrypted prefs, background work | Apache-2.0 |
| Jetpack Compose + Compose for TV (`androidx.tv.material3`) | UI | Apache-2.0 |
| Media3 / ExoPlayer | Playback engine, media session | Apache-2.0 |
| Kotlin standard library | Language runtime | Apache-2.0 |
| kotlinx-coroutines | Async / concurrency | Apache-2.0 |
| kotlinx-serialization | JSON (library index, settings) | Apache-2.0 |
| smbj (com.hierynomus) | SMB / network share client | Apache-2.0 |
| OkHttp (Square) | HTTP (online lyric providers) | Apache-2.0 |
| jAudioTagger | Reading embedded ID3 lyrics from local files | LGPL-2.1 |

## jAudioTagger (LGPL 2.1)

jAudioTagger is licensed under the GNU Lesser General Public License, version 2.1
(https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html). It is used unmodified.
Its source is available from the upstream project; because Dink itself is MIT
licensed and source-available, the LGPL's relinking provisions are satisfied by
the published source repository.

## Apache 2.0

A copy of the Apache License 2.0 is available at
https://www.apache.org/licenses/LICENSE-2.0. Each Apache-licensed dependency is
distributed unmodified under that license.
