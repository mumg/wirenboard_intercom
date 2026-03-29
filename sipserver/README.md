# SIP Server

SIP B2BUA/registrar на Go с использованием `sipgo`.

## Обычная сборка

Сборка бинарника в `./bin/sipserver`:

```bash
make build
```

Запуск:

```bash
make run
```

## Сборка под ARM

### Linux ARM64

Для 64-бит ARM (`aarch64`, `arm64`):

```bash
mkdir -p ./bin
env GOCACHE=/tmp/gocache GOPATH=/tmp/gopath GOOS=linux GOARCH=arm64 \
  go build -o ./bin/sipserver-linux-arm64 ./cmd/sipserver
```

### Linux ARMv7

Для 32-бит ARM (`armv7`):

```bash
mkdir -p ./bin
env GOCACHE=/tmp/gocache GOPATH=/tmp/gopath GOOS=linux GOARCH=arm GOARM=7 \
  go build -o ./bin/sipserver-linux-armv7 ./cmd/sipserver
```

### Сборка на macOS ARM

Если нужно собрать бинарник именно для текущей машины Apple Silicon:

```bash
mkdir -p ./bin
env GOCACHE=/tmp/gocache GOPATH=/tmp/gopath GOOS=darwin GOARCH=arm64 \
  go build -o ./bin/sipserver-darwin-arm64 ./cmd/sipserver
```

## Проверка после сборки

Проверка проекта:

```bash
env GOCACHE=/tmp/gocache GOPATH=/tmp/gopath go build ./...
env GOCACHE=/tmp/gocache GOPATH=/tmp/gopath go vet ./...
```

## Запуск как service в Linux

В репозитории есть `systemd` unit и скрипты установки:

- `./deploy/systemd/sipserver.service`
- `./scripts/install-systemd-service.sh`
- `./scripts/restart-systemd-service.sh`
- `./scripts/uninstall-systemd-service.sh`

Типовой сценарий:

```bash
make build
sudo ./scripts/install-systemd-service.sh
```

После установки сервис будет запущен как пользователь `sipserver`.

Полезные команды:

```bash
sudo systemctl status sipserver
sudo journalctl -u sipserver -f
sudo ./scripts/restart-systemd-service.sh
```

Что делает install-скрипт:

- копирует бинарник в `/opt/sipserver/bin/sipserver`;
- копирует `./config.example.json` в `/etc/sipserver/sipserver.conf`;
- создаёт пользователя и группу `sipserver`, если их ещё нет;
- создаёт каталоги `/var/lib/sipserver` и `/var/log/sipserver`;
- устанавливает unit в `/etc/systemd/system/sipserver.service`;
- включает и запускает сервис через `systemctl enable --now`.

Дополнительные опции запуска можно задать в:

```bash
/etc/default/sipserver
```

Файл-шаблон лежит в `./deploy/systemd/sipserver.env.example`.

## Конфиг

Пример конфига лежит в `./config.example.json`.
Для systemd-сервиса конфиг ставится в `/etc/sipserver/sipserver.conf`.
Для локального запуска по умолчанию используется `./config.json`.
