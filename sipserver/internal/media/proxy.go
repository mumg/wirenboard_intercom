package media

import (
	"fmt"
	"log"
	"net"
	"strconv"
	"strings"
	"sync"
)

const (
	StreamAudio = "audio"
	StreamVideo = "video"
)

type Binding struct {
	Name    string
	MediaIP string
}

type Endpoint struct {
	IP   string
	Port int
}

type EndpointMap map[string]Endpoint

type RemoteMap map[string]*net.UDPAddr

type PortAllocator struct {
	start int
	end   int

	mu   sync.Mutex
	next map[string]int
}

func NewPortAllocator(start, end int) *PortAllocator {
	return &PortAllocator{
		start: start,
		end:   end,
		next:  make(map[string]int),
	}
}

func (p *PortAllocator) Allocate(bind Binding) (*net.UDPConn, int, error) {
	p.mu.Lock()
	start := p.next[bind.Name]
	if start == 0 {
		start = p.start
	}
	p.mu.Unlock()

	for attempt := 0; attempt <= p.end-p.start; attempt++ {
		port := start + attempt
		if port > p.end {
			port = p.start + (port-p.start)%(p.end-p.start+1)
		}
		addr, err := net.ResolveUDPAddr("udp", net.JoinHostPort(bind.MediaIP, strconv.Itoa(port)))
		if err != nil {
			return nil, 0, err
		}
		conn, err := net.ListenUDP("udp", addr)
		if err == nil {
			p.mu.Lock()
			next := port + 2
			if next > p.end {
				next = p.start
			}
			p.next[bind.Name] = next
			p.mu.Unlock()
			return conn, port, nil
		}
	}
	return nil, 0, fmt.Errorf("no free RTP port on %s", bind.Name)
}

type Session struct {
	id string

	allocator *PortAllocator
	streams   []string

	mu       sync.RWMutex
	closed   bool
	caller   *leg
	branches map[string]*leg
}

type leg struct {
	id      string
	binding Binding
	streams map[string]*streamSocket
}

type streamSocket struct {
	kind  string
	conn  *net.UDPConn
	local Endpoint

	mu     sync.RWMutex
	remote *net.UDPAddr
}

func NewSession(id string, allocator *PortAllocator, callerBinding Binding, callerRemote RemoteMap) (*Session, error) {
	streams := streamOrder(callerRemote)
	if len(streams) == 0 {
		return nil, fmt.Errorf("no supported media streams in SDP")
	}

	caller, err := newLeg("caller", allocator, callerBinding, streams)
	if err != nil {
		return nil, err
	}
	caller.setRemotes(callerRemote)

	s := &Session{
		id:        id,
		allocator: allocator,
		streams:   streams,
		caller:    caller,
		branches:  make(map[string]*leg),
	}
	log.Printf("media session created id=%s binding=%s streams=%v caller_remote=%v caller_local=%v", id, callerBinding.Name, streams, callerRemote, s.caller.endpoints())
	s.runLegCaller(caller)
	return s, nil
}

func (s *Session) CallerEndpoints() EndpointMap {
	return s.caller.endpoints()
}

func (s *Session) AddBranch(id string, binding Binding) (EndpointMap, error) {
	branch, err := newLeg(id, s.allocator, binding, s.streams)
	if err != nil {
		return nil, err
	}

	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		branch.close()
		return nil, fmt.Errorf("media session closed")
	}
	s.branches[id] = branch
	s.mu.Unlock()

	log.Printf("media branch created session=%s branch=%s binding=%s local=%v", s.id, id, binding.Name, branch.endpoints())
	s.runLegBranch(branch)
	return branch.endpoints(), nil
}

func (s *Session) UpdateCallerRemotes(remotes RemoteMap) {
	log.Printf("media caller remotes updated session=%s remotes=%v", s.id, remotes)
	s.caller.setRemotes(remotes)
}

func (s *Session) UpdateBranchRemotes(id string, remotes RemoteMap) {
	s.mu.RLock()
	branch := s.branches[id]
	s.mu.RUnlock()
	if branch != nil {
		log.Printf("media branch remotes updated session=%s branch=%s remotes=%v", s.id, id, remotes)
		branch.setRemotes(remotes)
	}
}

func (s *Session) RemoveBranch(id string) {
	s.mu.Lock()
	branch := s.branches[id]
	delete(s.branches, id)
	s.mu.Unlock()
	if branch != nil {
		log.Printf("media branch removed session=%s branch=%s", s.id, id)
		branch.close()
	}
}

func (s *Session) Close() {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return
	}
	s.closed = true
	branches := make([]*leg, 0, len(s.branches))
	for _, branch := range s.branches {
		branches = append(branches, branch)
	}
	s.branches = map[string]*leg{}
	s.mu.Unlock()

	log.Printf("media session closing id=%s branches=%d", s.id, len(branches))
	s.caller.close()
	for _, branch := range branches {
		branch.close()
	}
}

func (s *Session) runLegCaller(caller *leg) {
	for kind, stream := range caller.streams {
		go s.runCallerStream(kind, stream)
	}
}

func (s *Session) runLegBranch(branch *leg) {
	for kind, stream := range branch.streams {
		go s.runBranchStream(kind, stream)
	}
}

func (s *Session) runCallerStream(kind string, caller *streamSocket) {
	buf := make([]byte, 2048)
	for {
		n, addr, err := caller.conn.ReadFromUDP(buf)
		if err != nil {
			return
		}
		caller.setRemote(addr)
		packet := append([]byte(nil), buf[:n]...)
		for _, target := range s.branchTargets(kind) {
			_, _ = caller.conn.WriteToUDP(packet, target)
		}
	}
}

func (s *Session) runBranchStream(kind string, branch *streamSocket) {
	buf := make([]byte, 2048)
	for {
		n, addr, err := branch.conn.ReadFromUDP(buf)
		if err != nil {
			return
		}
		branch.setRemote(addr)
		callerTarget := s.caller.remote(kind)
		if callerTarget == nil {
			continue
		}
		_, _ = branch.conn.WriteToUDP(buf[:n], callerTarget)
	}
}

func (s *Session) branchTargets(kind string) []*net.UDPAddr {
	s.mu.RLock()
	defer s.mu.RUnlock()

	targets := make([]*net.UDPAddr, 0, len(s.branches))
	for _, branch := range s.branches {
		if target := branch.remote(kind); target != nil {
			targets = append(targets, target)
		}
	}
	return targets
}

func newLeg(id string, allocator *PortAllocator, binding Binding, streams []string) (*leg, error) {
	l := &leg{
		id:      id,
		binding: binding,
		streams: make(map[string]*streamSocket, len(streams)),
	}

	for _, kind := range streams {
		conn, port, err := allocator.Allocate(binding)
		if err != nil {
			l.close()
			return nil, err
		}
		l.streams[kind] = &streamSocket{
			kind: kind,
			conn: conn,
			local: Endpoint{
				IP:   binding.MediaIP,
				Port: port,
			},
		}
	}
	return l, nil
}

func (l *leg) endpoints() EndpointMap {
	out := make(EndpointMap, len(l.streams))
	for kind, stream := range l.streams {
		out[kind] = stream.local
	}
	return out
}

func (l *leg) close() {
	for _, stream := range l.streams {
		_ = stream.conn.Close()
	}
}

func (l *leg) setRemotes(remotes RemoteMap) {
	for kind, addr := range remotes {
		if stream := l.streams[kind]; stream != nil {
			stream.setRemote(addr)
		}
	}
}

func (l *leg) remote(kind string) *net.UDPAddr {
	if stream := l.streams[kind]; stream != nil {
		return stream.remoteAddr()
	}
	return nil
}

func (s *streamSocket) setRemote(addr *net.UDPAddr) {
	if addr == nil {
		return
	}
	s.mu.Lock()
	s.remote = addr
	s.mu.Unlock()
}

func (s *streamSocket) remoteAddr() *net.UDPAddr {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if s.remote == nil {
		return nil
	}
	copyAddr := *s.remote
	return &copyAddr
}

func ExtractMediaEndpoints(body []byte) (RemoteMap, error) {
	descs, err := parseMediaDescriptions(body)
	if err != nil {
		return nil, err
	}

	remotes := make(RemoteMap)
	for _, kind := range []string{StreamAudio, StreamVideo} {
		desc, ok := descs[kind]
		if !ok || desc.IP == "" || desc.Port == 0 {
			continue
		}
		addr, err := net.ResolveUDPAddr("udp", net.JoinHostPort(desc.IP, strconv.Itoa(desc.Port)))
		if err != nil {
			return nil, err
		}
		remotes[kind] = addr
	}
	if len(remotes) == 0 {
		return nil, fmt.Errorf("audio/video media endpoints not found")
	}
	return remotes, nil
}

func RewriteMediaEndpoints(body []byte, ip string, endpoints EndpointMap) ([]byte, error) {
	descs, err := parseMediaDescriptions(body)
	if err != nil {
		return nil, err
	}
	if len(endpoints) == 0 {
		return nil, fmt.Errorf("no media endpoints provided")
	}

	lines := strings.Split(strings.ReplaceAll(string(body), "\r\n", "\n"), "\n")
	currentKind := ""
	sessionConnectionSeen := false
	rewroteMedia := false

	for i, line := range lines {
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(trimmed, "m=") {
			currentKind = mediaKindFromLine(trimmed)
			if endpoint, ok := endpoints[currentKind]; ok {
				fields := strings.Fields(trimmed)
				if len(fields) < 4 {
					return nil, fmt.Errorf("invalid %s media line", currentKind)
				}
				fields[1] = strconv.Itoa(endpoint.Port)
				lines[i] = strings.Join(fields, " ")
				rewroteMedia = true
			}
			continue
		}

		if !strings.HasPrefix(trimmed, "c=IN IP4 ") {
			continue
		}

		if currentKind == "" {
			if usesEndpoint(descs, endpoints) {
				lines[i] = "c=IN IP4 " + ip
				sessionConnectionSeen = true
			}
			continue
		}

		if _, ok := endpoints[currentKind]; ok {
			lines[i] = "c=IN IP4 " + ip
			rewroteMedia = true
		}
	}

	if !rewroteMedia {
		return nil, fmt.Errorf("SDP must contain m=audio or m=video lines")
	}

	if !sessionConnectionSeen && !hasAnyMediaLevelConnection(lines, endpoints) {
		return nil, fmt.Errorf("SDP must contain c= line for rewritten media")
	}

	return []byte(strings.Join(lines, "\r\n")), nil
}

type mediaDescription struct {
	Kind string
	IP   string
	Port int
}

func parseMediaDescriptions(body []byte) (map[string]mediaDescription, error) {
	lines := strings.Split(strings.ReplaceAll(string(body), "\r\n", "\n"), "\n")
	sessionIP := ""
	currentKind := ""
	descs := make(map[string]mediaDescription)

	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if trimmed == "" {
			continue
		}

		if strings.HasPrefix(trimmed, "m=") {
			currentKind = mediaKindFromLine(trimmed)
			if currentKind == "" {
				currentKind = "__other__"
				continue
			}
			fields := strings.Fields(strings.TrimPrefix(trimmed, "m="))
			if len(fields) < 2 {
				return nil, fmt.Errorf("invalid media description line %q", trimmed)
			}
			port, err := strconv.Atoi(fields[1])
			if err != nil {
				return nil, fmt.Errorf("invalid media port in %q", trimmed)
			}
			desc := descs[currentKind]
			desc.Kind = currentKind
			desc.Port = port
			if desc.IP == "" {
				desc.IP = sessionIP
			}
			descs[currentKind] = desc
			continue
		}

		if strings.HasPrefix(trimmed, "c=IN IP4 ") {
			ip := strings.TrimSpace(strings.TrimPrefix(trimmed, "c=IN IP4 "))
			if currentKind == "" {
				sessionIP = ip
				for kind, desc := range descs {
					if desc.IP == "" {
						desc.IP = ip
						descs[kind] = desc
					}
				}
				continue
			}
			if currentKind == "__other__" {
				continue
			}
			desc := descs[currentKind]
			desc.IP = ip
			descs[currentKind] = desc
		}
	}

	for kind, desc := range descs {
		if desc.IP == "" {
			desc.IP = sessionIP
			descs[kind] = desc
		}
	}
	return descs, nil
}

func mediaKindFromLine(line string) string {
	switch {
	case strings.HasPrefix(line, "m=audio "):
		return StreamAudio
	case strings.HasPrefix(line, "m=video "):
		return StreamVideo
	default:
		return ""
	}
}

func streamOrder(remotes RemoteMap) []string {
	order := make([]string, 0, 2)
	for _, kind := range []string{StreamAudio, StreamVideo} {
		if remotes[kind] != nil {
			order = append(order, kind)
		}
	}
	return order
}

func usesEndpoint(descs map[string]mediaDescription, endpoints EndpointMap) bool {
	for kind := range endpoints {
		if _, ok := descs[kind]; ok {
			return true
		}
	}
	return false
}

func hasAnyMediaLevelConnection(lines []string, endpoints EndpointMap) bool {
	currentKind := ""
	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(trimmed, "m=") {
			currentKind = mediaKindFromLine(trimmed)
			continue
		}
		if strings.HasPrefix(trimmed, "c=IN IP4 ") {
			if _, ok := endpoints[currentKind]; ok {
				return true
			}
		}
	}
	return false
}
