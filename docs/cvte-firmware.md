# Заметки о прошивке CVTE (TP.MT5510S.PB803, Android 7.0)

Что выяснено при разработке лаунчера — чтобы не искать заново.

## Железо

| | |
|---|---|
| Плата | CVTE TP.MT5510S.PB803, MediaTek MT5510, 4 ядра ARM |
| Память | 8 ГБ флеш, ~795 МБ ОЗУ, zram ~297 МБ |
| Видео | Mali-450 MP (OpenGL ES 2.0), 1920×1080 @ 50 Гц, density 240 |
| Система | Android 7.0, сборка userdebug, патч безопасности 2018-02 |

## Заводское меню и отладка

- Заводское меню: **Настройки → Menu → 1147**.
- Пункты с `>>>` (SHIPPING INIT, AGING MODE, BOARD INIT) — это команды, выполняются сразу. Не нажимать.
- **DEBUG → ADB ENABLE = ON** + перезагрузка из розетки → ADB по сети на порту 5555.

## ТВ-источники

- Библиотека прошивки `cn.cvte.tv.api` лежит в `BOOTCLASSPATH` — её классы доступны любому приложению через рефлексию.
- Сервис: action `com.cvte.tv.api.TV_API_SERVICE`, пакет `com.cvte.tv.api.impl` — пускает любые приложения.
- `ITvApiManager.getTVApiSystemInputSource()`:
  - `eventSystemInputSourceGetList()` — 0 TV (DVB-C), 1 AV, 2 YPbPr, 3 VGA, 4–6 HDMI 1–3;
  - `eventSystemInputSourceSetInputSource(id)` — переключить.
- Полноэкранный ТВ — `com.cvte.tv.androidsetting/.TvSettingActivity`. **Запускать с action `MAIN`**:
  без него экран падает с NPE в `onNewIntent`.
- Свой экран на `TvView` не подходит: когда ТВ-вход активен, Home перехватывает `HotkeyManager` CVTE,
  а регистрировать свои глобальные клавиши разрешено только пакетам из белого списка.

## Таймер сна

`ITvApiManager.getTVApiSystemSleepTime()` → `eventSystemSleepTimeSetSleepTime(минуты)`, `0` — выключен.

## Что отключено на этом ТВ

Facebook, Aptoide, браузер, TalkBack, AccuWeather, Zeasn (реклама фильмов + статистика), Invisible Art,
магазин и «чистильщик» CVTE и др. — через `pm disable-user`, откат `pm enable <пакет>`.

**Не трогать:** `com.example.user.myapplication` (запасной главный экран при загрузке),
`com.cvte.tv.launcher.fall` (заводской лаунчер — запасной), `com.cvte.tvservice`, `com.cvte.tv.api.impl`,
`com.cvte.tv.androidsetting`, `com.mediatek.*`, `com.android.systemui`.
