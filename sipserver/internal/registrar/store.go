package registrar

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"
)

type Registration struct {
	Username  string    `json:"username"`
	Contact   string    `json:"contact"`
	Interface string    `json:"interface"`
	Received  string    `json:"received"`
	UserAgent string    `json:"user_agent,omitempty"`
	ExpiresAt time.Time `json:"expires_at"`
}

type Store struct {
	path string

	mu    sync.RWMutex
	items map[string][]Registration
}

func New(path string) (*Store, error) {
	s := &Store{
		path:  path,
		items: make(map[string][]Registration),
	}
	if err := s.load(); err != nil {
		return nil, err
	}
	return s, nil
}

func (s *Store) Upsert(reg Registration) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	s.cleanupExpiredLocked(time.Now())
	list := s.items[reg.Username]
	replaced := false
	for i := range list {
		if list[i].Contact == reg.Contact {
			list[i] = reg
			replaced = true
			break
		}
	}
	if !replaced {
		list = append(list, reg)
	}
	s.items[reg.Username] = list
	return s.persistLocked()
}

func (s *Store) Remove(username, contact string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	list := s.items[username]
	filtered := list[:0]
	for _, item := range list {
		if item.Contact != contact {
			filtered = append(filtered, item)
		}
	}
	if len(filtered) == 0 {
		delete(s.items, username)
	} else {
		s.items[username] = filtered
	}
	return s.persistLocked()
}

func (s *Store) GetByUser(username string) []Registration {
	s.mu.Lock()
	defer s.mu.Unlock()

	s.cleanupExpiredLocked(time.Now())
	out := append([]Registration(nil), s.items[username]...)
	return out
}

func (s *Store) ActiveUsers() map[string][]Registration {
	s.mu.Lock()
	defer s.mu.Unlock()

	s.cleanupExpiredLocked(time.Now())
	out := make(map[string][]Registration, len(s.items))
	for username, regs := range s.items {
		out[username] = append([]Registration(nil), regs...)
	}
	return out
}

func (s *Store) load() error {
	data, err := os.ReadFile(s.path)
	if err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}

	var regs []Registration
	if err := json.Unmarshal(data, &regs); err != nil {
		return err
	}
	now := time.Now()
	for _, reg := range regs {
		if reg.ExpiresAt.After(now) {
			s.items[reg.Username] = append(s.items[reg.Username], reg)
		}
	}
	return nil
}

func (s *Store) persistLocked() error {
	flat := make([]Registration, 0)
	for _, regs := range s.items {
		flat = append(flat, regs...)
	}
	sort.Slice(flat, func(i, j int) bool {
		if flat[i].Username == flat[j].Username {
			return flat[i].Contact < flat[j].Contact
		}
		return flat[i].Username < flat[j].Username
	})

	if err := os.MkdirAll(filepath.Dir(s.path), 0o755); err != nil {
		return err
	}
	data, err := json.MarshalIndent(flat, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(s.path, data, 0o644)
}

func (s *Store) cleanupExpiredLocked(now time.Time) {
	changed := false
	for username, regs := range s.items {
		filtered := regs[:0]
		for _, reg := range regs {
			if reg.ExpiresAt.After(now) {
				filtered = append(filtered, reg)
			} else {
				changed = true
			}
		}
		if len(filtered) == 0 {
			delete(s.items, username)
			continue
		}
		s.items[username] = filtered
	}
	if changed {
		_ = s.persistLocked()
	}
}
