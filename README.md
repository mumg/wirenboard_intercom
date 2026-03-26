# Конфигурация `app_config.json`

Файл конфигурации находится здесь:

`/sdcard/Android/data/net.muratov.intercom/files/app_config.json`

Приложение читает из него:

- URL встроенного браузера на главном экране
- список плиток камер
- список SIP-аккаунтов
- параметры внешних провайдеров, например `proptech`

## Общая структура

```json
{
  "webViewUrl": "http://192.168.1.10",
  "streams": [],
  "sipAccounts": [],
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
        "url": "rtsp://admin:password@192.168.1.20:554/stream1"
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
    "url": "rtsp://admin:password@192.168.1.21:554/stream1"
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
    "url": "rtsp://admin:password@192.168.1.22:554/stream1",
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
    "url": "rtsp://admin:password@192.168.1.23:554/stream1",
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
- `url`: обязательный идентификатор или подсказка выбора камеры
- `cameraId`: явный ID камеры

Пример:

```json
{
  "id": "cam-4",
  "title": "Домофон",
  "provider": {
    "type": "proptech",
    "url": "Домофон"
  }
}
```

`accessControlId` получается приложением через API провайдера. Для выбора нужного объекта приложение использует данные API и сопоставляет их по `provider.url`, `title`, `id` и `cameraId`.

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
        "transport": "UDP"
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

Пример:

```json
{
  "id": "internal",
  "provider": {
    "type": "config",
    "title": "Intercom",
    "displayName": "Intercom",
    "username": "2000",
    "password": "password",
    "domain": "192.168.7.251",
    "port": 5060,
    "transport": "UDP"
  }
}
```

### SIP провайдер: `type = "proptech"`

Используется, когда SIP-учётные данные должны быть получены от `proptech`.

Поддерживаемые поля:

- `type`: `"proptech"`
- `title`
- `displayName`
- `port`
- `transport`

Пример:

```json
{
  "id": "external",
  "provider": {
    "type": "proptech",
    "title": "Домофон",
    "displayName": "MyHome Intercom",
    "transport": "UDP"
  }
}
```

`accessControlId` получается через API провайдера. Для выбора нужной точки доступа приложение использует данные API и сопоставляет их по `title` или `id`.

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
- `installationId`

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
        "url": "rtsp://admin:password@192.168.15.254:554/stream1"
      }
    },
    {
      "id": "cam-2",
      "title": "Тамбур",
      "provider": {
        "type": "config",
        "url": "rtsp://admin:password@192.168.7.29:554/stream1",
        "previewUrl": "http://192.168.7.29/jpg/image.jpg",
        "previewReloadPeriod": 5000
      }
    },
    {
      "id": "cam-3",
      "title": "Домофон",
      "provider": {
        "type": "proptech",
        "url": "Домофон"
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
        "password": "password",
        "domain": "192.168.7.251",
        "port": 5060,
        "transport": "UDP"
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
- У любого stream-провайдера поле `url` обязательно.
- `previewUrl` можно не указывать.
- Если `provider.type = "proptech"`, данные для плиток и SIP получаются от провайдера после авторизации.
- Если у плитки есть `previewUrl`, в плитке показывается картинка, а в fullscreen всё равно запускается `RTSP`.
- Если `previewUrl` нет, `RTSP` запускается сразу в плитке.
- `previewReloadPeriod` задаётся в миллисекундах.
- `transport` для SIP пишется строкой: `UDP`, `TCP` или `TLS`.
- Если в конфиге нет ни одного потребителя `proptech`, авторизация `proptech` не запускается.
- Если файл конфига отсутствует или повреждён, приложение покажет экран с просьбой добавить корректный `app_config.json`.
