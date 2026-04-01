# Конфигурация `app_config.json`

Файл конфигурации находится здесь:

`/sdcard/Android/data/net.muratov.intercom/files/app_config.json`

Приложение читает из него:

- URL встроенного браузера на главном экране
- список плиток камер
- список SIP-аккаунтов
- параметры MQTT для публикации состояния звонка
- параметры внешних провайдеров, например `proptech`

## Общая структура

```json
{
  "webViewUrl": "http://192.168.1.10",
  "streams": [],
  "sipAccounts": [],
  "mqtt": {},
  "providers": []
}
```

## Где должен лежать файл

Приложение читает конфиг только из внешнего файла:

`/sdcard/Android/data/net.muratov.intercom/files/app_config.json`

Если файла нет или JSON невалиден:

- приложение не запускает основной экран
- SIP не стартует
- на экране показывается сообщение `Необходима конфигурация для приложения`

## Как применить конфиг

1. Создайте файл `app_config.json`
2. Скопируйте его в `/sdcard/Android/data/net.muratov.intercom/files/`
3. Перезапустите приложение

Пример через `adb`:

```bash
adb push app_config.json /sdcard/Android/data/net.muratov.intercom/files/app_config.json
```

## `webViewUrl`

Адрес страницы, которая открывается в левой части главного экрана.

Примеры:

```json
{
  "webViewUrl": "http://192.168.7.254/#!/dashboards/svg/view/intercom?hmi"
}
```

```json
{
  "webViewUrl": "https://example.local/dashboard"
}
```

## `streams`

`streams` определяет плитки камер справа на главном экране.

Обязательные поля элемента:

- `id`
- `title`
- `provider`

Пример:

```json
{
  "streams": [
    {
      "id": "cam-1",
      "title": "Подъезд",
      "provider": {
        "type": "config",
        "url": "rtsp://admin:G7m2xQ9pL4@192.168.1.20:554/stream1"
      }
    }
  ]
}
```

### Провайдер потока: `type = "config"`

Используется для прямого RTSP-потока из конфига.

Поддерживаемые поля:

- `type`: `"config"`
- `url`: обязательный RTSP URL
- `rtspExtras`: дополнительные заголовки для RTSP
- `previewUrl`: URL JPEG/PNG превью вместо живого видео
- `previewReloadPeriod`: период автообновления превью в миллисекундах
- `previewExtras`: дополнительные заголовки для запроса превью

Пример живого RTSP:

```json
{
  "id": "cam-1",
  "title": "Улица",
  "provider": {
    "type": "config",
    "url": "rtsp://admin:K8v1nR5sT2@192.168.1.21:554/stream1"
  }
}
```

Пример превью-картинки с обновлением:

```json
{
  "id": "cam-2",
  "title": "Лифт",
  "provider": {
    "type": "config",
    "url": "rtsp://admin:M4p7cZ2wH9@192.168.1.22:554/stream1",
    "previewUrl": "http://192.168.1.22/jpg/image.jpg",
    "previewReloadPeriod": 5000
  }
}
```

Пример превью с заголовками:

```json
{
  "id": "cam-3",
  "title": "Склад",
  "provider": {
    "type": "config",
    "url": "rtsp://admin:Q6t8bN3yF1@192.168.1.23:554/stream1",
    "previewUrl": "https://camera.local/snapshot",
    "previewReloadPeriod": 15000,
    "previewExtras": {
      "Authorization": "Bearer YOUR_TOKEN"
    }
  }
}
```

### Провайдер потока: `type = "proptech"`

Используется, когда поток и превью должны быть получены от провайдера `proptech`.

Поддерживаемые поля:

- `type`: `"proptech"`
- `cameraId`: явный ID камеры

Пример:

```json
{
  "id": "cam-4",
  "title": "Домофон",
  "provider": {
    "type": "proptech"
  }
}
```

`accessControlId` получается приложением через API провайдера. Для выбора нужного объекта приложение использует данные API и сопоставляет их по `title`, `id` и `cameraId`.

## `sipAccounts`

`sipAccounts` определяет аккаунты, которые регистрируются в SIP.

Обязательные поля элемента:

- `id`
- `provider`

Пример:

```json
{
  "sipAccounts": [
    {
      "id": "office",
      "provider": {
        "type": "config",
        "title": "Office",
        "displayName": "Office",
        "username": "2000",
        "password": "secret",
        "domain": "192.168.1.30",
        "port": 5060,
        "transport": "UDP",
        "stunServer": "stun.l.google.com:19302",
        "iceEnabled": true
      }
    }
  ]
}
```

### SIP провайдер: `type = "config"`

Используется для статического SIP-аккаунта из конфига.

Поддерживаемые поля:

- `type`: `"config"`
- `title`
- `displayName`
- `username`
- `password`
- `domain`
- `port`
- `transport`: `UDP`, `TCP` или `TLS`
- `stunServer`: STUN-сервер для этого аккаунта
- `iceEnabled`: включает ICE для этого аккаунта

Пример:

```json
{
  "id": "internal",
  "provider": {
    "type": "config",
    "title": "Intercom",
    "displayName": "Intercom",
    "username": "2000",
    "password": "secret",
    "domain": "192.168.7.251",
    "port": 5060,
    "transport": "UDP",
    "stunServer": "stun.l.google.com:19302",
    "iceEnabled": true
  }
}
```

### SIP провайдер: `type = "proptech"`

Используется, когда SIP-учётные данные должны быть получены от `proptech`.

Поддерживаемые поля:

- `type`: `"proptech"`
- `title`
- `displayName`
- `username`
- `password`
- `domain`
- `port`
- `transport`
- `stunServer`
- `iceEnabled`

Пример:

```json
{
  "id": "external",
  "provider": {
    "type": "proptech",
    "title": "Домофон",
    "displayName": "MyHome Intercom",
    "username": "override-user",
    "password": "secret",
    "domain": "sip.example.local",
    "transport": "UDP",
    "stunServer": "stun.l.google.com:19302",
    "iceEnabled": true
  }
}
```

`accessControlId` получается через API провайдера. Для выбора нужной точки доступа приложение использует данные API и сопоставляет их по `title` или `id`.

Если `username`, `password`, `domain`, `stunServer` или `iceEnabled` заданы в конфиге, они переопределяют значения, полученные через API или дефолты приложения. Если поле не задано, остаётся поведение `proptech` по умолчанию.

## `mqtt`

Секция `mqtt` необязательная.

Если она заполнена, приложение подключается к MQTT broker и публикует состояние звонка, номер звонящего и название провайдера SIP-аккаунта.

Обязательные поля:

- `serverUrl`
- `clientId`

Поддерживаемые поля:

- `serverUrl`: адрес broker, например `tcp://192.168.1.10:1883`
- `clientId`: используется как часть MQTT topic
- `username`: опционально
- `password`: опционально
- `topicPrefix`: сейчас не используется для топиков звонка, но может остаться в конфиге без вреда

Если broker работает без авторизации, поля `username` и `password` можно не указывать.

Пример:

```json
{
  "mqtt": {
    "serverUrl": "tcp://192.168.1.10:1883",
    "clientId": "panel-1",
    "username": "mqtt-user",
    "password": "secret"
  }
}
```

### Какие топики публикуются при подключении

Для `clientId = "panel-1"` из секции `mqtt` приложение публикует:

- `/devices/intercom-panel-1`
- `/devices/intercom-panel-1/meta`
- `/devices/intercom-panel-1/meta/driver`
- `/devices/intercom-panel-1/meta/name`
- `/devices/intercom-panel-1/controls/state/meta`
- `/devices/intercom-panel-1/controls/state/meta/order`
- `/devices/intercom-panel-1/controls/state/meta/readonly`
- `/devices/intercom-panel-1/controls/state/meta/type`
- `/devices/intercom-panel-1/controls/provider/meta`
- `/devices/intercom-panel-1/controls/provider/meta/order`
- `/devices/intercom-panel-1/controls/provider/meta/readonly`
- `/devices/intercom-panel-1/controls/provider/meta/type`
- `/devices/intercom-panel-1/controls/number/meta`
- `/devices/intercom-panel-1/controls/number/meta/order`
- `/devices/intercom-panel-1/controls/number/meta/readonly`
- `/devices/intercom-panel-1/controls/number/meta/type`

### Какие топики обновляются во время звонка

- `/devices/intercom-panel-1/controls/state`
- `/devices/intercom-panel-1/controls/provider`
- `/devices/intercom-panel-1/controls/number`

Значения `state`:

- `1` = `Idle`
- `2` = `Ringing`
- `3` = `Connected`

В `provider` публикуется `title` SIP-аккаунта, через который пришёл звонок.

В `number` публикуется номер звонящего, извлечённый из SIP address.

## `providers`

Секция `providers` содержит настройки внешних провайдеров.

Сейчас поддержан провайдер `proptech`.

Пример:

```json
{
  "providers": [
    {
      "type": "proptech",
      "baseUrl": "https://myhome.proptech.ru",
      "phone": "79990000000"
    }
  ]
}
```

Поддерживаемые поля `proptech`:

- `type`: `"proptech"`
- `baseUrl`
- `phone`

Пример:

```json
{
  "providers": [
    {
      "type": "proptech",
      "baseUrl": "https://myhome.proptech.ru",
      "phone": "79990000000"
    }
  ]
}
```

## Полный пример

```json
{
  "webViewUrl": "http://192.168.7.254/#!/dashboards/svg/view/intercom?hmi",
  "streams": [
    {
      "id": "cam-1",
      "title": "Подъезд",
      "provider": {
        "type": "config",
        "url": "rtsp://admin:J5d9wK2pRt6@192.168.15.254:554/stream1"
      }
    },
    {
      "id": "cam-2",
      "title": "Тамбур",
      "provider": {
        "type": "config",
        "url": "rtsp://admin:V3m8qX1nCb7@192.168.7.29:554/stream1",
        "previewUrl": "http://192.168.7.29/jpg/image.jpg",
        "previewReloadPeriod": 5000
      }
    },
    {
      "id": "cam-3",
      "title": "Домофон",
      "provider": {
        "type": "proptech"
      }
    }
  ],
  "sipAccounts": [
    {
      "id": "internal",
      "provider": {
        "type": "config",
        "title": "Intercom",
        "displayName": "Intercom",
        "username": "2000",
        "password": "secret",
        "domain": "192.168.7.251",
        "port": 5060,
        "transport": "UDP",
        "stunServer": "stun.l.google.com:19302",
        "iceEnabled": true
      }
    },
    {
      "id": "external",
      "provider": {
        "type": "proptech",
        "title": "Домофон",
        "transport": "UDP"
      }
    }
  ],
  "mqtt": {
    "serverUrl": "tcp://192.168.1.10:1883",
    "clientId": "panel-1",
    "username": "mqtt-user",
    "password": "secret"
  },
  "providers": [
    {
      "type": "proptech",
      "baseUrl": "https://myhome.proptech.ru",
      "phone": "79990000000"
    }
  ]
}
```

## Правила заполнения

- Если `provider.type = "config"`, данные берутся прямо из этого блока конфига.
- У stream-провайдера `type = "config"` поле `url` обязательно.
- `previewUrl` можно не указывать.
- Если `provider.type = "proptech"`, данные для плиток и SIP получаются от провайдера после авторизации.
- Если у плитки есть `previewUrl`, в плитке показывается картинка, а в fullscreen всё равно запускается `RTSP`.
- Если `previewUrl` нет, `RTSP` запускается сразу в плитке.
- `previewReloadPeriod` задаётся в миллисекундах.
- `transport` для SIP пишется строкой: `UDP`, `TCP` или `TLS`.
- `stunServer` для SIP-аккаунта указывается строкой, например `stun.l.google.com:19302`.
- `iceEnabled` для SIP-аккаунта указывается как `true` или `false`.
- Для `proptech` `username/password/domain` приходят из API, а `stunServer/iceEnabled` имеют дефолты в приложении, но все эти значения можно переопределить прямо в `sipAccounts[].provider`.
- Для MQTT секции `mqtt.clientId` обязателен, без него MQTT не стартует.
- Если в конфиге нет ни одного потребителя `proptech`, авторизация `proptech` не запускается.
- Если файл конфига отсутствует или повреждён, приложение покажет экран с просьбой добавить корректный `app_config.json`.
