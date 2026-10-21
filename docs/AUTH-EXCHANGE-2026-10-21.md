# Разбор AUTH_UNSUPPORTED_TOKEN в 0.8.2

## Подтверждённые наблюдения

Владелец передал скриншот ошибки AUTH_UNSUPPORTED_TOKEN и начало JSON запуска 0.8.2 (1059), Samsung SM-S731B / Android 17. Ниже время Москвы, UTC+3, 21 октября 2026 года.

| Время | Наблюдение |
| --- | --- |
| 08:24:18.899 | Нажатие подключения; PREPARING. |
| 08:24:20.115–20.319 | anonymous, qr_bootstrap и create: HTTP 200, распознаны ожидаемые поля; заявка готова. |
| 08:24:22.017 | Заявка передана com.android.chrome, chrome_custom_tab, начальный узел qr.vk.ru. |
| 08:24:22.108 | Первый опрос: status=0, token_kind=none. |
| 08:24:37.192 | Activity вернулась на передний план. |
| 08:24:37.718–37.719 | Опрос: status=5; приложение перешло в ENTER_CODE. |
| Скриншот 08:24 | Приложение показывает AUTH_UNSUPPORTED_TOKEN. |

JSON обрывается внутри события после ENTER_CODE. Отправка кода, ответ validate, время и содержимое окончательного опроса в переданной части отсутствуют. Полный файл нужен для точной последующей хронологии, но не для определения конкретной ветки ошибки.

В исходниках выпущенной [0.8.2](https://github.com/alcovaibe/vk-music-export/blob/v0.8.2-build.1059/app/src/main/java/app/alcovaibe/vkmusicexport/music/DeviceAuth.kt) UNSUPPORTED_TOKEN возникает только в ветке status=2: после исключения need_password/is_partial, при отсутствии строкового access_token и наличии ненулевого super_app_token. Это вывод из точного кода ошибки на скриншоте. Нельзя называть его подтверждённым числовым событием из обрезанного JSON. Браузерная часть вернула результат подтверждения, но проверка users.get/audio.get ещё не выполнялась. Отказ музыки, неисправность VPN и запрет исполнения JavaScript этим результатом не установлены.

## Первичные реализации протокола

- [MusicX LoginCategory, фиксированный коммит 41124888](https://github.com/Fooxboy/MusicX-WPF/blob/41124888f71191b3f8a0894141adf0703faf8c14/VkNet.AudioBypassService/Categories/LoginCategory.cs): POST connect_code_auth, app_id=7913379, token и uuid; ответ type=okay, data.response_type=auth_token, data.access_token. Это подтверждает недостающий этап, но само по себе не доказывает музыкальных прав результата.
- [MeridiusNova qr-check, коммит a9e24a37](https://github.com/PurpleHorrorRus/MeridiusNova/blob/a9e24a374338644cd0589dca06a1e13990454545/server/api/vk/qr-check.get.ts): после status=2 отправляет super_app_token в connect_code_auth, завершает next_step_url и передаёт data.access_token в webToken.
- [MeridiusNova HTTP-клиент того же коммита](https://github.com/PurpleHorrorRus/MeridiusNova/blob/a9e24a374338644cd0589dca06a1e13990454545/server/utils/http.ts): web_token с version=1, app_id=6287487 и access_token предыдущего этапа. [Типы ответа](https://github.com/PurpleHorrorRus/MeridiusNova/blob/a9e24a374338644cd0589dca06a1e13990454545/server/types/auth.ts): data.access_token, user_id и expires.

Это внутренние веб-методы, не обещанный публичной документацией VK ID музыкальный OAuth. Общедоступный контракт этого обмена в официальной схеме не обнаружен. Реализация написана самостоятельно по сообщениям протокола; не переносит логирование ответов, пользовательских данных или cookies из других проектов.

## Исправление 0.8.3

DeviceCheck.ExchangeRequired хранит super_app_token только в памяти с закрытым строковым представлением. Появляется фаза EXCHANGING. Прямой access_token по прежнему сценарию сохраняет существующую ветку. После получения промежуточного auth_token выполняется web_token; промежуточный токен никогда не используется как доказательство музыкального доступа.

Два POST идут на фиксированный login.vk.com, токены только в теле. Одноразовый callback может содержать чувствительные параметры: целиком остаётся в памяти и не попадает в журнал. Callback проверяется до GET, допускает точные HTTPS-узлы vk.com/vk.ru/login.vk.com/login.vk.ru и порт 443, без userinfo/fragment. После первого GET разрешено до четырёх перенаправлений; повтор URL и внешний узел останавливают обмен. POST по редиректу не повторяется. Callback читает только заголовки, HTML не читается и не сохраняется.

Для собственной сессии обмена создаётся ограниченный CookieJar: до 32 cookies и суммарно до 16 КиБ имён/значений; переполнение даёт явный сигнал и прекращает обмен. Jar принимает cookies только от разрешённых HTTPS-узлов и передаёт только подходящие узлу/пути. Cookies Chrome не читаются. Jar очищается в finally при любом исходе и не входит в хранилище доступа.

JSON ограничен 1 МиБ и глубиной 32. Учитывается объявленная кодировка UTF-8/windows-1251: последняя присутствует в обработчике Meridius; кириллица во вложенном ответе не должна ломать обмен. Другие кодировки отклоняются. Типы полей проверяются строго; partial, need_password, service и signup отклоняются. Произвольные error_info/error_msg, поля пользователя, cookies, тела, URL и токены не логируются. Новые события: web_connect, web_callback, web_token, безопасная форма ответа, итог api_token_received с music_access=not_proven и очистка native_cookies_cleared. У callback body_observation=headers_only: это не трассировка содержимого страницы.

Исходный монотонный срок заявки действует и во время обмена. Обмен начинается после возвращения Activity; отмена закрывает сетевой вызов, поздний результат после срока не принимается. Новый токен записывается существующим AES-GCM/AndroidKeyStore только после успешных users.get/audio.get. При отказе предыдущий доступ остаётся.

## Что проверять на телефоне

Успех: подтверждение/код → status=2, token_kind=super_app → EXCHANGING → web_connect → при наличии web_callback → web_token → api_token_received → VERIFYING → успешный users.get/audio.get → CONNECTED. Последнее обязательно: ни super_app_token, ни промежуточный auth_token, ни успешный web_token сами по себе не подтверждают музыку.

Реальная совместимость обмена и права audio.get остаются проверяемой гипотезой. Веб-токен может быть краткоживущим; автоматическое обновление без повторного входа не реализовано. При ошибке нужен полный JSON через «Поделиться JSON-файлом», а не сами токены или код. Результаты синтетических JVM/Android-проверок не заменяют вход владельца.
