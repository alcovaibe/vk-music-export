# План исправлений, зависимости, трудоёмкость и риски

Оценки — инженерные диапазоны для одного опытного Android-разработчика, рабочий день ≈6 часов сосредоточенной работы. Это не обещание срока и не проценты готовности. Они предполагают reuse текущего ядра, обычный code review и synthetic проверки; время согласования доступа VK, ожидания внешних разрешений и закупки устройств не оценимо по репозиторию. Части этапов пересекаются — складывать границы как точный календарный срок нельзя.

| Этап | Работа / задачи | Зависит от | Оценка | Риск / критерий выхода |
|---|---|---|---|---|
| A | Уточнить доступ провайдера, capabilities и scope нового клиента, F04/F05 | Нет | 2–5 дней технической проверки; внешние согласования неизвестны | Очень высокий внешний риск. Разрешённый аккаунт + таблица методов/условий; недоступное явно помечено |
| B | F01 read-session regression/fix; F22 согласовать lease | Нет; запустить первым | 1–3 дня | Высокая важность, небольшой код. Детерминированные interleavings и no foreign block |
| C | F02 канал доставки, F03 применённые правила, exact acceptance/evidence F05/F27 | B для нового выпуска; административный доступ | 2–4 дня технически + фактическая ручная проверка | Защита не включается коммитом JSON; exception publication не меняет pending |
| D | Baseline измерений и fixtures, F24 | Нет; можно параллельно A/B | 3–5 дней | Неустойчивые эмуляторные performance результаты. Реальный API29 baseline и modern device |
| E | DAO/repository, URI/source индексы, cursor/receipts F07–F11 | B,D | 6–10 дней | Высокий риск миграций. Ни один старый task key/URI не потерян, O(N) checkpoint |
| F | Разделить ViewModel/use cases и lifecycle, F12/F26; cleanup F30 | B,D; желательно E интерфейсы | 5–9 дней | Изменение cancellation/generation. Малые PR и сравнение поведения, не big bang rewrite |
| G | Playback persistence/resumption и общий TrackActions, F06/F16/F17 | A,B,E/F contracts | 6–10 дней | Account-aware restore, signed URL expiry, OEM media buttons; stable media service |
| H | Библиотека/поиск/друзья/offline collections/download screen, F13–F18/F29 | A,E,G | 8–15 дней | Remote capabilities неизвестны. Неподдерживаемые методы не подменяются результатом |
| I | Partial resume и cache/prefetch policy, F19 | A,D,E,G | 5–9 дней | Средне-высокий риск склейки несовместимого ресурса; можно отложить за MVP |
| J | Browser provider policy F20, нейтральные ошибки F21, accessibility F23, docs F28 | Основные маршруты G/H | 3–6 дней | Реальные TalkBack/font/IME/Chrome-no-Chrome проверки; согласованный UX |
| K | SAST/build-tool inventory/provenance F25/F27, release smoke | C,D | 3–5 дней | Неполный SBOM нельзя назвать полным; fail-closed scan, долговременный evidence |
| L | Ручная и длительная приёмка exact APK, исправление обнаруженного | A–H,J,K; I по scope | 5–10 дней + повтор после значимых правок | Высокий внешний/OEM риск, нельзя заменить зелёным debug CI |

Минимальный порядок: **B → E → G → H → L**, при этом A/C/D начинают раньше. G можно проектировать во время E, но данные миграции и read lease должны быть определены до завершения restore. F выполнять инкрементально вокруг этих изменений. I не должен задерживать выпуск базового клиента, если продукт явно принимает restart текущего файла.

Ориентир по суммарной работе MVP при доступном провайдере — порядка **40–70 рабочих дней** с reuse существующей функциональности и без полной реализации I; расширенный объём с длительным hardening/partial resume — больше. Этот диапазон существенно зависит от результата A и объёма ручных дефектов; он не выводится из числа Kotlin-строк. Если музыка не доступна по приемлемому provider contract, план online функций пересматривается, а offline/local клиент может развиваться независимо.

## Приёмочные гейты

1. Изоляция: запрос A не использует независимый login B; разрешено проверенное продление той же read session. Удаление/edits не replay; чужой account/source не наследуется.
2. Данные: upgrade с реальных baseline и synthetic v1/v2/v3 больших наборов, hash всех сохранённых файлов до/после; power/process kill на каждом durable checkpoint; originals/modified files не удаляются.
3. Производительность: опубликованный baseline на 100/4500/20000, ограниченное число SQL/provider запросов на экран/страницу; объём checkpoints не квадратичный. Не назначать целевые миллисекунды без baseline.
4. Playback: restore order/index/position/repeat/shuffle, source/capability и offline fallback; audio focus/BT/noisy/lockscreen; 1–8 часов реального прогона с сетью и остановками.
5. UX: доступность всех маршрутов, system Back, большой текст, TalkBack, понятный поиск/пустые/ошибки и сохранённые подборки.
6. Release: один конкретный signed APK с source SHA и digest; CI, ручная acceptance и актуальный канал совпадают; evidence не исчезает через месяц.

Первые исправления целесообразно готовить отдельными PR: (1) F01 с детерминированным тестом; (2) metadata/receipt checkpoint schema + migration; (3) доставляемый update channel и protection; (4) playback restore и unified track actions. В этом аудите ни один из них не внесён.
