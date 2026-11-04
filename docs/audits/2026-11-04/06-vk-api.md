# VK API, авторизация и границы интеграции

## Первичные источники и доступность документации

Проверены официальный [VKCOM/vkid-android-sdk](https://github.com/VKCOM/vkid-android-sdk) и его README: SDK описывает OAuth 2.1, регистрацию собственного приложения, authorize и refreshToken. Это официальный путь авторизации, **не обещание доступа к музыке**. Из проекта VK Music Export он не вызывается.

Публичная [VKCOM/vk-api-schema](https://github.com/VKCOM/vk-api-schema) проверена на SHA **333481bd082ad747d4873ef4a77f9247097eeef0**. README указывает API 5.199. В `audio/` на этом SHA есть только [objects.json](https://github.com/VKCOM/vk-api-schema/blob/333481bd082ad747d4873ef4a77f9247097eeef0/audio/objects.json), `audio/methods.json` возвращает 404, раздела `catalog` в просмотренном публичном дереве нет. `friends.get` и `users.get` есть в соответствующих methods schema. В auth/methods нет используемых пяти WEB pairing методов. Наличие объекта audio или кода ошибки 201 не является правом вызова музыкальных методов.

Были предприняты обращения к [dev.vk.com/ru/reference](https://dev.vk.com/ru/reference), [правилам платформы](https://dev.vk.com/ru/rules), [исторической странице audio_api](https://vk.com/dev/audio_api) и документации VK ID на id.vk.ru. В этом окружении страницы вернули ошибки получения; актуальный полный текст правил, договорные исключения и первичная историческая страница **не прочитаны**. Поиск обнаружил вторичные сообщения о закрытии публичного audio API с 16.12.2016, но они не используются как подтверждение сегодняшних прав приложения. История ограничений в этой части остаётся неполностью проверенной по первоисточнику. В доступном endpoint истории `audio/methods.json` публичной схемы записей не найдено; это также не дата закрытия API.

Поэтому вывод ограничен: приложение использует внутренний музыкальный протокол; официальная поддержка всех вызываемых методов для собственного client ID **не подтверждена**. Категоричные заявления «VK ID откроет всю музыку», «всем можно скачать MP3» или «метод точно сейчас работает» не делаются. Юридическое разрешение на скачивание/распространение нельзя получить из JSON-схемы или HTTP200.

## Все прикладные API-методы

Вызовы отправляются POST в `https://api.vk.com/method/<фиксированный метод>`, access_token в теле, API version 5.199; arbitrary method/переопределение v/access_token в fields запрещены. Таблица отражает **код приложения**, не проведённый live contract test.

| Метод | Параметры приложения | Разбор/использование | Классификация доступа |
|---|---|---|---|
| users.get | без user_ids для владельца; user_ids, fields=photo_200,domain,screen_name для друга | Массив ровно одного владельца/профиля, сверка ID | Публично описан; разрешения/приватность конкретного запроса проверять |
| friends.get | count=100, offset, order=name, fields=photo_200,domain,screen_name | count/items, до 20000 друзей; duplicate ID не принимается как полный список | Публично описан; token/scope всё равно значимы |
| audio.get | owner_id, count=100, offset; playlist_id/access_key для подборки; count=1 для access badge | NativePage, повторные позиции сохранены; ошибки метаданных сохраняют известный ID | Внутренняя музыкальная интеграция, доступ не подтверждён публичным контрактом |
| audio.getPlaylists | owner_id, count=100, offset либо start_from; дополнительный filters=albums | count/items, next_from/next_offset; явная server type классификация | То же |
| audio.getPlaylistById | owner_id, playlist_id, access_key при наличии | Точный key; уточнение типа/карточки | То же |
| audio.getById | audios=точный owner_id_audio_id | Ссылка/метаданные/обложка exact-ID, без поиска по похожему названию | То же |
| audio.search | q, count=20, offset, auto_complete=0, search_own=0, sort=2 | Только lookup обложки/названия; максимум первых 100 результатов | То же; глобальный клиентский Search ещё не реализован |
| audio.delete | owner_id собственного профиля, audio_id | users.get привязывает точный token; только integer 1 — подтверждённый успех; без автоматического replay | Внутренняя мутация; отдельное одноразовое подтверждение |
| catalog.getAudio | lang=ru, need_blocks=1; owner_id либо current-user projection; url для внутренней навигации | Layout/sections/blocks, только распознанные subscriptions | Закрытый/недокументированный для этого приложения контракт |
| catalog.getAudioArtist | artist_id, need_blocks=1, lang=ru | Каталог страницы исполнителя | То же |
| catalog.getSection | section_id, start_from при наличии | Адресные части внутреннего каталога | То же |
| catalog.getBlockItems | block_id, start_from | Продолжение блока | То же |
| audio.getAudiosByArtist | artist_id, count=100, offset | Полный список artist tracks | Внутренняя музыкальная интеграция |

## WEB pairing и продление

`auth.getAnonymToken`, `auth.getQrAuthData`, `auth.getAuthCode`, `auth.checkAuthCode`, `auth.validateAuthCode` используют web client 7913379, версии 5.276 для anonymous/bootstrap и 5.126 для pair/poll/code. Код создаёт одноразовую заявку, открывает разрешённый VK URL в Chrome Custom Tab, опрашивает её только при foreground после явного открытия, ограничивает срок максимум пятью минутами. Шестизначный код относится к заявке; пароль не вводится в собственную форму приложения. Успех browser intent не считается успешным входом.

При super_app_token выполняется POST login.vk.com `connect_code_auth`, allowlisted callback GET с ограниченными redirects, затем `web_token` с client 6287487. Native cookie jar изолирован от профиля Chrome, ограничен и очищается; материал продления сохраняется в зашифрованном envelope. NativeProtocol.DEFAULT_AGENT — собственный probe UA, не identity официального Android клиента. В коде не найден встроенный секрет официального Android приложения; наличие числовых web client IDs не является таким секретом, но создаёт зависимость от чужого протокола.

Новый результат сохраняется лишь после users.get/audio.get и сверки account. Срок неизвестного токена не выдумывается; известный проверяется перед запросами, advance renewal — 1/5 lifetime, максимум 5 минут. Process-wide mutex объединяет refresh, CAS не позволяет позднему refresh воскресить удалённый envelope. request повторяет ACCESS один раз после renewal, но окно F01 требует устранения. Локальный logout не делает remote revoke. Ручной ввод token остался как тестируемый метод ViewModel.configure, штатный UI его больше не показывает.

## Ошибки, лимиты и пагинация

| Коды VK | Категория приложения | Значение для UX/повтора |
|---|---|---|
| 5,27,28 | ACCESS | Проверка/обновление доступа, затем новый вход при отказе |
| 6,9,29 | RATE_LIMIT | Не выдумывать фиксированную гарантированную квоту VK; сохранить прогресс |
| 14 | CAPTCHA | Явная проверка на стороне VK, не обход |
| 17 | VALIDATION | Подтверждение входа |
| 7,15,20,201,203 | DENIED | Доступ к конкретному запросу; 201 не доказывает конкретную причину приватности (F21) |
| 3 | METHOD | Метод недоступен подключению |
| Остальные | RESPONSE | Неизвестная/неподдерживаемая форма, без raw server message в UI/log |

HTTP429 отличается от 403. Scoped friend/search/getById denial не должен инвалидировать весь account; явный VK code=5 сохраняется как account error. CDN401/403/404/410 относятся к signed media link, а не автоматически к VK account token. Retry policy не едина: страницы повторяют только transient network/timeout/HTTP500/502/503/504 до двух повторов; audio transfer делает до трёх попыток с задержками. Retry-After/adaptive global backoff в коде не обнаружены; 350 ms pacer — внутреннее ограничение клиента, не официальный SLA VK.

Track pagination сохраняет count и позиции, отвергает ранний конец/изменившийся count/повтор страницы. Catalog объединяет более богатые повторные объекты, использует курсор без offset, подтверждённый next_offset и причины неполноты. Это не атомарный server snapshot: неизменный count сам по себе не доказывает отсутствие перестановки. Для одинаковых повторных страниц track loop выбирает безопасный отказ; edge case законно одинаковых страниц нужно отдельно согласовать, не отключать loop guard вслепую.

Кэш: friend availability только in-memory, session/account scoped, 15 минут; image preview URL до 2000, resolve cover hints до 8, ArtworkLoader до 4 изображений. Полного disk music catalog/cache search нет; receipts дубликатов — 24 часа, до 20000. Signed media URL намеренно не сохраняются в SQLite/журналы.

## Музыка друзей, каталоги и лицензии

Успешный пустой audio.get — доступный пустой раздел, не ошибка; denied и network/parse errors отображаются отдельно. Closed profile fields не являются автоматическим правом музыки. Чтение друга не даёт права менять его библиотеку; очередь остаётся viewer account, friend — source. Для capability проверки нужны друзья с доступным, закрытым, пустым разделом и частично доступными подборками, с заранее разрешённым тестированием.

Artist subscriptions разбираются по внутренним layout/headings/flags и ссылкам, а не извлекаются из имён артистов песен. Неподдерживаемый layout/timeout не превращается в нулевые подписки; это сильная сторона текущей логики, но хрупкая зависимость от layout. Recommendations, глобальный artist search и подписка/отписка не реализованы.

Лицензия кода GPL-3.0-or-later и лицензии библиотек не предоставляют прав на музыкальные записи, обложки или бренд VK. Для собственного исходного/разрешённого контента скачивание — отдельная capability. Модель массового сохранения каталога и распространения клиента требует проверки текущих VK terms и договорных прав; полный текст этих terms здесь недоступен, поэтому юридическая совместимость **не подтверждена и не объявлена нарушенной**. Партнёрские права или scope реального приложения невозможно вывести из репозитория.
