package server

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"io"
	"log/slog"
	"net"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/emiago/sipgo"
	"github.com/emiago/sipgo/sip"

	"sipserver/internal/auth"
	"sipserver/internal/config"
	"sipserver/internal/logging"
	"sipserver/internal/media"
	"sipserver/internal/registrar"
)

type Server struct {
	cfg *config.Config

	ua       *sipgo.UserAgent
	srv      *sipgo.Server
	logger   *slog.Logger
	logClose io.Closer
	bindings map[string]*binding
	users    map[string]config.UserConfig

	auth      *auth.Manager
	registrar *registrar.Store
	ports     *media.PortAllocator

	mu             sync.RWMutex
	callsByCaller  map[string]*callSession
	branchByCallID map[string]*callBranch
}

type binding struct {
	cfg    config.InterfaceConfig
	client *sipgo.Client
	subnet *net.IPNet
	local  sip.Addr
}

type callSession struct {
	id string

	mu sync.Mutex

	originalInvite *sip.Request
	inviteTx       sip.ServerTransaction
	callerBinding  *binding
	callerCallID   string
	callerContact  sip.Uri
	callerFrom     sip.FromHeader
	callerTo       sip.ToHeader
	callerTag      string
	callerByeSeq   uint32

	target string

	media      *media.Session
	branches   map[string]*callBranch
	winner     *callBranch
	finalCh    chan bool
	finalOnce  sync.Once
	cancelled  bool
	finalSent  bool
	terminated bool
	failStatus int
	failReason string
}

type callBranch struct {
	id string

	call          *callSession
	username      string
	registration  registrar.Registration
	binding       *binding
	tx            sip.ClientTransaction
	invite        *sip.Request
	callID        string
	remoteTo      *sip.ToHeader
	remoteContact sip.Uri
	cseq          uint32
	final         bool
	cancelled     bool
}

func New(cfg *config.Config) (*Server, error) {
	logger, logClose := logging.New("sipserver")
	sip.SetDefaultLogger(logger)
	sip.SIPDebug = true

	storePath := cfg.RegistrationStore
	if !filepath.IsAbs(storePath) {
		storePath = filepath.Clean(storePath)
	}

	regStore, err := registrar.New(storePath)
	if err != nil {
		return nil, fmt.Errorf("init registrar: %w", err)
	}

	ua, err := sipgo.NewUA(
		sipgo.WithUserAgent("sipserver"),
		sipgo.WithUserAgentHostname(cfg.Realm),
		sipgo.WithUserAgentTransportLayerOptions(sip.WithTransportLayerLogger(logger.With("component", "transport"))),
	)
	if err != nil {
		return nil, fmt.Errorf("create sipgo user agent: %w", err)
	}

	srv, err := sipgo.NewServer(ua, sipgo.WithServerLogger(logger.With("component", "server")))
	if err != nil {
		_ = ua.Close()
		return nil, fmt.Errorf("create sipgo server: %w", err)
	}

	s := &Server{
		cfg:            cfg,
		ua:             ua,
		srv:            srv,
		logger:         logger,
		logClose:       logClose,
		bindings:       make(map[string]*binding, len(cfg.Interfaces)),
		users:          make(map[string]config.UserConfig, len(cfg.Users)),
		auth:           auth.NewManager(cfg.Realm, time.Duration(cfg.NonceTTLSeconds)*time.Second),
		registrar:      regStore,
		ports:          media.NewPortAllocator(cfg.Media.RTPPortStart, cfg.Media.RTPPortEnd),
		callsByCaller:  make(map[string]*callSession),
		branchByCallID: make(map[string]*callBranch),
	}

	for _, user := range cfg.Users {
		s.users[user.Username] = user
	}

	for _, iface := range cfg.Interfaces {
		_, subnet, err := net.ParseCIDR(iface.Subnet)
		if err != nil {
			_ = s.ua.Close()
			return nil, fmt.Errorf("parse subnet for %s: %w", iface.Name, err)
		}

		host, port, err := sip.ParseAddr(iface.SIPListen)
		if err != nil {
			_ = s.ua.Close()
			return nil, fmt.Errorf("parse listen addr for %s: %w", iface.Name, err)
		}

		client, err := sipgo.NewClient(
			ua,
			sipgo.WithClientHostname(iface.AdvertiseIP),
			sipgo.WithClientPort(port),
			sipgo.WithClientConnectionAddr(iface.SIPListen),
			sipgo.WithClientLogger(logger.With("component", "client", "interface", iface.Name)),
		)
		if err != nil {
			_ = s.ua.Close()
			return nil, fmt.Errorf("create client for %s: %w", iface.Name, err)
		}

		s.bindings[iface.Name] = &binding{
			cfg:    iface,
			client: client,
			subnet: subnet,
			local: sip.Addr{
				IP:       net.ParseIP(host),
				Port:     port,
				Hostname: host,
			},
		}
	}

	s.logger.Info("detailed SIP logging enabled", "realm", cfg.Realm, "sip_debug", true)
	s.registerHandlers()
	return s, nil
}

func (s *Server) Start(ctx context.Context) error {
	defer func() {
		if s.logClose != nil {
			_ = s.logClose.Close()
		}
	}()

	errCh := make(chan error, len(s.bindings))
	var wg sync.WaitGroup

	for _, bind := range s.bindings {
		wg.Add(1)
		go func(bind *binding) {
			defer wg.Done()
			if err := s.srv.ListenAndServe(ctx, "udp", bind.cfg.SIPListen); err != nil && ctx.Err() == nil {
				errCh <- fmt.Errorf("listen on %s: %w", bind.cfg.SIPListen, err)
			}
		}(bind)
		s.logger.Info("SIP listener started", "interface", bind.cfg.Name, "listen", bind.cfg.SIPListen, "advertise_ip", bind.cfg.AdvertiseIP, "media_ip", bind.cfg.MediaIP, "subnet", bind.cfg.Subnet)
	}

	select {
	case <-ctx.Done():
	case err := <-errCh:
		_ = s.ua.Close()
		wg.Wait()
		return err
	}

	_ = s.ua.Close()
	s.closeCalls()
	wg.Wait()
	return nil
}

func (s *Server) registerHandlers() {
	s.srv.OnRegister(s.onRegister)
	s.srv.OnInvite(s.onInvite)
	s.srv.OnAck(s.onAck)
	s.srv.OnBye(s.onBye)
	s.srv.OnCancel(s.onCancel)
	s.srv.OnOptions(s.onOptions)
	s.srv.OnNoRoute(func(req *sip.Request, tx sip.ServerTransaction) {
		_ = tx.Respond(sip.NewResponseFromRequest(req, sip.StatusMethodNotAllowed, "Method Not Allowed", nil))
	})
}

func (s *Server) onRegister(req *sip.Request, tx sip.ServerTransaction) {
	s.logRequest("register received", req)
	bind := s.bindingForRequest(req)
	if bind == nil {
		s.logger.Warn("register rejected: unknown interface", "source", req.Source(), "call_id", callID(req))
		_ = tx.Respond(sip.NewResponseFromRequest(req, sip.StatusForbidden, "Unknown Interface", nil))
		return
	}

	authHeader := req.GetHeader("Authorization")
	authValue := ""
	if authHeader != nil {
		authValue = authHeader.Value()
	}

	username, user, ok := s.authorize(authValue, "REGISTER", req.Recipient.String())
	if !ok {
		s.logger.Warn("register auth challenge", "interface", bind.cfg.Name, "source", req.Source(), "call_id", callID(req), "aor", req.Recipient.String())
		resp := sip.NewResponseFromRequest(req, sip.StatusUnauthorized, "Unauthorized", nil)
		resp.AppendHeader(sip.NewHeader("WWW-Authenticate", s.auth.NewChallenge()))
		_ = tx.Respond(resp)
		return
	}

	contact := req.Contact()
	if contact == nil {
		s.logger.Warn("register rejected: missing contact", "user", username, "interface", bind.cfg.Name, "call_id", callID(req))
		_ = tx.Respond(sip.NewResponseFromRequest(req, sip.StatusBadRequest, "Missing Contact", nil))
		return
	}

	expires := parseExpires(req)
	if contact.Address.Wildcard || expires == 0 {
		if err := s.registrar.Remove(username, contact.Value()); err != nil {
			s.logger.Error("register remove failed", "user", username, "contact", contact.Value(), "error", err)
			_ = tx.Respond(sip.NewResponseFromRequest(req, sip.StatusInternalServerError, "Registrar Error", nil))
			return
		}
		s.logger.Info("registration removed", "user", username, "interface", bind.cfg.Name, "contact", contact.Value(), "source", req.Source())
		resp := sip.NewResponseFromRequest(req, sip.StatusOK, "OK", nil)
		resp.AppendHeader(sip.NewHeader("Expires", "0"))
		_ = tx.Respond(resp)
		return
	}

	reg := registrar.Registration{
		Username:  username,
		Contact:   contact.Value(),
		Interface: bind.cfg.Name,
		Received:  req.Source(),
		UserAgent: headerValue(req, "User-Agent"),
		ExpiresAt: time.Now().Add(time.Duration(expires) * time.Second),
	}
	if err := s.registrar.Upsert(reg); err != nil {
		s.logger.Error("registration persist failed", "user", username, "interface", bind.cfg.Name, "contact", contact.Value(), "error", err)
		_ = tx.Respond(sip.NewResponseFromRequest(req, sip.StatusInternalServerError, "Registrar Error", nil))
		return
	}
	s.logger.Info("registration stored", "user", username, "distributed", user.Distributed, "interface", bind.cfg.Name, "contact", contact.Value(), "source", req.Source(), "expires_sec", expires)

	resp := sip.NewResponseFromRequest(req, sip.StatusOK, "OK", nil)
	resp.AppendHeader(sip.NewHeader("Expires", strconv.Itoa(expires)))
	resp.AppendHeader(sip.NewHeader("X-User-Distributed", strconv.FormatBool(user.Distributed)))
	_ = tx.Respond(resp)
}

func (s *Server) onInvite(req *sip.Request, tx sip.ServerTransaction) {
	s.logRequest("invite received", req)
	if req.To() != nil && req.To().Params.Has("tag") {
		s.logger.Warn("invite rejected: reinvite unsupported", "call_id", callID(req))
		_ = tx.Respond(sip.NewResponseFromRequest(req, sip.StatusNotImplemented, "Re-INVITE Not Supported", nil))
		return
	}

	bind := s.bindingForRequest(req)
	if bind == nil {
		s.logger.Warn("invite rejected: unknown interface", "source", req.Source(), "call_id", callID(req))
		_ = tx.Respond(sip.NewResponseFromRequest(req, sip.StatusForbidden, "Unknown Interface", nil))
		return
	}

	callerFrom := req.From()
	if callerFrom == nil || callerFrom.Address.User == "" {
		s.logger.Warn("invite rejected: invalid from", "interface", bind.cfg.Name, "call_id", callID(req))
		_ = tx.Respond(sip.NewResponseFromRequest(req, sip.StatusBadRequest, "Invalid From", nil))
		return
	}

	callerUser := callerFrom.Address.User
	if _, ok := s.users[callerUser]; !ok {
		s.logger.Warn("invite rejected: unknown caller", "caller", callerUser, "call_id", callID(req))
		_ = tx.Respond(sip.NewResponseFromRequest(req, sip.StatusForbidden, "Unknown Caller", nil))
		return
	}
	if len(s.registrar.GetByUser(callerUser)) == 0 {
		s.logger.Warn("invite rejected: caller not registered", "caller", callerUser, "call_id", callID(req))
		_ = tx.Respond(sip.NewResponseFromRequest(req, sip.StatusForbidden, "Caller Not Registered", nil))
		return
	}

	targetUser := req.Recipient.User
	if targetUser == "" && req.To() != nil {
		targetUser = req.To().Address.User
	}
	if targetUser == "" {
		s.logger.Warn("invite rejected: unknown target", "caller", callerUser, "call_id", callID(req))
		_ = tx.Respond(sip.NewResponseFromRequest(req, sip.StatusNotFound, "Unknown Target", nil))
		return
	}

	targets := s.resolveTargets(callerUser, targetUser)
	if len(targets) == 0 {
		s.logger.Warn("invite rejected: no registered targets", "caller", callerUser, "target", targetUser, "call_id", callID(req))
		_ = tx.Respond(sip.NewResponseFromRequest(req, sip.StatusNotFound, "Target Not Registered", nil))
		return
	}

	callerRemoteMedia, err := media.ExtractMediaEndpoints(req.Body())
	if err != nil {
		s.logger.Warn("invite rejected: invalid media offer", "caller", callerUser, "target", targetUser, "call_id", callID(req), "error", err)
		_ = tx.Respond(sip.NewResponseFromRequest(req, sip.StatusNotAcceptableHere, "SDP Offer Required", nil))
		return
	}

	mediaSession, err := media.NewSession(s.randomID("media"), s.ports, media.Binding{
		Name:    bind.cfg.Name,
		MediaIP: bind.cfg.MediaIP,
	}, callerRemoteMedia)
	if err != nil {
		s.logger.Error("invite rejected: media session create failed", "caller", callerUser, "target", targetUser, "call_id", callID(req), "error", err)
		_ = tx.Respond(sip.NewResponseFromRequest(req, sip.StatusInternalServerError, "Media Proxy Error", nil))
		return
	}

	contact := req.Contact()
	if contact == nil {
		mediaSession.Close()
		s.logger.Warn("invite rejected: missing contact", "caller", callerUser, "call_id", callID(req))
		_ = tx.Respond(sip.NewResponseFromRequest(req, sip.StatusBadRequest, "Missing Contact", nil))
		return
	}

	call := &callSession{
		id:             s.randomID("call"),
		originalInvite: req.Clone(),
		inviteTx:       tx,
		callerBinding:  bind,
		callerCallID:   callID(req),
		callerContact:  *contact.Address.Clone(),
		callerFrom:     *callerFrom,
		callerTo:       *cloneToHeader(req.To()),
		callerTag:      randomHex(8),
		callerByeSeq:   req.CSeq().SeqNo + 1,
		target:         targetUser,
		media:          mediaSession,
		branches:       make(map[string]*callBranch),
		finalCh:        make(chan bool, 1),
	}

	s.mu.Lock()
	s.callsByCaller[call.callerCallID] = call
	s.mu.Unlock()
	s.logger.Info("call created", "call_id", call.callerCallID, "internal_call_id", call.id, "caller", callerUser, "target", targetUser, "interface", bind.cfg.Name, "targets_count", len(targets), "caller_contact", contact.Value(), "media_offer", fmt.Sprintf("%v", callerRemoteMedia))

	_ = tx.Respond(sip.NewResponseFromRequest(req, sip.StatusTrying, "Trying", nil))

	created := 0
	for _, reg := range targets {
		if err := s.startBranch(call, reg); err != nil {
			s.logger.Error("branch start failed", "call_id", call.callerCallID, "target_user", reg.Username, "interface", reg.Interface, "contact", reg.Contact, "error", err)
			continue
		}
		created++
	}

	if created == 0 {
		s.logger.Warn("call failed: no reachable targets", "call_id", call.callerCallID, "caller", callerUser, "target", targetUser)
		resp := s.newCallerResponse(call, sip.StatusTemporarilyUnavailable, "No Reachable Targets", nil)
		_ = tx.Respond(resp)
		call.finalSent = true
		call.notifyFinal(false)
		s.waitInviteCompletion(call)
		s.cleanupCall(call)
		return
	}

	success := s.waitInviteCompletion(call)
	if !success {
		s.cleanupCall(call)
	}
}

func (s *Server) onAck(req *sip.Request, _ sip.ServerTransaction) {
	s.logRequest("ack received", req)
	call := s.findCallerCall(callID(req))
	if call == nil {
		s.logger.Debug("ack ignored: call not found", "call_id", callID(req))
		return
	}

	call.mu.Lock()
	winner := call.winner
	call.mu.Unlock()
	if winner == nil {
		s.logger.Debug("ack ignored: winner branch not set", "call_id", callID(req))
		return
	}
	s.forwardCallerAck(call)
}

func (s *Server) onBye(req *sip.Request, tx sip.ServerTransaction) {
	s.logRequest("bye received", req)
	if branch := s.findBranch(callID(req)); branch != nil {
		s.logger.Info("callee sent BYE", "call_id", branch.call.callerCallID, "branch_id", branch.id, "branch_call_id", branch.callID)
		_ = tx.Respond(sip.NewResponseFromRequest(req, sip.StatusOK, "OK", nil))
		s.forwardByeToCaller(branch)
		return
	}

	call := s.findCallerCall(callID(req))
	if call == nil {
		s.logger.Warn("bye rejected: call not found", "call_id", callID(req))
		_ = tx.Respond(sip.NewResponseFromRequest(req, sip.StatusCallTransactionDoesNotExists, "Call/Transaction Does Not Exist", nil))
		return
	}

	_ = tx.Respond(sip.NewResponseFromRequest(req, sip.StatusOK, "OK", nil))

	call.mu.Lock()
	winner := call.winner
	call.terminated = true
	call.mu.Unlock()
	s.logger.Info("caller sent BYE", "call_id", call.callerCallID, "winner_branch", branchID(winner))

	if winner != nil {
		bye, err := buildByeRequest(winner.invite, winner.remoteTo, winner.remoteContact, winner.cseq+1)
		if err == nil {
			s.logger.Info("forwarding BYE to winner", "call_id", call.callerCallID, "branch_id", winner.id, "branch_call_id", winner.callID)
			_, _ = winner.binding.client.TransactionRequest(context.Background(), bye)
		} else {
			s.logger.Error("bye build failed", "call_id", call.callerCallID, "branch_id", winner.id, "error", err)
		}
	}
	s.cleanupCall(call)
}

func (s *Server) onCancel(req *sip.Request, tx sip.ServerTransaction) {
	s.logRequest("cancel received", req)
	call := s.findCallerCall(callID(req))
	if call == nil {
		s.logger.Warn("cancel rejected: call not found", "call_id", callID(req))
		_ = tx.Respond(sip.NewResponseFromRequest(req, sip.StatusCallTransactionDoesNotExists, "Call/Transaction Does Not Exist", nil))
		return
	}
	_ = tx.Respond(sip.NewResponseFromRequest(req, sip.StatusOK, "OK", nil))

	call.mu.Lock()
	call.cancelled = true
	s.logger.Info("call cancelled by caller", "call_id", call.callerCallID, "branches", len(call.branches))
	for _, branch := range call.branches {
		if branch.final {
			continue
		}
		branch.cancelled = true
		cancelReq := buildCancelRequest(branch.invite)
		s.logger.Info("forwarding CANCEL to branch", "call_id", call.callerCallID, "branch_id", branch.id, "branch_call_id", branch.callID, "destination", branch.invite.Destination())
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		_, _ = branch.binding.client.TransactionRequest(ctx, cancelReq)
		cancel()
	}
	call.mu.Unlock()
}

func (s *Server) onOptions(req *sip.Request, tx sip.ServerTransaction) {
	s.logRequest("options received", req)
	resp := sip.NewResponseFromRequest(req, sip.StatusOK, "OK", nil)
	resp.AppendHeader(sip.NewHeader("Allow", "REGISTER, INVITE, ACK, BYE, CANCEL, OPTIONS"))
	_ = tx.Respond(resp)
}

func (s *Server) startBranch(call *callSession, reg registrar.Registration) error {
	branchBind := s.bindings[reg.Interface]
	if branchBind == nil {
		return fmt.Errorf("unknown binding %q", reg.Interface)
	}

	branchID := s.randomID("branch")
	rtpEndpoints, err := call.media.AddBranch(branchID, media.Binding{
		Name:    branchBind.cfg.Name,
		MediaIP: branchBind.cfg.MediaIP,
	})
	if err != nil {
		return err
	}

	outboundSDP, err := media.RewriteMediaEndpoints(call.originalInvite.Body(), branchBind.cfg.MediaIP, rtpEndpoints)
	if err != nil {
		call.media.RemoveBranch(branchID)
		return err
	}

	contactURI, err := parseStoredContact(reg.Contact)
	if err != nil {
		call.media.RemoveBranch(branchID)
		return err
	}

	invite := buildBranchInvite(call, branchBind, contactURI, outboundSDP)
	invite.Laddr = branchBind.local

	ctx, cancel := context.WithCancel(context.Background())
	tx, err := branchBind.client.TransactionRequest(ctx, invite)
	if err != nil {
		cancel()
		call.media.RemoveBranch(branchID)
		return err
	}

	branch := &callBranch{
		id:           branchID,
		call:         call,
		username:     reg.Username,
		registration: reg,
		binding:      branchBind,
		tx:           tx,
		invite:       invite.Clone(),
		callID:       callID(invite),
		cseq:         invite.CSeq().SeqNo,
	}

	call.mu.Lock()
	call.branches[branch.id] = branch
	call.mu.Unlock()

	s.mu.Lock()
	s.branchByCallID[branch.callID] = branch
	s.mu.Unlock()
	s.logger.Info("branch created", "call_id", call.callerCallID, "branch_id", branch.id, "branch_call_id", branch.callID, "target_user", reg.Username, "interface", branchBind.cfg.Name, "contact", reg.Contact, "media_endpoints", fmt.Sprintf("%v", rtpEndpoints))

	go s.watchBranchResponses(branch, cancel)
	return nil
}

func (s *Server) watchBranchResponses(branch *callBranch, cancel context.CancelFunc) {
	defer cancel()
	defer branch.tx.Terminate()

	for {
		select {
		case res := <-branch.tx.Responses():
			if res == nil {
				continue
			}
			s.logger.Info("branch response received", "call_id", branch.call.callerCallID, "branch_id", branch.id, "branch_call_id", branch.callID, "status", res.StatusCode, "reason", res.Reason, "has_sdp", len(res.Body()) > 0)
			if res.IsProvisional() {
				s.handleBranchProvisional(branch, res)
				continue
			}
			s.handleBranchFinal(branch, res)
			branch.call.maybeFinalizeFailure(s)
			return

		case <-branch.tx.Done():
			s.logger.Debug("branch transaction done", "call_id", branch.call.callerCallID, "branch_id", branch.id, "branch_call_id", branch.callID)
			branch.call.maybeFinalizeFailure(s)
			return
		}
	}
}

func (s *Server) handleBranchProvisional(branch *callBranch, res *sip.Response) {
	call := branch.call
	call.mu.Lock()
	defer call.mu.Unlock()

	branch.remoteTo = cloneToHeader(res.To())
	if contact := contactFromResponse(res); contact != nil {
		branch.remoteContact = *contact
	}

	var body []byte
	if len(res.Body()) > 0 {
		if remotes, err := media.ExtractMediaEndpoints(res.Body()); err == nil {
			call.media.UpdateBranchRemotes(branch.id, remotes)
			callerEndpoints := call.media.CallerEndpoints()
			body, _ = media.RewriteMediaEndpoints(res.Body(), call.callerBinding.cfg.MediaIP, callerEndpoints)
			s.logger.Info("branch provisional media updated", "call_id", call.callerCallID, "branch_id", branch.id, "remote_media", fmt.Sprintf("%v", remotes), "caller_media", fmt.Sprintf("%v", callerEndpoints))
		} else {
			s.logger.Warn("branch provisional SDP parse failed", "call_id", call.callerCallID, "branch_id", branch.id, "error", err)
		}
	}

	s.logger.Info("forwarding provisional response to caller", "call_id", call.callerCallID, "branch_id", branch.id, "status", res.StatusCode, "reason", res.Reason, "has_sdp", len(body) > 0)
	resp := s.newCallerResponse(call, res.StatusCode, res.Reason, body)
	_ = call.inviteTx.Respond(resp)
}

func (s *Server) handleBranchFinal(branch *callBranch, res *sip.Response) {
	call := branch.call

	call.mu.Lock()
	defer call.mu.Unlock()

	branch.final = true
	branch.remoteTo = cloneToHeader(res.To())
	if contact := contactFromResponse(res); contact != nil {
		branch.remoteContact = *contact
	}

	if res.IsSuccess() {
		if call.winner != nil && call.winner.id != branch.id {
			s.logger.Info("late 2xx received on non-winning branch", "call_id", call.callerCallID, "branch_id", branch.id, "winner_branch_id", call.winner.id)
			go s.ackAndByeLateBranch(branch)
			return
		}

		call.winner = branch
		s.logger.Info("branch selected as winner", "call_id", call.callerCallID, "branch_id", branch.id, "branch_call_id", branch.callID, "target_user", branch.username)
		var body []byte
		if len(res.Body()) > 0 {
			if remotes, err := media.ExtractMediaEndpoints(res.Body()); err == nil {
				call.media.UpdateBranchRemotes(branch.id, remotes)
				callerEndpoints := call.media.CallerEndpoints()
				body, _ = media.RewriteMediaEndpoints(res.Body(), call.callerBinding.cfg.MediaIP, callerEndpoints)
				s.logger.Info("winner media updated", "call_id", call.callerCallID, "branch_id", branch.id, "remote_media", fmt.Sprintf("%v", remotes), "caller_media", fmt.Sprintf("%v", callerEndpoints))
			} else {
				s.logger.Warn("winner SDP parse failed", "call_id", call.callerCallID, "branch_id", branch.id, "error", err)
			}
		}

		resp := s.newCallerResponse(call, res.StatusCode, res.Reason, body)
		_ = call.inviteTx.Respond(resp)
		call.finalSent = true
		call.notifyFinal(true)
		s.logger.Info("forwarding 2xx to caller", "call_id", call.callerCallID, "branch_id", branch.id, "status", res.StatusCode, "reason", res.Reason, "has_sdp", len(body) > 0)

		for _, other := range call.branches {
			if other.id == branch.id || other.final {
				continue
			}
			other.cancelled = true
			cancelReq := buildCancelRequest(other.invite)
			s.logger.Info("cancelling losing branch", "call_id", call.callerCallID, "winner_branch_id", branch.id, "branch_id", other.id, "branch_call_id", other.callID)
			ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
			_, _ = other.binding.client.TransactionRequest(ctx, cancelReq)
			cancel()
		}
		return
	}

	if call.failStatus == 0 || betterFailure(res.StatusCode, call.failStatus) {
		call.failStatus = res.StatusCode
		call.failReason = res.Reason
	}
	s.logger.Info("branch failed", "call_id", call.callerCallID, "branch_id", branch.id, "branch_call_id", branch.callID, "status", res.StatusCode, "reason", res.Reason)
	call.media.RemoveBranch(branch.id)
}

func (c *callSession) maybeFinalizeFailure(s *Server) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if c.finalSent || c.winner != nil {
		return
	}

	for _, branch := range c.branches {
		if !branch.final {
			return
		}
	}

	status := c.failStatus
	reason := c.failReason
	if c.cancelled {
		status, reason = sip.StatusRequestTerminated, "Request Terminated"
	}
	if status == 0 {
		status, reason = sip.StatusTemporarilyUnavailable, "Temporarily Unavailable"
	}

	s.logger.Info("all branches completed without winner", "call_id", c.callerCallID, "status", status, "reason", reason)
	resp := s.newCallerResponse(c, status, reason, nil)
	_ = c.inviteTx.Respond(resp)
	c.finalSent = true
	c.notifyFinal(false)
}

func (s *Server) ackAndByeLateBranch(branch *callBranch) {
	if branch.remoteTo == nil {
		s.logger.Warn("late branch cleanup skipped: missing remote To", "call_id", branch.call.callerCallID, "branch_id", branch.id)
		return
	}
	ack, err := buildACKRequest(branch.invite, branch.remoteTo, branch.remoteContact)
	if err == nil {
		s.logger.Info("sending ACK to late branch", "call_id", branch.call.callerCallID, "branch_id", branch.id, "branch_call_id", branch.callID)
		_ = branch.binding.client.WriteRequest(ack)
	} else {
		s.logger.Error("late branch ACK build failed", "call_id", branch.call.callerCallID, "branch_id", branch.id, "error", err)
	}

	time.Sleep(50 * time.Millisecond)

	bye, err := buildByeRequest(branch.invite, branch.remoteTo, branch.remoteContact, branch.cseq+1)
	if err == nil {
		s.logger.Info("sending BYE to late branch", "call_id", branch.call.callerCallID, "branch_id", branch.id, "branch_call_id", branch.callID)
		_, _ = branch.binding.client.TransactionRequest(context.Background(), bye)
	} else {
		s.logger.Error("late branch BYE build failed", "call_id", branch.call.callerCallID, "branch_id", branch.id, "error", err)
	}
}

func (s *Server) forwardByeToCaller(branch *callBranch) {
	call := branch.call

	call.mu.Lock()
	defer call.mu.Unlock()

	if call.terminated {
		s.logger.Debug("caller BYE forwarding skipped: call already terminated", "call_id", call.callerCallID)
		return
	}

	bye, err := buildCallerBye(call)
	if err != nil {
		s.logger.Error("caller BYE build failed", "call_id", call.callerCallID, "branch_id", branch.id, "error", err)
		return
	}
	s.logger.Info("forwarding BYE to caller", "call_id", call.callerCallID, "branch_id", branch.id, "caller_destination", bye.Destination())
	_, _ = call.callerBinding.client.TransactionRequest(context.Background(), bye)
	call.terminated = true
	go s.cleanupCall(call)
}

func (s *Server) cleanupCall(call *callSession) {
	call.mu.Lock()
	if call.media != nil {
		call.media.Close()
		call.media = nil
	}
	branches := make([]*callBranch, 0, len(call.branches))
	for _, branch := range call.branches {
		branches = append(branches, branch)
	}
	call.mu.Unlock()

	s.logger.Info("cleaning call", "call_id", call.callerCallID, "branches", len(branches), "winner_branch", branchID(call.winner))
	s.mu.Lock()
	delete(s.callsByCaller, call.callerCallID)
	for _, branch := range branches {
		delete(s.branchByCallID, branch.callID)
	}
	s.mu.Unlock()
}

func (s *Server) waitInviteCompletion(call *callSession) bool {
	var success bool

	select {
	case success = <-call.finalCh:
		s.logger.Info("final INVITE response delivered", "call_id", call.callerCallID, "success", success)
	case <-call.inviteTx.Done():
		s.logger.Warn("invite transaction finished before final notification", "call_id", call.callerCallID)
		call.mu.Lock()
		success = call.winner != nil
		call.mu.Unlock()
		return success
	}

	select {
	case ack := <-call.inviteTx.Acks():
		s.logger.Info("ACK received on INVITE transaction", "call_id", call.callerCallID, "ack_call_id", callID(ack), "success", success)
		if success {
			s.forwardCallerAck(call)
		}
		call.inviteTx.Terminate()
	case <-call.inviteTx.Done():
		s.logger.Info("invite transaction completed", "call_id", call.callerCallID, "success", success)
	case <-time.After(35 * time.Second):
		s.logger.Warn("timeout waiting for INVITE completion", "call_id", call.callerCallID, "success", success)
		call.inviteTx.Terminate()
	}

	return success
}

func (s *Server) forwardCallerAck(call *callSession) {
	call.mu.Lock()
	winner := call.winner
	call.mu.Unlock()

	if winner == nil {
		s.logger.Debug("caller ACK ignored: winner branch not set", "call_id", call.callerCallID)
		return
	}

	ack, err := buildACKRequest(winner.invite, winner.remoteTo, winner.remoteContact)
	if err != nil {
		s.logger.Error("ack build failed", "call_id", call.callerCallID, "branch_id", winner.id, "error", err)
		return
	}
	s.logger.Info("forwarding ACK to winner", "call_id", call.callerCallID, "branch_id", winner.id, "branch_call_id", winner.callID, "destination", winner.invite.Destination())
	_ = winner.binding.client.WriteRequest(ack)
}

func (s *Server) closeCalls() {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, call := range s.callsByCaller {
		call.mu.Lock()
		if call.media != nil {
			call.media.Close()
			call.media = nil
		}
		call.mu.Unlock()
	}
	s.logger.Info("all active calls closed")
}

func (s *Server) authorize(header, method, uri string) (string, config.UserConfig, bool) {
	var user config.UserConfig
	if header == "" {
		return "", user, false
	}
	for candidate, candidateUser := range s.users {
		u, valid := s.auth.Validate(header, method, uri, candidateUser.Password)
		if valid && u == candidate {
			return candidate, candidateUser, true
		}
	}
	return "", user, false
}

func (s *Server) resolveTargets(callerUser, targetUser string) []registrar.Registration {
	if targetUser != s.cfg.DistributedExtension {
		return s.registrar.GetByUser(targetUser)
	}

	active := s.registrar.ActiveUsers()
	targets := make([]registrar.Registration, 0)
	for username, regs := range active {
		if username == callerUser {
			continue
		}
		user, ok := s.users[username]
		if !ok || !user.Distributed {
			continue
		}
		targets = append(targets, regs...)
	}
	return targets
}

func (s *Server) bindingForRequest(req *sip.Request) *binding {
	host, _, err := net.SplitHostPort(req.Source())
	if err != nil {
		host = req.Source()
	}
	ip := net.ParseIP(host)
	if ip == nil {
		return nil
	}
	for _, bind := range s.bindings {
		if bind.subnet.Contains(ip) {
			return bind
		}
	}
	return nil
}

func (s *Server) findCallerCall(callID string) *callSession {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.callsByCaller[callID]
}

func (s *Server) findBranch(callID string) *callBranch {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.branchByCallID[callID]
}

func (s *Server) randomID(prefix string) string {
	buf := make([]byte, 8)
	_, _ = rand.Read(buf)
	return prefix + "-" + hex.EncodeToString(buf)
}

func (s *Server) logRequest(event string, req *sip.Request) {
	s.logger.Info(event,
		"method", req.Method.String(),
		"call_id", callID(req),
		"source", req.Source(),
		"destination", req.Destination(),
		"recipient", req.Recipient.String(),
		"from", fromUser(req),
		"to", toUser(req),
		"transport", req.Transport(),
	)
}

func (s *Server) newCallerResponse(call *callSession, status int, reason string, body []byte) *sip.Response {
	resp := sip.NewResponseFromRequest(call.originalInvite, status, reason, body)
	if status >= 101 {
		if to := resp.To(); to != nil {
			if to.Params == nil {
				to.Params = sip.NewParams()
			}
			to.Params.Add("tag", call.callerTag)
		}
	}
	resp.RemoveHeader("Contact")
	resp.AppendHeader(serverContactHeader(call.callerBinding))
	if len(body) > 0 {
		resp.RemoveHeader("Content-Type")
		resp.AppendHeader(sip.NewHeader("Content-Type", "application/sdp"))
		resp.SetBody(body)
	}
	return resp
}

func buildBranchInvite(call *callSession, bind *binding, contact sip.Uri, sdp []byte) *sip.Request {
	req := sip.NewRequest(sip.INVITE, contact)
	req.SetTransport("UDP")
	req.AppendHeader(&sip.FromHeader{
		DisplayName: call.callerFrom.DisplayName,
		Address:     *call.callerFrom.Address.Clone(),
		Params:      cloneParams(call.callerFrom.Params),
	})
	if from := req.From(); from != nil {
		if from.Params == nil {
			from.Params = sip.NewParams()
		}
		from.Params.Add("tag", randomHex(8))
	}
	req.AppendHeader(&sip.ToHeader{
		DisplayName: "",
		Address: sip.Uri{
			Scheme: contact.Scheme,
			User:   contact.User,
			Host:   contact.Host,
			Port:   contact.Port,
		},
	})
	callID := sip.CallIDHeader(randomHex(12))
	req.AppendHeader(&callID)
	req.AppendHeader(&sip.CSeqHeader{SeqNo: call.originalInvite.CSeq().SeqNo, MethodName: sip.INVITE})
	maxForwards := sip.MaxForwardsHeader(70)
	req.AppendHeader(&maxForwards)
	req.AppendHeader(serverContactHeader(bind))
	req.AppendHeader(sip.NewHeader("User-Agent", "sipserver/0.1"))
	req.AppendHeader(sip.NewHeader("Content-Type", "application/sdp"))
	req.SetBody(sdp)
	return req
}

func buildCancelRequest(invite *sip.Request) *sip.Request {
	req := sip.NewRequest(sip.CANCEL, *invite.Recipient.Clone())
	req.SetTransport(invite.Transport())
	req.SetSource(invite.Source())
	req.SetDestination(invite.Destination())
	req.Laddr = invite.Laddr
	req.AppendHeader(sip.HeaderClone(invite.Via()))
	maxForwards := sip.MaxForwardsHeader(70)
	req.AppendHeader(&maxForwards)
	req.AppendHeader(sip.HeaderClone(invite.From()))
	req.AppendHeader(sip.HeaderClone(invite.To()))
	req.AppendHeader(sip.HeaderClone(invite.CallID()))
	cseq := &sip.CSeqHeader{SeqNo: invite.CSeq().SeqNo, MethodName: sip.CANCEL}
	req.AppendHeader(cseq)
	req.SetBody(nil)
	return req
}

func buildACKRequest(invite *sip.Request, remoteTo *sip.ToHeader, contact sip.Uri) (*sip.Request, error) {
	if remoteTo == nil {
		return nil, fmt.Errorf("missing remote To header")
	}
	target := contact
	if target.Host == "" {
		target = *invite.Recipient.Clone()
	}

	req := sip.NewRequest(sip.ACK, target)
	req.SetTransport(invite.Transport())
	req.SetSource(invite.Source())
	req.SetDestination(uriDestination(target, invite.Transport()))
	req.Laddr = invite.Laddr
	req.AppendHeader(sip.HeaderClone(invite.Via()))
	maxForwards := sip.MaxForwardsHeader(70)
	req.AppendHeader(&maxForwards)
	req.AppendHeader(sip.HeaderClone(invite.From()))
	toCopy := *cloneToHeader(remoteTo)
	req.AppendHeader(&toCopy)
	req.AppendHeader(sip.HeaderClone(invite.CallID()))
	req.AppendHeader(&sip.CSeqHeader{SeqNo: invite.CSeq().SeqNo, MethodName: sip.ACK})
	req.AppendHeader(serverContactFromInvite(invite))
	req.SetBody(nil)
	return req, nil
}

func buildByeRequest(invite *sip.Request, remoteTo *sip.ToHeader, contact sip.Uri, seq uint32) (*sip.Request, error) {
	if remoteTo == nil {
		return nil, fmt.Errorf("missing remote To header")
	}
	target := contact
	if target.Host == "" {
		target = *invite.Recipient.Clone()
	}

	req := sip.NewRequest(sip.BYE, target)
	req.SetTransport(invite.Transport())
	req.SetSource(invite.Source())
	req.SetDestination(uriDestination(target, invite.Transport()))
	req.Laddr = invite.Laddr
	req.AppendHeader(&sip.ViaHeader{
		ProtocolName:    "SIP",
		ProtocolVersion: "2.0",
		Transport:       invite.Transport(),
		Host:            invite.Via().Host,
		Port:            invite.Via().Port,
		Params:          sip.NewParams(),
	})
	req.Via().Params.Add("branch", "z9hG4bK-"+randomHex(6))
	maxForwards := sip.MaxForwardsHeader(70)
	req.AppendHeader(&maxForwards)
	req.AppendHeader(sip.HeaderClone(invite.From()))
	toCopy := *cloneToHeader(remoteTo)
	req.AppendHeader(&toCopy)
	req.AppendHeader(sip.HeaderClone(invite.CallID()))
	req.AppendHeader(&sip.CSeqHeader{SeqNo: seq, MethodName: sip.BYE})
	req.AppendHeader(serverContactFromInvite(invite))
	req.SetBody(nil)
	return req, nil
}

func buildCallerBye(call *callSession) (*sip.Request, error) {
	target := *call.callerContact.Clone()
	req := sip.NewRequest(sip.BYE, target)
	req.SetTransport("UDP")
	req.SetDestination(uriDestination(target, "UDP"))
	req.Laddr = call.callerBinding.local
	req.AppendHeader(&sip.ViaHeader{
		ProtocolName:    "SIP",
		ProtocolVersion: "2.0",
		Transport:       "UDP",
		Host:            call.callerBinding.cfg.AdvertiseIP,
		Port:            call.callerBinding.local.Port,
		Params:          sip.NewParams(),
	})
	req.Via().Params.Add("branch", "z9hG4bK-"+randomHex(6))
	maxForwards := sip.MaxForwardsHeader(70)
	req.AppendHeader(&maxForwards)

	from := call.callerTo
	from.Params = cloneParams(call.callerTo.Params)
	if from.Params == nil {
		from.Params = sip.NewParams()
	}
	from.Params.Add("tag", call.callerTag)
	fromHeader := from.AsFrom()
	req.AppendHeader(&fromHeader)

	toHeader := call.callerFrom.AsTo()
	req.AppendHeader(&toHeader)
	callID := sip.CallIDHeader(call.callerCallID)
	req.AppendHeader(&callID)
	req.AppendHeader(&sip.CSeqHeader{SeqNo: call.callerByeSeq, MethodName: sip.BYE})
	req.AppendHeader(serverContactHeader(call.callerBinding))
	req.SetBody(nil)
	call.callerByeSeq++
	return req, nil
}

func serverContactHeader(bind *binding) *sip.ContactHeader {
	return &sip.ContactHeader{
		Address: sip.Uri{
			Scheme: "sip",
			User:   "sipserver",
			Host:   bind.cfg.AdvertiseIP,
			Port:   bind.local.Port,
		},
	}
}

func serverContactFromInvite(invite *sip.Request) *sip.ContactHeader {
	if contact := invite.Contact(); contact != nil {
		return contact.Clone()
	}
	return &sip.ContactHeader{Address: sip.Uri{Scheme: "sip", User: "sipserver"}}
}

func parseExpires(req *sip.Request) int {
	if contact := req.Contact(); contact != nil && contact.Params != nil {
		if value, ok := contact.Params.Get("expires"); ok {
			if n, err := strconv.Atoi(value); err == nil {
				return n
			}
		}
	}
	if h := req.GetHeader("Expires"); h != nil {
		if n, err := strconv.Atoi(strings.TrimSpace(h.Value())); err == nil {
			return n
		}
	}
	return 3600
}

func parseStoredContact(raw string) (sip.Uri, error) {
	value := strings.TrimSpace(raw)
	if strings.HasPrefix(value, "*") {
		return sip.Uri{Wildcard: true}, nil
	}
	if lt := strings.IndexByte(value, '<'); lt >= 0 {
		if gt := strings.IndexByte(value[lt+1:], '>'); gt >= 0 {
			value = value[lt+1 : lt+1+gt]
		}
	} else if idx := strings.IndexByte(value, ';'); idx >= 0 {
		value = value[:idx]
	}

	var uri sip.Uri
	if err := sip.ParseUri(value, &uri); err != nil {
		return sip.Uri{}, err
	}
	return uri, nil
}

func callID(msg interface{ CallID() *sip.CallIDHeader }) string {
	if h := msg.CallID(); h != nil {
		return h.Value()
	}
	return ""
}

func headerValue(req *sip.Request, name string) string {
	if h := req.GetHeader(name); h != nil {
		return h.Value()
	}
	return ""
}

func cloneToHeader(h *sip.ToHeader) *sip.ToHeader {
	if h == nil {
		return &sip.ToHeader{}
	}
	from := h.AsFrom()
	cloned := (&from).AsTo()
	return &cloned
}

func cloneParams(params sip.HeaderParams) sip.HeaderParams {
	if params == nil {
		return nil
	}
	return params.Clone()
}

func contactFromResponse(res *sip.Response) *sip.Uri {
	if contact := res.Contact(); contact != nil {
		uri := *contact.Address.Clone()
		return &uri
	}
	return nil
}

func uriDestination(uri sip.Uri, transport string) string {
	port := uri.Port
	if port == 0 {
		port = sip.DefaultPort(transport)
	}
	return net.JoinHostPort(uri.Host, strconv.Itoa(port))
}

func betterFailure(newCode, current int) bool {
	if current == 0 {
		return true
	}
	if newCode == sip.StatusBusyHere && current != sip.StatusBusyHere {
		return true
	}
	return newCode > current
}

func randomHex(n int) string {
	buf := make([]byte, n)
	_, _ = rand.Read(buf)
	return hex.EncodeToString(buf)
}

func (c *callSession) notifyFinal(success bool) {
	c.finalOnce.Do(func() {
		c.finalCh <- success
	})
}

func fromUser(req *sip.Request) string {
	if h := req.From(); h != nil {
		return h.Address.User
	}
	return ""
}

func toUser(req *sip.Request) string {
	if h := req.To(); h != nil {
		return h.Address.User
	}
	return req.Recipient.User
}

func branchID(branch *callBranch) string {
	if branch == nil {
		return ""
	}
	return branch.id
}
