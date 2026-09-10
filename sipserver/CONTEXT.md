# Контекст Проекта

## Назначение

`sipserver` — Go SIP B2BUA/registrar на базе `github.com/emiago/sipgo`.
Сервер принимает SIP по нескольким локальным интерфейсам, хранит локальные
регистрации, делает fork вызова на зарегистрированные устройства и проксирует
RTP для `audio` и `video`.

## Основные Компоненты

- `cmd/sipserver/main.go` — запуск, чтение `-config` и обработка сигналов.
- `internal/config/config.go` — JSON-конфигурация и её валидация.
- `internal/server/server.go` — SIP handlers, call/fork lifecycle, SDP rewrite.
- `internal/server/external.go` — внешние UAC-аккаунты и фоновые REGISTER.
- `internal/registrar/store.go` — JSON-хранилище локальных регистраций.
- `internal/media/proxy.go` — RTP relay, отдельные сокеты для audio/video.
- `internal/logging/` — `slog` и стандартный `log` одновременно в stdout и
  syslog при его доступности.

## Конфигурация

Шаблон: `config.example.json`.

- `interfaces` содержит `name`, `sip_listen`, `advertise_ip`, `media_ip`,
  `subnet`.
- `users` — общая база локальных пользователей; `distributed: true` включает
  пользователя в распределённый вызов на `distributed_extension`.
- `registration_store` для service/package должен быть
  `/var/lib/sipserver/registrations.json`.
- `media.rtp_port_start` и `media.rtp_port_end` — диапазон RTP-портов.

### Внешние SIP UAC-аккаунты

`external_accounts` регистрирует сервер как UAC на стороннем SIP-сервере.

Ключевые поля аккаунта:

- `name` — идентификатор для логов.
- `interface` — локальный интерфейс, через который выполняется REGISTER.
- `server` — внешний SIP-сервер в виде `host:port`.
- `domain` — SIP-домен AOR; при отсутствии берётся host из `server`.
- `username`, `auth_username`, `password` — учётные данные; `auth_username`
  по умолчанию равен `username`.
- `contact_user` — user часть Contact, на которую внешний сервер направляет
  входящий INVITE.
- `caller`, `callee` — локальные номера, по которым стартует внутренний
  обзвон при входящем вызове на внешний аккаунт.
- `expires` — время регистрации в секундах, по умолчанию 300.

Сейчас для `external_accounts` поддерживается только UDP. Внешняя регистрация
обновляется за 30 секунд до истечения, либо через половину времени при
коротком expiry. Поддержаны challenge `401` и `407` с digest auth.

Для входящего INVITE на `contact_user` из `external_accounts`:

1. Сервер узнаёт аккаунт по интерфейсу и Request-URI/To user.
2. Внутренний fork запускается с `caller` и `callee` из конфигурации.
3. Внешний From/To сохраняются для диалога с внешним сервером; в исходящих
   INVITE внутренних веток From формируется от настроенного локального caller.

## Регистрация И Маршрутизация

- Локальный REGISTER проверяется digest-авторизацией против `users`.
- В хранилище запоминаются `Contact`, интерфейс, source address, User-Agent и
  срок действия регистрации.
- Вызов на конкретный user направляется на все его активные регистрации.
- Вызов на `distributed_extension` направляется всем зарегистрированным
  пользователям с `distributed: true`, кроме caller.

## Fork И Media

- На каждую ветку создаётся отдельная пара/набор RTP-сокетов для объявленных
  потоков `audio` и `video`.
- SDP offer и answer переписываются на адреса media relay.
- `183 Session Progress` с SDP передаётся caller, поэтому возможен early media
  от нескольких вызываемых веток.
- Первый `2xx` выбирает winning branch; остальным незавершённым веткам
  отправляется CANCEL.
- `486 Busy Here`, `600 Busy Everywhere` и `603 Decline` от любой ветки
  немедленно завершают весь fork: caller получает этот финальный ответ, а
  остальные ветки отменяются. Поздний `2xx` после финального отказа гасится
  через ACK + BYE.

## Размер SIP Сообщений И Транспорт

`sipgo v1.3.0` имеет `UDPMTUSize = 1500` и отказывает в отправке UDP SIP
сообщения длиннее `UDPMTUSize - 200`, то есть примерно 1300 байт.

Исходный INVITE от caller может помещаться в UDP, но branch INVITE часто
больше из-за device-specific Contact, новых SIP-заголовков и SDP после
переписывания. Для branch INVITE:

- поддерживается transport из SIP Contact;
- если собранный запрос по UDP превышает 1300 байт, он автоматически
  переключается на TCP;
- в лог пишутся `transport` и `invite_size`;
- включены compact SIP headers (`v`, `f`, `t`, `i`, `m`, `c`, `l` и другие,
  поддерживаемые sipgo) для INVITE/CANCEL/ACK/BYE fork-веток.

Автоматический TCP fallback требует, чтобы вызываемое устройство принимало
SIP по TCP. Если устройство работает только по UDP, требуется уменьшать SDP
или отказаться от части media-параметров.

## Логи

- Детальные бизнес-логи и SIP debug включены в `internal/server/server.go`.
- Логи идут в stdout и дополнительно в syslog при доступном syslog socket.
- Unit также пишет stdout/stderr в `/var/log/sipserver/sipserver.log`.
- Полезные команды:

```bash
sudo journalctl -u sipserver -f
sudo systemctl status sipserver
```

## Сборка И Пакеты

```bash
make build
make vet
make build-amd64
make dpkg-arm64
make dpkg-armhf
make dpkg-amd64
```

`make build` использует только `GOCACHE=/tmp/gocache`. Не следует
принудительно задавать пустой `GOPATH=/tmp/gopath`: это скрывает module cache
и приводит к ошибке импорта `github.com/emiago/sipgo`.

Пакеты устанавливают:

- бинарник: `/opt/sipserver/bin/sipserver`;
- конфиг: `/etc/sipserver/sipserver.conf`;
- registrations: `/var/lib/sipserver/registrations.json`;
- unit: `/lib/systemd/system/sipserver.service`.

Установочные maintainer scripts создают пользователя `sipserver` и включают
systemd service автоматически.

## Текущее Состояние Рабочего Дерева

Незакоммиченные изменения на момент записи этого файла включают:

- `config.example.json` и `internal/config/config.go`: `external_accounts`;
- `internal/server/server.go`: внешний call routing, завершение fork при
  явном отказе, compact headers и TCP fallback при большом UDP INVITE.

Также в рабочем дереве могут присутствовать локальные артефакты `bin/`,
`dist/` и файлы за пределами этой папки. Их не следует считать исходным кодом
проекта или удалять без отдельной проверки.
