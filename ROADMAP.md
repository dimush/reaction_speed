# Reaction Speed 4.0 — Roadmap

Живой документ. Оркестратор (Claude) обновляет статусы и вносит изменения по ходу работы.
Статусы: `[ ]` не начато · `[~]` в работе · `[x]` готово · `[!]` заблокировано (ждёт пользователя)

## Решения (согласовано с пользователем 2026-09-20)

- **Соревнование:** Google Play Games Services v2 — лидерборды + достижения.
- **Глубина:** полный апгрейд на Kotlin, новая оболочка на Material 3, игровая механика сохраняется.
- **Релиз:** автономная загрузка в **internal testing**; в production — только после явного «да».
- **Реклама:** AdMob-баннер + UMP consent; ссылку на Pro-версию убрать.

## Целевая архитектура

- Toolchain (проверен на соседнем проекте BlaBlarium): AGP 9.2.1, Gradle 9.4.1, Kotlin 2.2.10,
  Compose BOM 2026.02.01, version catalog, compileSdk/targetSdk 36, minSdk 24, JDK 21.
- Один модуль `app`, пакет `org.softosaurus.reactionspeed`, single-activity:
  - `game/` — `GameEngine` (чистая Kotlin-логика без Android: state machine, тайминг, подсчёт
    результата с отсечением выбросов по СКО — как в оригинале), покрыта unit-тестами;
    `GameView` (SurfaceView, рендер-поток с корректным lifecycle, время по `event.eventTime`/`uptimeMillis`).
  - `data/` — `ResultsRepository` (DataStore/SharedPreferences) + **миграция старых данных**
    из prefs `ReactionSpeedActivity` (`best_res*`, `res*`, `use_*`) — пользователи не должны потерять историю.
  - `ui/` — Compose + Material 3: Home, Game (хост для GameView), Result, Stats (график истории), Settings, edge-to-edge.
  - `games/` — `PlayGamesManager`: вход, отправка очков, достижения, открытие нативных экранов. Без настроенного
    app id фича тихо отключается.
  - `ads/` — UMP consent → MobileAds init → adaptive banner.
- Визуальный стиль: сохранить «каменную» тему (трава, плиты, валун) в игровом экране, оболочку сделать современной.

## Лидерборды и достижения (проект)

Лидерборды (меньше = лучше, формат «время, мс»):
1. `leaderboard_best_average` — лучший средний результат серии из 10 (основная доска).
2. `leaderboard_best_single` — лучшая единичная реакция (с античит-порогом ≥ 100 мс).

Достижения: первая серия; < 350 / < 300 / < 250 / < 220 мс среднее; 10 / 50 / 200 серий (incremental);
«Стабильность» (СКО < 25 мс); «Без выбросов» (все 10 попыток засчитаны).

Античит: результаты < 100 мс не отправляются (физиологически невозможны); нажатие до появления цели = фальстарт.

## Фазы

### Фаза 0 — Гигиена репозитория
- [x] Ветка `modernize-4.0`; `.gitignore` (keystore.properties, build/, local.properties, *.aab); убрать мусор из индекса (`app/release/*.aab`, `.iml`, `import-summary.txt`); коммит текущего состояния.

### Фаза 1 — Сборка (Sonnet)
- [x] Перевод на Kotlin DSL + version catalog, AGP 9.2.1 / Gradle 9.4.1, SDK 36, minSdk 24.
- [x] versionCode 11 (в production уже 10 = «3.1»), versionName 4.0 в gradle; signingConfig из `keystore.properties`; R8 + shrinkResources.
- [x] `./gradlew assembleDebug` зелёный на существующем Java-коде (точка отсчёта).

### Фаза 2 — Игровое ядро (Opus)
- [x] `GameEngine` на Kotlin + unit-тесты (тайминг, фальстарт, СКО-фильтр, top-10, история).
- [x] `GameView` — рендер, анимации плит/валуна, звук (SoundPool.Builder), вибрация (VibrationEffect), корректный stop/start потока.
- [x] `ResultsRepository` + миграция legacy prefs + тест миграции.

### Фаза 3 — UI-оболочка (Sonnet, после Фазы 2)
- [x] Compose/M3: Home, Result, Stats (график), Settings; edge-to-edge; predictive back; тема.
- [x] Локализации en / ru / de; все строки из ресурсов.
- [x] Адаптивная иконка + monochrome; splash (core-splashscreen).

### Фаза 4 — Play Games Services (Opus — код; пользователь/Chrome — консоль)
- [x] `PlayGamesManager` (PGS v2 SDK), отправка очков/достижений, кнопки «Лидерборды» / «Достижения».
- [x] `games-ids.xml` с плейсхолдерами; graceful-off без id.
- [!] Настройка в Play Console: проект Play Games, OAuth-клиенты (SHA-1 upload key + app signing key), 2 доски, достижения, публикация PGS-проекта. → `docs/PLAY_GAMES_SETUP.md`
- [!] Вписать реальные id (после настройки консоли) → сборка versionCode 13 →, проверить на устройстве/эмуляторе с тестовым аккаунтом.

### Фаза 5 — Реклама и приватность (Sonnet)
- [x] play-services-ads (актуальная) + UMP consent flow, adaptive anchored banner, пункт «Privacy options» в Settings.
- [x] Убрать ссылку на Pro. Проверить манифест (AD_ID, INTERNET), политику конфиденциальности, Data safety заметки → `docs/STORE_CHECKLIST.md`.

### Фаза 6 — QA и релиз
- [x] Unit-тесты + lint зелёные; code review (Opus) всего диффа; security review.
- [x] Smoke на эмуляторе `Medium_Phone_API_36.0`: запуск, серия, миграция данных поверх старой версии 3.0, скриншоты.
- [x] Release AAB, подпись, `node tool/play_upload.mjs --track internal`.
- [x] Release notes en/ru/de; обновить README.
- [!] Подтверждение пользователя → promote в production.

### Фаза 7 — Оформление: персонажи, анимация, звук, музыка (запрос пользователя 2026-09-21)
Концепция: цели — смешные красные круглые «рожи»-монстрики, выскакивающие из травы (все красные и одного размера —
честность замера сохраняется); на экране результата — зверь-маскот по уровню (ленивец → черепаха → кролик → гепард → супергерой-молния);
на главном — маскот-динозавр (Softosaurus). Всё генерируется локально: картинки — ComfyUI (скилл comfy-image-gen, только sdxl),
звуки/музыка — процедурный синтез (numpy/scipy), голоса — офлайн TTS Windows (en/de/ru) с питч-шифтом «под мультяшку». Без скачиваний из сети → чистые лицензии.
- [~] Арт (Sonnet): 10 рож-целей (круглый кроп, WebP), 5 маскотов уровня + «фальстарт», маскот главного экрана, фон-трава получше, иконки 10 достижений 512px, feature graphic 1024×500 → `art/` (исходники) и `res/drawable-nodpi`.
- [~] Аудио (Opus): хиты (несколько вариантов «боньк/поп/писк»), появление цели, фальстарт, промах, фанфары по уровням, рекорд, UI-клик, музыкальные лупы (меню/игра), голосовые реплики en/de/ru → `tool/audio/*.py` (воспроизводимая генерация) и `res/raw*`.
- [ ] Интеграция (Opus): спрайты в GameRenderer (pop-in, сквош/вращение/звёздочки при попадании, «дразнилка» при фальстарте), MusicPlayer с lifecycle, голоса, анимированные маскоты в Compose (Home/Result, конфетти при рекорде), настройки «Музыка»/«Голос», проверка на Pixel 9.
- [ ] Ревью + versionCode 13 → internal.

## Журнал

- 2026-09-20 — Роадмап создан, решения согласованы. Окружение: JDK 21, SDK 36, AVD есть, ключ Play API и keystore на месте.
- 2026-09-20 — Фазы 0–1 готовы; ядро + репозиторий + миграция готовы (44 unit-теста зелёные). minSdk поднят 23→24 (требование play-services-ads 25.x). Иконка готова (splash — в Фазе 3). Запущены параллельно: GameView (Opus), PlayGamesManager (Opus), Ads/UMP (Sonnet); UI-оболочка стартует после них, чтобы интегрировать реальные API.
- 2026-09-20 — GameView, PlayGamesManager (+AchievementRules), Ads/UMP готовы, 75 unit-тестов. Play API: в production лежит versionCode 10 «3.1» → наш код 11. Play App Signing включён, upload-key SHA-1 совпадает с локальным keystore. Pro-версия удалена Google — ссылку убираем. Добавлен tool/play_status.mjs (read-only статус треков). Идёт интеграция UI (Opus) + проверка на эмуляторе; затем локализация ru/de (Sonnet), ревью (Opus), загрузка в internal.
- 2026-09-20 — UI-оболочка (Compose/M3) интегрирована, legacy Java удалён, миграция данных проверена на эмуляторе, R8-релиз запускается (добавлены keep-правила для WorkManager/Room). Локализации ru/de, release notes, черновики листинга (store/listing). Privacy URL взят из живого листинга. Ревью (Opus): 18 находок → tool/play_upload.mjs обезврежен (--track обязателен, статус по умолчанию draft); остальные исправляет Opus-агент (стрендинг цели при ресайзе, защита от случайных кликов по баннеру, backup legacy-prefs, запись результата в момент финиша, один AdView на activity и др.).
- 2026-09-21 — Все находки ревью исправлены и проверены на эмуляторе (81 unit-тест, lint 0 ошибок). **versionCode 11 (4.0) загружен в internal testing** (AAB 6.8 МБ). Осталось (ждёт пользователя): 1) настройка Play Games в консоли → реальные `games-ids.xml` → сборка 12; 2) проверка баннера/UMP на реальном устройстве (эмулятор не достучался до UMP); 3) «да» на production.
- 2026-09-21 — Тест на реальном Pixel 9 (Android 17): обновление поверх установленной 3.1 прошло, данные (9 серий, топ 317 мс) мигрировали; баннер и single-AdView работают; падений нет. Найдено и исправлено: нечитаемый текст в тёмной теме (BannerScaffold не задавал LocalContentColor), подписи «Серий»/«Лучшая реакция», спарклайн приведён к ориентации графика статистики. **versionCode 12 загружен в internal.** Новое для пользователя: в AdMob не настроена форма согласия UMP (Privacy & messaging) — диалог GDPR не показывается, пока её нет.
- 2026-09-21 — По замечанию пользователя: «лучшая реакция» медленнее лучшего среднего больше не показывается (инвариант в ScoreBoard), добавлено «Удалить последнюю серию». Начата Фаза 7 (оформление).
