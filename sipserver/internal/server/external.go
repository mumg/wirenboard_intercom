package server

import (
	"context"
	"fmt"
	"net"
	"strconv"
	"time"

	"github.com/emiago/sipgo"
	"github.com/emiago/sipgo/sip"

	"sipserver/internal/config"
)

type externalAccount struct {
	cfg    config.ExternalAccount
	bind   *binding
	caller config.UserConfig
}

type inboundCallSpec struct {
	bind                    *binding
	callerUser              string
	targetUser              string
	signalingFrom           sip.FromHeader
	signalingTo             sip.ToHeader
	branchFrom              sip.FromHeader
	requireRegisteredCaller bool
	route                   string
}

func (s *Server) matchExternalAccount(bind *binding, req *sip.Request) *externalAccount {
	contactUser := req.Recipient.User
	if contactUser == "" && req.To() != nil {
		contactUser = req.To().Address.User
	}
	if contactUser == "" {
		return nil
	}

	for _, account := range s.external {
		if account.bind.cfg.Name != bind.cfg.Name {
			continue
		}
		if account.cfg.ContactUser == contactUser {
			return account
		}
	}
	return nil
}

func (s *Server) handleExternalInvite(account *externalAccount, req *sip.Request, tx sip.ServerTransaction) {
	if req.From() == nil {
		s.logger.Warn("external invite rejected: missing from", "account", account.cfg.Name, "call_id", callID(req))
		_ = tx.Respond(sip.NewResponseFromRequest(req, sip.StatusBadRequest, "Missing From", nil))
		return
	}
	if req.To() == nil {
		s.logger.Warn("external invite rejected: missing to", "account", account.cfg.Name, "call_id", callID(req))
		_ = tx.Respond(sip.NewResponseFromRequest(req, sip.StatusBadRequest, "Missing To", nil))
		return
	}

	s.logger.Info("invite routed from external UAC account",
		"account", account.cfg.Name,
		"interface", account.bind.cfg.Name,
		"configured_caller", account.cfg.Caller,
		"configured_callee", account.cfg.Callee,
		"call_id", callID(req),
	)

	s.startInboundCall(req, tx, inboundCallSpec{
		bind:                    account.bind,
		callerUser:              account.cfg.Caller,
		targetUser:              account.cfg.Callee,
		signalingFrom:           *cloneFromHeader(req.From()),
		signalingTo:             *cloneToHeader(req.To()),
		branchFrom:              buildConfiguredCallerHeader(account, s.cfg.Realm),
		requireRegisteredCaller: false,
		route:                   "external-uac:" + account.cfg.Name,
	})
}

func buildConfiguredCallerHeader(account *externalAccount, realm string) sip.FromHeader {
	displayName := account.caller.DisplayName
	if displayName == "" {
		displayName = account.cfg.Caller
	}

	return sip.FromHeader{
		DisplayName: displayName,
		Address: sip.Uri{
			Scheme: "sip",
			User:   account.cfg.Caller,
			Host:   realm,
		},
		Params: sip.NewParams(),
	}
}

func (s *Server) runExternalRegistrationLoop(ctx context.Context, account *externalAccount) {
	for {
		expires, err := s.registerExternalAccount(ctx, account)
		if err != nil {
			s.logger.Error("external account registration failed",
				"account", account.cfg.Name,
				"server", account.cfg.Server,
				"interface", account.bind.cfg.Name,
				"error", err,
			)
			if !sleepWithContext(ctx, 15*time.Second) {
				return
			}
			continue
		}

		wait := registrationRefreshAfter(expires)
		s.logger.Info("external account registration active",
			"account", account.cfg.Name,
			"server", account.cfg.Server,
			"expires_sec", expires,
			"refresh_after", wait.String(),
		)
		if !sleepWithContext(ctx, wait) {
			return
		}
	}
}

func (s *Server) registerExternalAccount(parent context.Context, account *externalAccount) (int, error) {
	ctx, cancel := context.WithTimeout(parent, 20*time.Second)
	defer cancel()

	req := buildExternalRegisterRequest(account)
	tx, err := account.bind.client.TransactionRequest(ctx, req, sipgo.ClientRequestRegisterBuild)
	if err != nil {
		return 0, err
	}
	res, err := waitFinalResponse(ctx, tx)
	tx.Terminate()
	if err != nil {
		return 0, err
	}

	if res.StatusCode == sip.StatusUnauthorized || res.StatusCode == sip.StatusProxyAuthRequired {
		res, err = account.bind.client.DoDigestAuth(ctx, req, res, sipgo.DigestAuth{
			Username: account.cfg.AuthUsername,
			Password: account.cfg.Password,
		})
		if err != nil {
			return 0, err
		}
	}

	if res.StatusCode < 200 || res.StatusCode >= 300 {
		return 0, fmt.Errorf("register failed: %d %s", res.StatusCode, res.Reason)
	}

	expires := externalRegisterExpires(res, account.cfg.Expires)
	s.logger.Info("external account registered",
		"account", account.cfg.Name,
		"server", account.cfg.Server,
		"contact_user", account.cfg.ContactUser,
		"caller", account.cfg.Caller,
		"callee", account.cfg.Callee,
		"expires_sec", expires,
	)
	return expires, nil
}

func buildExternalRegisterRequest(account *externalAccount) *sip.Request {
	registrarURI := sip.Uri{
		Scheme: "sip",
		Host:   account.cfg.Domain,
	}
	aor := sip.Uri{
		Scheme: "sip",
		User:   account.cfg.Username,
		Host:   account.cfg.Domain,
	}

	req := sip.NewRequest(sip.REGISTER, registrarURI)
	req.SetTransport(account.cfg.Transport)
	req.SetDestination(account.cfg.Server)
	req.Laddr = account.bind.local

	from := &sip.FromHeader{
		Address: *aor.Clone(),
		Params:  sip.NewParams(),
	}
	from.Params.Add("tag", randomHex(8))
	req.AppendHeader(from)
	req.AppendHeader(&sip.ToHeader{Address: *aor.Clone()})

	contactParams := sip.NewParams()
	contactParams.Add("transport", "udp")
	req.AppendHeader(&sip.ContactHeader{
		Address: sip.Uri{
			Scheme: "sip",
			User:   account.cfg.ContactUser,
			Host:   account.bind.cfg.AdvertiseIP,
			Port:   account.bind.local.Port,
		},
		Params: contactParams,
	})
	req.AppendHeader(sip.NewHeader("Expires", strconv.Itoa(account.cfg.Expires)))
	req.AppendHeader(sip.NewHeader("User-Agent", "sipserver/0.1"))
	return req
}

func waitFinalResponse(ctx context.Context, tx sip.ClientTransaction) (*sip.Response, error) {
	for {
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-tx.Done():
			return nil, tx.Err()
		case res := <-tx.Responses():
			if res == nil || res.IsProvisional() {
				continue
			}
			return res, nil
		}
	}
}

func externalRegisterExpires(res *sip.Response, fallback int) int {
	if contact := res.Contact(); contact != nil && contact.Params != nil {
		if value, ok := contact.Params.Get("expires"); ok {
			if n, err := strconv.Atoi(value); err == nil && n > 0 {
				return n
			}
		}
	}
	if h := res.GetHeader("Expires"); h != nil {
		if n, err := strconv.Atoi(h.Value()); err == nil && n > 0 {
			return n
		}
	}
	return fallback
}

func registrationRefreshAfter(expires int) time.Duration {
	if expires <= 0 {
		return 2 * time.Minute
	}
	if expires <= 60 {
		return time.Duration(expires/2) * time.Second
	}
	return time.Duration(expires-30) * time.Second
}

func sleepWithContext(ctx context.Context, d time.Duration) bool {
	timer := time.NewTimer(d)
	defer timer.Stop()

	select {
	case <-ctx.Done():
		return false
	case <-timer.C:
		return true
	}
}

func requestMatchesBindingDestination(req *sip.Request, bind *binding) bool {
	host, port, ok := parseAddr(req.Destination())
	if !ok {
		host = req.Recipient.Host
		port = req.Recipient.Port
	}
	if host == "" {
		return false
	}
	if port == 0 {
		port = bind.local.Port
	}
	if port != bind.local.Port {
		return false
	}

	return sameHost(host, bind.local.Hostname) || sameHost(host, bind.cfg.AdvertiseIP)
}

func parseAddr(addr string) (string, int, bool) {
	if addr == "" {
		return "", 0, false
	}
	host, portStr, err := net.SplitHostPort(addr)
	if err != nil {
		return "", 0, false
	}
	port, err := strconv.Atoi(portStr)
	if err != nil {
		return "", 0, false
	}
	return host, port, true
}

func sameHost(a, b string) bool {
	if a == b {
		return true
	}
	ipA := net.ParseIP(a)
	ipB := net.ParseIP(b)
	if ipA == nil || ipB == nil {
		return false
	}
	return ipA.Equal(ipB)
}
