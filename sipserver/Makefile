APP := sipserver
CMD := ./cmd/sipserver
BIN_DIR := ./bin
BIN := $(BIN_DIR)/$(APP)
BIN_LINUX_AMD64 := $(BIN_DIR)/$(APP)-linux-amd64
GOCACHE ?= /tmp/gocache
GOENV := GOCACHE=$(GOCACHE)
VERSION ?= 0.1.0
TARGET_OS ?= linux
TARGET_ARCH ?= arm64
TARGET_GOARM ?= 7
OUT_DIR ?= ./dist

.PHONY: build build-amd64 run vet clean install uninstall restart dpkg-arm64 dpkg-armhf dpkg-amd64

build:
	mkdir -p $(BIN_DIR)
	env $(GOENV) go build -o $(BIN) $(CMD)

build-amd64:
	mkdir -p $(BIN_DIR)
	env $(GOENV) GOOS=linux GOARCH=amd64 go build -o $(BIN_LINUX_AMD64) $(CMD)

run:
	env $(GOENV) go run $(CMD) -config ./config.json

vet:
	env $(GOENV) go vet ./...

clean:
	rm -rf $(BIN_DIR)

install: build
	sudo ./scripts/install-systemd-service.sh

uninstall:
	sudo ./scripts/uninstall-systemd-service.sh

restart:
	sudo ./scripts/restart-systemd-service.sh

dpkg-arm64:
	chmod +x ./scripts/build-dpkg.sh
	VERSION=$(VERSION) TARGET_OS=$(TARGET_OS) TARGET_ARCH=$(TARGET_ARCH) TARGET_GOARM=$(TARGET_GOARM) OUT_DIR=$(OUT_DIR) ./scripts/build-dpkg.sh

dpkg-armhf:
	chmod +x ./scripts/build-dpkg.sh
	VERSION=$(VERSION) TARGET_OS=linux TARGET_ARCH=arm TARGET_GOARM=7 OUT_DIR=$(OUT_DIR) ./scripts/build-dpkg.sh

dpkg-amd64:
	chmod +x ./scripts/build-dpkg.sh
	VERSION=$(VERSION) TARGET_OS=linux TARGET_ARCH=amd64 OUT_DIR=$(OUT_DIR) ./scripts/build-dpkg.sh
