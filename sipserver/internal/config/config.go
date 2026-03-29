package config

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
)

type Config struct {
	Realm                string            `json:"realm"`
	DistributedExtension string            `json:"distributed_extension"`
	RegistrationStore    string            `json:"registration_store"`
	NonceTTLSeconds      int               `json:"nonce_ttl_seconds"`
	Interfaces           []InterfaceConfig `json:"interfaces"`
	Users                []UserConfig      `json:"users"`
	Media                MediaConfig       `json:"media"`
}

type InterfaceConfig struct {
	Name        string `json:"name"`
	SIPListen   string `json:"sip_listen"`
	AdvertiseIP string `json:"advertise_ip"`
	MediaIP     string `json:"media_ip"`
	Subnet      string `json:"subnet"`
}

type UserConfig struct {
	Username    string `json:"username"`
	Password    string `json:"password"`
	DisplayName string `json:"display_name"`
	Distributed bool   `json:"distributed"`
}

type MediaConfig struct {
	RTPPortStart int `json:"rtp_port_start"`
	RTPPortEnd   int `json:"rtp_port_end"`
	ReadBuffer   int `json:"read_buffer"`
}

func Load(path string) (*Config, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("read config: %w", err)
	}

	var cfg Config
	if err := json.Unmarshal(data, &cfg); err != nil {
		return nil, fmt.Errorf("decode config: %w", err)
	}

	if cfg.Realm == "" {
		return nil, fmt.Errorf("realm is required")
	}
	if cfg.DistributedExtension == "" {
		cfg.DistributedExtension = "hunt"
	}
	if cfg.RegistrationStore == "" {
		cfg.RegistrationStore = filepath.Join(".", "data", "registrations.json")
	}
	if cfg.NonceTTLSeconds <= 0 {
		cfg.NonceTTLSeconds = 300
	}
	if cfg.Media.RTPPortStart == 0 {
		cfg.Media.RTPPortStart = 20000
	}
	if cfg.Media.RTPPortEnd == 0 {
		cfg.Media.RTPPortEnd = 20100
	}
	if cfg.Media.ReadBuffer <= 0 {
		cfg.Media.ReadBuffer = 2048
	}
	if len(cfg.Interfaces) == 0 {
		return nil, fmt.Errorf("at least one interface is required")
	}
	if len(cfg.Users) == 0 {
		return nil, fmt.Errorf("at least one user is required")
	}

	seenInterfaces := make(map[string]struct{}, len(cfg.Interfaces))
	for i, iface := range cfg.Interfaces {
		if iface.Name == "" {
			return nil, fmt.Errorf("interfaces[%d].name is required", i)
		}
		if iface.SIPListen == "" {
			return nil, fmt.Errorf("interfaces[%d].sip_listen is required", i)
		}
		if iface.AdvertiseIP == "" {
			return nil, fmt.Errorf("interfaces[%d].advertise_ip is required", i)
		}
		if iface.MediaIP == "" {
			cfg.Interfaces[i].MediaIP = iface.AdvertiseIP
		}
		if _, exists := seenInterfaces[iface.Name]; exists {
			return nil, fmt.Errorf("duplicate interface name %q", iface.Name)
		}
		seenInterfaces[iface.Name] = struct{}{}
	}

	seenUsers := make(map[string]struct{}, len(cfg.Users))
	for _, user := range cfg.Users {
		if user.Username == "" || user.Password == "" {
			return nil, fmt.Errorf("each user must have username and password")
		}
		if _, exists := seenUsers[user.Username]; exists {
			return nil, fmt.Errorf("duplicate user %q", user.Username)
		}
		seenUsers[user.Username] = struct{}{}
	}

	return &cfg, nil
}
