// Package tsnetlib wraps Tailscale's tsnet behind a minimal gomobile-bindable API.
//
// Design notes:
//   - ControlURL and AuthKey are passed in by the caller; no server address is hard-coded.
//   - Only two capabilities: start/stop the tunnel, and expose a tailnet target as a local
//     TCP port (per-app proxy). No system VPN, therefore system DNS is untouched.
//
// NOTE: keep every comment and string in this file ASCII-only. gomobile copies Go doc
// comments into the generated Java sources, and javac reads them with the platform
// encoding (GBK on zh-CN Windows), which fails on non-ASCII characters.
package tsnetlib

import (
	"context"
	"fmt"
	"io"
	"log"
	"net"
	"strconv"
	"sync"
	"time"

	"tailscale.com/tsnet"
)

var (
	mu      sync.Mutex
	srv     *tsnet.Server
	proxies = map[int]net.Listener{}
)

// Start brings up a tsnet client and waits until a tailnet IP is assigned.
//
// controlURL: coordination server URL (e.g. a self-hosted Headscale).
// Empty string falls back to tsnet's own behavior (TS_CONTROL_URL env var).
// authKey: preauth key (tskey-auth-...). Empty means interactive login, which is
// not useful on a headless device.
// hostname: node name shown in the tailnet.
// stateDir: local state directory for tailscaled.state; caller decides the location.
//
// Returns the node IP inside the tailnet (for example 100.64.0.5).
func Start(controlURL, authKey, hostname, stateDir string) (string, error) {
	mu.Lock()
	defer mu.Unlock()
	if srv != nil {
		return "", fmt.Errorf("tsnet already running")
	}

	s := &tsnet.Server{
		Dir:        stateDir,
		Hostname:   hostname,
		AuthKey:    authKey,
		ControlURL: controlURL,
		Logf:       log.Printf,
	}
	if err := s.Start(); err != nil {
		return "", fmt.Errorf("start: %w", err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()
	st, err := s.Up(ctx)
	if err != nil {
		_ = s.Close()
		return "", fmt.Errorf("up: %w", err)
	}
	if st == nil || len(st.TailscaleIPs) == 0 {
		_ = s.Close()
		return "", fmt.Errorf("up: no tailscale IP assigned")
	}

	srv = s
	return st.TailscaleIPs[0].String(), nil
}

// Stop shuts the tunnel down and releases every proxy listener.
func Stop() error {
	mu.Lock()
	defer mu.Unlock()
	for port, ln := range proxies {
		_ = ln.Close()
		delete(proxies, port)
	}
	if srv == nil {
		return nil
	}
	err := srv.Close()
	srv = nil
	return err
}

// StartProxy listens on a random 127.0.0.1 port and forwards connections to
// host:port inside the tailnet. Returns the local port. The app then connects to
// 127.0.0.1:<port>, so no system VPN is involved and other apps are unaffected.
func StartProxy(host string, port int) (int, error) {
	mu.Lock()
	s := srv
	mu.Unlock()
	if s == nil {
		return 0, fmt.Errorf("tsnet not running")
	}

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, fmt.Errorf("listen: %w", err)
	}
	localPort := ln.Addr().(*net.TCPAddr).Port
	target := net.JoinHostPort(host, strconv.Itoa(port))

	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
				defer cancel()
				rc, err := s.Dial(ctx, "tcp", target)
				if err != nil {
					log.Printf("tsnetlib: dial %s failed: %v", target, err)
					return
				}
				defer rc.Close()
				done := make(chan struct{}, 2)
				go func() { _, _ = io.Copy(rc, c); done <- struct{}{} }()
				go func() { _, _ = io.Copy(c, rc); done <- struct{}{} }()
				<-done
			}(conn)
		}
	}()

	mu.Lock()
	proxies[localPort] = ln
	mu.Unlock()
	return localPort, nil
}

// StopProxy closes the proxy listener on the given local port.
func StopProxy(port int) error {
	mu.Lock()
	defer mu.Unlock()
	ln, ok := proxies[port]
	if !ok {
		return fmt.Errorf("no proxy on port %d", port)
	}
	delete(proxies, port)
	return ln.Close()
}

// Running reports whether the tunnel is currently up.
func Running() bool {
	mu.Lock()
	defer mu.Unlock()
	return srv != nil
}
