# Контекст проекта

## Назначение

`intercom` — Android-приложение домашнего интеркома. Оно показывает WebView и
камеры, регистрирует SIP-аккаунты, публикует состояние звонка через MQTT и
может получать камеры/SIP-параметры из MyHome Proptech.

В каталоге `sipserver/` находится отдельный Go SIP B2BUA/registrar. Его
подробный контекст сохранён в `sipserver/CONTEXT.md`.

## Основные файлы

- `app/src/main/java/net/muratov/intercom/MainApplication.kt` — сборка
  зависимостей, инициализация конфигурации и запуск runtime-сервисов.
- `app/src/main/java/net/muratov/intercom/MainActivity.kt` — Compose UI,
  мастер регистрации и полноэкранный просмотр камер.
- `app/src/main/java/net/muratov/intercom/data/provider/` — источники камер и
  SIP-аккаунтов.
- `app/src/main/java/net/muratov/intercom/provider/myhome/` — авторизация и API
  MyHome Proptech.
- `app/src/main/java/net/muratov/intercom/video/RtspPlayer.kt` — проигрывание
  RTSP/HTTP-потоков.
- `app_config_bedroom.json` и `app_config_entrance.json` — конфигурации двух
  развёртываемых устройств.
- `deploy.sh` — release-сборка, проверка подписи и установка через ADB.

## Текущая работа в Android-приложении

На момент записи в рабочем дереве есть незакоммиченная серия связанных
изменений:

- для Proptech добавлена авторизация по `accountId` + `password` без SMS через
  `POST /auth/v2/auth/{login}/password`; запрос подписывается `hash1`/`hash2`,
  после выдачи токена `placeId` определяется через `/rest/v3/subscriber-places`;
  прежняя авторизация по `phone` остаётся доступной;
- данные выбранного места Proptech предварительно загружаются в общий
  `ProptechPlaceCatalog`, после чего из одного снимка обновляются камеры и
  SIP-аккаунты;
- инициализация приложения теперь явно отделена от запуска SIP/MQTT, а UI
  показывает загрузку до готовности конфигурации;
- получение свежего короткоживущего URL камеры и создание fullscreen-плеера
  выполняются без повторного проигрывания старого URL;
- у ExoPlayer уменьшена задержка live-потока и отключено накопление буфера;
- release-вариант подписывается keystore из `/Users/maxim/android.jks`, а
  пароли и alias поступают через переменные `INTERCOM_KEYSTORE_PASSWORD`,
  `INTERCOM_KEY_ALIAS` и `INTERCOM_KEY_PASSWORD`.

Описание конфигурации Proptech обновляется в `README.md`. `accountId` и
`password` должны задаваться вместе; если они заданы, `phone` не обязателен.

Формат входа по договору подтверждён по трафику официального клиента 9.10.0.
`GET /auth/v2/login/{accountId}` может штатно вернуть `200` с пустым телом и
не должен использоваться для выбора контекста password-авторизации. После
входа заголовок `Operator` передаётся в авторизованных запросах, кроме первого
получения списка мест. Проверка на `entrance` с чистыми данными прошла:
password-login, загрузка места, access control и регистрация SIP вернули 200.

## Деплой Android-приложения

Без аргументов сборка устанавливается на оба известных устройства:

```bash
./deploy.sh
```

Для одного устройства используется опциональный ключ:

```bash
./deploy.sh --device bedroom
./deploy.sh --device entrance
```

Короткая форма — `-d`. Также поддерживается `--device=bedroom`. Допустимые
идентификаторы строго ограничены значениями `bedroom` и `entrance`; неверный
аргумент завершается до сборки и запроса паролей. Справка: `./deploy.sh --help`.

Скрипт собирает `:app:assembleRelease`, проверяет APK через `apksigner`,
копирует соответствующий JSON-конфиг, устанавливает APK и запускает
`net.muratov.intercom/.MainActivity`.

## Проверки и сборка

Основные команды:

```bash
./gradlew test
./gradlew :app:assembleRelease
zsh -n deploy.sh
```

Для release-сборки нужны значения переменных подписи; `deploy.sh` запрашивает
отсутствующие значения интерактивно.

## Состояние рабочего дерева и осторожность

Рабочее дерево уже содержит пользовательские незакоммиченные изменения. Не
откатывать и не перезаписывать их. Помимо исходников в нём есть локальные и
потенциально чувствительные файлы/артефакты, включая `app_config*.json`,
`invite.txt`, `HTTPToolkit_*.har`, `sipserver/bin/` и `sipserver/dist/`. HAR
может содержать действующие Bearer/SIP-токены и персональные данные. Не
публиковать содержимое этих файлов и не удалять их без отдельного запроса.
