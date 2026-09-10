package config

import (
	"encoding/json"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strings"
)

type Config struct {
	Realm                string            `json:"realm"`
	DistributedExtension string            `json:"distributed_extension"`
	RegistrationStore    string            `json:"registration_store"`
	NonceTTLSeconds      int               `json:"nonce_ttl_seconds"`
	Interfaces           []InterfaceConfig `json:"interfaces"`
	Users                []UserConfig      `json:"users"`
	ExternalAccounts     []ExternalAccount `json:"external_accounts"`
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

type ExternalAccount struct {
	Name         string `json:"name"`
	Interface    string `json:"interface"`
	Server       string `json:"server"`
	Domain       string `json:"domain"`
	Transport    string `json:"transport"`
	Username     string `json:"username"`
	AuthUsername string `json:"auth_username"`
	Password     string `json:"password"`
	ContactUser  string `json:"contact_user"`
	Expires      int    `json:"expires"`
	Caller       string `json:"caller"`
	Callee       string `json:"callee"`
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

	seenExternal := make(map[string]struct{}, len(cfg.ExternalAccounts))
	seenExternalContacts := make(map[string]struct{}, len(cfg.ExternalAccounts))
	for i, account := range cfg.ExternalAccounts {
		if account.Name == "" {
			return nil, fmt.Errorf("external_accounts[%d].name is required", i)
		}
		if account.Interface == "" {
			return nil, fmt.Errorf("external_accounts[%d].interface is required", i)
		}
		if _, ok := seenInterfaces[account.Interface]; !ok {
			return nil, fmt.Errorf("external_accounts[%d].interface references unknown interface %q", i, account.Interface)
		}
		if account.Server == "" {
			return nil, fmt.Errorf("external_accounts[%d].server is required", i)
		}
		if account.Username == "" {
			return nil, fmt.Errorf("external_accounts[%d].username is required", i)
		}
		if account.Password == "" {
			return nil, fmt.Errorf("external_accounts[%d].password is required", i)
		}
		if account.Caller == "" {
			return nil, fmt.Errorf("external_accounts[%d].caller is required", i)
		}
		if account.Callee == "" {
			return nil, fmt.Errorf("external_accounts[%d].callee is required", i)
		}
		if _, ok := seenUsers[account.Caller]; !ok {
			return nil, fmt.Errorf("external_accounts[%d].caller references unknown user %q", i, account.Caller)
		}
		if account.Transport == "" {
			cfg.ExternalAccounts[i].Transport = "UDP"
		} else {
			cfg.ExternalAccounts[i].Transport = strings.ToUpper(account.Transport)
		}
		if cfg.ExternalAccounts[i].Transport != "UDP" {
			return nil, fmt.Errorf("external_accounts[%d].transport=%q is not supported, only UDP is currently supported", i, cfg.ExternalAccounts[i].Transport)
		}
		cfg.ExternalAccounts[i].Server = normalizeServerAddr(account.Server)
		if cfg.ExternalAccounts[i].Domain == "" {
			cfg.ExternalAccounts[i].Domain = hostOnly(cfg.ExternalAccounts[i].Server)
		}
		if cfg.ExternalAccounts[i].AuthUsername == "" {
			cfg.ExternalAccounts[i].AuthUsername = account.Username
		}
		if cfg.ExternalAccounts[i].ContactUser == "" {
			cfg.ExternalAccounts[i].ContactUser = account.Username
		}
		if cfg.ExternalAccounts[i].Expires <= 0 {
			cfg.ExternalAccounts[i].Expires = 300
		}
		if _, exists := seenExternal[account.Name]; exists {
			return nil, fmt.Errorf("duplicate external account name %q", account.Name)
		}
		seenExternal[account.Name] = struct{}{}

		contactKey := cfg.ExternalAccounts[i].Interface + "|" + cfg.ExternalAccounts[i].ContactUser
		if _, exists := seenExternalContacts[contactKey]; exists {
			return nil, fmt.Errorf("duplicate external account contact user %q on interface %q", cfg.ExternalAccounts[i].ContactUser, cfg.ExternalAccounts[i].Interface)
		}
		seenExternalContacts[contactKey] = struct{}{}
	}

	return &cfg, nil
}

func normalizeServerAddr(server string) string {
	server = strings.TrimSpace(server)
	if server == "" {
		return server
	}
	if _, _, err := net.SplitHostPort(server); err == nil {
		return server
	}
	return net.JoinHostPort(server, "5060")
}

func hostOnly(server string) string {
	host, _, err := net.SplitHostPort(server)
	if err == nil {
		return host
	}
	return server
}
