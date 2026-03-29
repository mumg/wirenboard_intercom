APP := sipserver
CMD := ./cmd/sipserver
BIN_DIR := ./bin
BIN := $(BIN_DIR)/$(APP)
GOCACHE ?= /tmp/gocache
GOPATH ?= /tmp/gopath
GOENV := GOCACHE=$(GOCACHE) GOPATH=$(GOPATH)

.PHONY: build run vet clean install uninstall restart

build:
	mkdir -p $(BIN_DIR)
	env $(GOENV) go build -o $(BIN) $(CMD)

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
