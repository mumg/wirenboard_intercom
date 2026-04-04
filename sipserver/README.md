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
env GOCACHE=/tmp/gocache GOOS=linux GOARCH=arm64 \
  go build -o ./bin/sipserver-linux-arm64 ./cmd/sipserver
```

### Linux ARMv7

Для 32-бит ARM (`armv7`):

```bash
mkdir -p ./bin
env GOCACHE=/tmp/gocache GOOS=linux GOARCH=arm GOARM=7 \
  go build -o ./bin/sipserver-linux-armv7 ./cmd/sipserver
```

## Сборка под AMD64

### Linux AMD64

Для 64-бит x86:

```bash
make build-amd64
```

Или напрямую:

```bash
mkdir -p ./bin
env GOCACHE=/tmp/gocache GOOS=linux GOARCH=amd64 \
  go build -o ./bin/sipserver-linux-amd64 ./cmd/sipserver
```

### DPKG пакет под ARM

Сборка `.deb` для `linux/arm64`:

```bash
make dpkg-arm64
```

Сборка `.deb` для `linux/armv7` (`armhf`):

```bash
make dpkg-armhf
```

Готовый пакет складывается в `./dist`.

После установки пакета:

```bash
sudo dpkg -i ./dist/sipserver_0.1.0_arm64.deb
```

пакет автоматически:

- ставит бинарник в `/opt/sipserver/bin/sipserver`;
- кладёт конфиг в `/etc/sipserver/sipserver.conf`;
- использует файл регистраций `/var/lib/sipserver/registrations.json`;
- ставит unit в `/lib/systemd/system/sipserver.service`;
- создаёт пользователя `sipserver`;
- включает и запускает сервис через `systemd`.

Готовый `armhf`-пакет появится в `./dist`, например:

```bash
./dist/sipserver_0.1.0_armhf.deb
```

### DPKG пакет под AMD64

Сборка `.deb` для `linux/amd64`:

```bash
make dpkg-amd64
```

Готовый пакет также появится в `./dist`, например:

```bash
./dist/sipserver_0.1.0_amd64.deb
```

### Сборка на macOS ARM

Если нужно собрать бинарник именно для текущей машины Apple Silicon:

```bash
mkdir -p ./bin
env GOCACHE=/tmp/gocache GOOS=darwin GOARCH=arm64 \
  go build -o ./bin/sipserver-darwin-arm64 ./cmd/sipserver
```

## Проверка после сборки

Проверка проекта:

```bash
env GOCACHE=/tmp/gocache go build ./...
env GOCACHE=/tmp/gocache go vet ./...
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
- настраивает хранение регистраций в `/var/lib/sipserver/registrations.json`;
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
Для установленного сервиса файл регистраций по умолчанию хранится в `/var/lib/sipserver/registrations.json`.
Для локального запуска по умолчанию используется `./config.json`.
