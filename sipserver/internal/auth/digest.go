package auth

import (
	"crypto/md5"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"strings"
	"sync"
	"time"
)

type Manager struct {
	realm string
	ttl   time.Duration

	mu     sync.Mutex
	nonces map[string]time.Time
}

func NewManager(realm string, ttl time.Duration) *Manager {
	return &Manager{
		realm:  realm,
		ttl:    ttl,
		nonces: make(map[string]time.Time),
	}
}

func (m *Manager) Realm() string {
	return m.realm
}

func (m *Manager) NewChallenge() string {
	buf := make([]byte, 16)
	_, _ = rand.Read(buf)
	nonce := hex.EncodeToString(buf)

	m.mu.Lock()
	m.nonces[nonce] = time.Now().Add(m.ttl)
	m.cleanupLocked(time.Now())
	m.mu.Unlock()

	return fmt.Sprintf(`Digest realm="%s", nonce="%s", algorithm=MD5, qop="auth"`, m.realm, nonce)
}

func (m *Manager) Validate(header, method, requestURI, password string) (string, bool) {
	if !strings.HasPrefix(strings.ToLower(strings.TrimSpace(header)), "digest ") {
		return "", false
	}
	params := parseAuthParams(strings.TrimSpace(header[len("Digest "):]))
	username := params["username"]
	realm := params["realm"]
	nonce := params["nonce"]
	uri := params["uri"]
	response := params["response"]
	nc := params["nc"]
	cnonce := params["cnonce"]
	qop := params["qop"]

	if username == "" || realm != m.realm || nonce == "" || uri == "" || response == "" {
		return "", false
	}
	if requestURI != "" && uri != requestURI {
		return "", false
	}

	m.mu.Lock()
	expiry, exists := m.nonces[nonce]
	validNonce := exists && time.Now().Before(expiry)
	m.cleanupLocked(time.Now())
	m.mu.Unlock()
	if !validNonce {
		return "", false
	}

	ha1 := md5Hex(fmt.Sprintf("%s:%s:%s", username, realm, password))
	ha2 := md5Hex(fmt.Sprintf("%s:%s", method, uri))

	var expected string
	if qop != "" {
		expected = md5Hex(fmt.Sprintf("%s:%s:%s:%s:%s:%s", ha1, nonce, nc, cnonce, qop, ha2))
	} else {
		expected = md5Hex(fmt.Sprintf("%s:%s:%s", ha1, nonce, ha2))
	}
	return username, strings.EqualFold(expected, response)
}

func (m *Manager) cleanupLocked(now time.Time) {
	for nonce, expiry := range m.nonces {
		if now.After(expiry) {
			delete(m.nonces, nonce)
		}
	}
}

func parseAuthParams(raw string) map[string]string {
	params := make(map[string]string)
	for _, token := range strings.Split(raw, ",") {
		key, value, ok := strings.Cut(strings.TrimSpace(token), "=")
		if !ok {
			continue
		}
		params[strings.ToLower(strings.TrimSpace(key))] = strings.Trim(strings.TrimSpace(value), `"`)
	}
	return params
}

func md5Hex(value string) string {
	sum := md5.Sum([]byte(value))
	return hex.EncodeToString(sum[:])
}
