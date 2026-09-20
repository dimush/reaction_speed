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
- [~] Compose/M3: Home, Result, Stats (график), Settings; edge-to-edge; predictive back; тема.
- [ ] Локализации en / ru / de; все строки из ресурсов.
- [x] Адаптивная иконка + monochrome; splash (core-splashscreen).

### Фаза 4 — Play Games Services (Opus — код; пользователь/Chrome — консоль)
- [x] `PlayGamesManager` (PGS v2 SDK), отправка очков/достижений, кнопки «Лидерборды» / «Достижения».
- [x] `games-ids.xml` с плейсхолдерами; graceful-off без id.
- [!] Настройка в Play Console: проект Play Games, OAuth-клиенты (SHA-1 upload key + app signing key), 2 доски, достижения, публикация PGS-проекта. → `docs/PLAY_GAMES_SETUP.md`
- [ ] Вписать реальные id, проверить на устройстве/эмуляторе с тестовым аккаунтом.

### Фаза 5 — Реклама и приватность (Sonnet)
- [x] play-services-ads (актуальная) + UMP consent flow, adaptive anchored banner, пункт «Privacy options» в Settings.
- [ ] Убрать ссылку на Pro. Проверить манифест (AD_ID, INTERNET), политику конфиденциальности, Data safety заметки → `docs/STORE_CHECKLIST.md`.

### Фаза 6 — QA и релиз
- [ ] Unit-тесты + lint зелёные; code review (Opus) всего диффа; security review.
- [ ] Smoke на эмуляторе `Medium_Phone_API_36.0`: запуск, серия, миграция данных поверх старой версии 3.0, скриншоты.
- [ ] Release AAB, подпись, `node tool/play_upload.mjs --track internal`.
- [ ] Release notes en/ru/de; обновить README.
- [!] Подтверждение пользователя → promote в production.

## Журнал

- 2026-09-20 — Роадмап создан, решения согласованы. Окружение: JDK 21, SDK 36, AVD есть, ключ Play API и keystore на месте.
- 2026-09-20 — Фазы 0–1 готовы; ядро + репозиторий + миграция готовы (44 unit-теста зелёные). minSdk поднят 23→24 (требование play-services-ads 25.x). Иконка готова (splash — в Фазе 3). Запущены параллельно: GameView (Opus), PlayGamesManager (Opus), Ads/UMP (Sonnet); UI-оболочка стартует после них, чтобы интегрировать реальные API.
- 2026-09-20 — GameView, PlayGamesManager (+AchievementRules), Ads/UMP готовы, 75 unit-тестов. Play API: в production лежит versionCode 10 «3.1» → наш код 11. Play App Signing включён, upload-key SHA-1 совпадает с локальным keystore. Pro-версия удалена Google — ссылку убираем. Добавлен tool/play_status.mjs (read-only статус треков). Идёт интеграция UI (Opus) + проверка на эмуляторе; затем локализация ru/de (Sonnet), ревью (Opus), загрузка в internal.
