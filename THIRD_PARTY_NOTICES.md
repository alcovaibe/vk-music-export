# Сторонние компоненты

Лицензия GPL-3.0-or-later проекта не меняет лицензии зависимостей. Основные библиотеки, используемые приложением:

| Компонент | Правообладатели / проект | Лицензия |
|---|---|---|
| Kotlin standard library | JetBrains / Kotlin contributors | Apache-2.0 |
| kotlinx.coroutines, kotlinx.serialization | JetBrains / contributors | Apache-2.0 |
| AndroidX, Compose, Lifecycle, Activity, Browser | Android Open Source Project / contributors | Apache-2.0 |
| AndroidX Media3 | Android Open Source Project / Media3 contributors | Apache-2.0 |
| OkHttp, Okio | Square / contributors | Apache-2.0 |
| Guava | Google / Guava contributors | Apache-2.0 |

Полный текст Apache-2.0 — [third_party/Apache-2.0.txt](third_party/Apache-2.0.txt). Сохраняются также поставляемые библиотеками META-INF LICENSE/NOTICE. Таблица описывает основные компоненты приложения; точные транзитивные версии приложения и тестов перечисляются в SBOM `sbom.cdx.json`, сформированном из реально разрешённого Gradle-графа. Он сохраняется с доказательствами сборки в артефактах GitHub Actions `VK-Music-Export-release` или `Verified-release` на 30 дней. Вложения публичного релиза содержат APK и `SHA256SUMS.txt`; отдельный SBOM туда не прикладывается. Порядок воспроизведения сборки описан в [CONTRIBUTING.md](CONTRIBUTING.md#сборка-и-проверки).

Gradle plugin/build/test компоненты не обязательно входят в APK и могут иметь другие лицензии (например JUnit/EPL, Hamcrest/BSD). При изменении зависимостей проверяйте их upstream LICENSE/NOTICE и обновляйте этот файл и офлайн-копию в APK; SBOM сам по себе не является юридической проверкой лицензий. Лицензия проекта, уведомления и тексты лицензий доступны в приложении без сети через «О приложении» → «Лицензия».

Ссылки на первоисточники: [Kotlin](https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt), [coroutines](https://github.com/Kotlin/kotlinx.coroutines/blob/master/LICENSE.txt), [serialization](https://github.com/Kotlin/kotlinx.serialization/blob/master/LICENSE.txt), [AndroidX](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt), [Media3](https://github.com/androidx/media/blob/release/LICENSE), [OkHttp](https://github.com/square/okhttp/blob/master/LICENSE.txt), [Okio](https://github.com/square/okio/blob/master/LICENSE.txt).

VK Music Export — независимый проект. Названия ВК и Android принадлежат соответствующим правообладателям; проект не заявляет их поддержку или официальное партнёрство.
