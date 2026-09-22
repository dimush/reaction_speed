# Play Games Services — настройка в Play Console

> **Сделано 2026-09-22.** Проект 537830905161; ключ App signing совпадает с upload key, поэтому OAuth-клиент один.
> Экспорт «Get resources» называет ресурсы по display name (`achievement_quick`, `leaderboard_best_average_10_taps`, `app_id`) —
> **не вставлять целиком**, а переносить id в имена из `games-ids.xml`.

Разовая ручная настройка (≈15 минут). После неё нужно передать в проект **App ID** и **ID досок/достижений**
(Play Console умеет экспортировать готовый `games-ids.xml` — кнопка «Get resources»).

## 1. Создать проект Play Games

Play Console → приложение **Reaction Speed** → *Grow users → Play Games Services → Setup and management → Configuration*
→ «No, my game doesn't use Google APIs» → создать новый облачный проект (имя: `Reaction Speed`).

## 2. OAuth consent + credentials

В *Configuration → Credentials → Add credential → Android*:

1. Настроить OAuth consent screen в Google Cloud (External, название приложения, e-mail поддержки) и опубликовать его (In production).
2. Создать OAuth client типа **Android** для пакета `org.softosaurus.reactionspeed`. Нужны **два** клиента (по одному credential на каждый):
   - **Upload key** (им подписываются локальные сборки и AAB):
     SHA-1 `5D:CA:6E:6F:A8:73:E4:F6:4F:51:9B:F4:07:20:3A:4B:E0:B8:64:71`
   - **App signing key** (если включён Play App Signing — им Google переподписывает сборки для пользователей):
     SHA-1 взять из *Test and release → Setup → App signing → App signing key certificate*.
     Если Play App Signing не включён, второй клиент не нужен — upload key и есть ключ приложения.

## 3. Лидерборды

*Play Games Services → Leaderboards → Add leaderboard*

| Ресурс (имя в `games-ids.xml`) | Название (en / ru / de) | Формат | Порядок | Лимиты |
|---|---|---|---|---|
| `leaderboard_best_average` | Best average (10 taps) / Лучшее среднее (10 нажатий) / Bester Durchschnitt (10 Treffer) | **Time**, ms... см. примечание | **Smaller is better** | min 100 |
| `leaderboard_best_single` | Fastest single tap / Самая быстрая реакция / Schnellste Einzelreaktion | то же | **Smaller is better** | min 100 |

Примечание по формату: выбрать **Numeric**, 0 знаков после запятой, custom unit `ms` — тогда в доске видно «231 ms».
(Формат Time показывает `0:00.23`, что менее наглядно.) Приложение отправляет целые миллисекунды.
«Enable tamper protection» — включить.

## 4. Достижения

*Play Games Services → Achievements*. Для каждого нужна иконка 512×512 (лежат в `store/achievements/`).

| Ресурс | Название | Тип | Очки |
|---|---|---|---|
| `achievement_first_series` | First Series / Первая серия | standard | 5 |
| `achievement_under_350` | Quick (< 350 ms) | standard | 10 |
| `achievement_under_300` | Fast (< 300 ms) | standard | 20 |
| `achievement_under_250` | Lightning (< 250 ms) | standard | 40 |
| `achievement_under_220` | Superhuman (< 220 ms) | standard | 80 |
| `achievement_series_10` | Regular — 10 series | incremental, 10 шагов | 10 |
| `achievement_series_50` | Devoted — 50 series | incremental, 50 шагов | 25 |
| `achievement_series_200` | Veteran — 200 series | incremental, 200 шагов | 50 |
| `achievement_steady_hand` | Steady Hand — std-dev < 25 ms | standard | 30 |
| `achievement_flawless` | Flawless — all 10 taps counted | standard | 15 |

## 5. Тестировщики и публикация

- *Play Games Services → Testers* — добавить свой Google-аккаунт (до публикации PGS работает только у тестеров).
- *Get resources* → скопировать XML → заменить `app/src/main/res/values/games-ids.xml`.
- После проверки на internal testing: *Play Games Services → Publishing → Publish* (PGS-проект публикуется отдельно от APK).

## 6. Data safety

PGS v2 собирает идентификатор игрока Play Games; обновить анкету *App content → Data safety* (см. `docs/STORE_CHECKLIST.md`).
