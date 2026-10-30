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
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/netip"
	"os"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"tailscale.com/net/netmon"
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

	// The logging policy layer aborts the whole process when it cannot find a
	// writable place for its log state, which is the case inside an app sandbox.
	// Point it at the caller supplied state directory before the server starts.
	guardLogsDir(stateDir)

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

// ifaceSpec is one network interface as reported by the embedding app.
// The app must enumerate its own interfaces (on Android the kernel refuses
// netlink sockets for regular app UIDs) and hand the result over as JSON.
type ifaceSpec struct {
	Name      string `json:"name"`
	Index     int    `json:"index"`
	MTU       int    `json:"mtu"`
	Up        bool   `json:"up"`
	Loopback  bool   `json:"loopback"`
	P2P       bool   `json:"p2p"`
	Multicast bool   `json:"multicast"`
	Broadcast bool   `json:"broadcast"`
	// Addrs holds "address/prefixLength" strings, for example "10.0.0.5/24".
	Addrs []string `json:"addrs"`
}

var (
	ifaceMu       sync.Mutex
	ifaceGetterOn bool
	ifaceSnapshot atomic.Pointer[[]netmon.Interface]
)

// SetInterfaces installs the interface list used instead of netlink based
// discovery. interfacesJSON is a JSON array of objects with the fields
// name, index, mtu, up, loopback, p2p, multicast, broadcast and addrs.
//
// The first non empty call registers a snapshot reader with Tailscale's
// network monitor, so every later enumeration is served from that snapshot.
// An empty list is rejected: overwriting a good snapshot with nothing would
// make the monitor believe the device has no interface at all.
func SetInterfaces(interfacesJSON string) error {
	var specs []ifaceSpec
	if err := json.Unmarshal([]byte(interfacesJSON), &specs); err != nil {
		return fmt.Errorf("tsnetlib: interface list: %w", err)
	}
	if len(specs) == 0 {
		return errors.New("tsnetlib: interface list is empty, keeping previous snapshot")
	}

	list := make([]netmon.Interface, 0, len(specs))
	for _, sp := range specs {
		if sp.Name == "" {
			return errors.New("tsnetlib: interface list entry without name")
		}
		var flags net.Flags
		if sp.Up {
			flags |= net.FlagUp
		}
		if sp.Loopback {
			flags |= net.FlagLoopback
		}
		if sp.P2P {
			flags |= net.FlagPointToPoint
		}
		if sp.Multicast {
			flags |= net.FlagMulticast
		}
		if sp.Broadcast {
			flags |= net.FlagBroadcast
		}
		std := &net.Interface{
			Index: sp.Index,
			MTU:   sp.MTU,
			Name:  sp.Name,
			Flags: flags,
		}
		var addrs []net.Addr
		for _, a := range sp.Addrs {
			pfx, err := netip.ParsePrefix(a)
			if err != nil {
				log.Printf("tsnetlib: skipping bad address %q on %s: %v", a, sp.Name, err)
				continue
			}
			ip := pfx.Addr()
			addrs = append(addrs, &net.IPNet{
				IP:   ip.AsSlice(),
				Mask: net.CIDRMask(pfx.Bits(), ip.BitLen()),
			})
		}
		list = append(list, netmon.Interface{Interface: std, AltAddrs: addrs})
	}

	ifaceMu.Lock()
	defer ifaceMu.Unlock()
	ifaceSnapshot.Store(&list)
	if !ifaceGetterOn {
		netmon.RegisterInterfaceGetter(snapshotInterfaces)
		ifaceGetterOn = true
	}
	return nil
}

// snapshotInterfaces serves the stored list to the network monitor.
func snapshotInterfaces() ([]netmon.Interface, error) {
	list := ifaceSnapshot.Load()
	if list == nil {
		return nil, errors.New("tsnetlib: no interface snapshot yet")
	}
	return *list, nil
}

// SetDefaultRouteInterface tells the network monitor which interface carries
// the default route. An empty name means "no network right now".
func SetDefaultRouteInterface(name string) error {
	setDefaultRouteInterface(name)
	return nil
}

// NetworkChanged asks the network monitor to re-read the network state right
// away, instead of waiting for a change event it cannot receive on Android.
// It does nothing while the tunnel is not running.
func NetworkChanged() error {
	mu.Lock()
	s := srv
	mu.Unlock()
	if s == nil {
		return nil
	}
	sys := s.Sys()
	if sys == nil {
		return nil
	}
	mon, ok := sys.NetMon.GetOK()
	if !ok || mon == nil {
		return nil
	}
	mon.InjectEvent()
	return nil
}

// Status reports the node's live state to the app side: empty when the tunnel is
// not running, "connected <tailnet IP>" when the backend is up, "offline" otherwise.
// The Kotlin side polls this to notice a dropped connection, because its own flag
// only reflects start/stop calls and cannot see a backend that went down.
func Status() (string, error) {
	mu.Lock()
	s := srv
	mu.Unlock()
	if s == nil {
		return "", nil
	}
	lc, err := s.LocalClient()
	if err != nil {
		return "", fmt.Errorf("status: local client: %w", err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	st, err := lc.Status(ctx)
	if err != nil {
		return "", fmt.Errorf("status: %w", err)
	}
	if st == nil || st.BackendState != "Running" || st.Self == nil || len(st.Self.TailscaleIPs) == 0 {
		return "offline", nil
	}
	return "connected " + st.Self.TailscaleIPs[0].String(), nil
}

// guardLogsDir points the log policy at dir unless the caller already chose a
// directory. Failures are only logged: the environment keeps whatever the
// embedding app set up.
func guardLogsDir(dir string) {
	if dir == "" || os.Getenv("TS_LOGS_DIR") != "" {
		return
	}
	if err := os.MkdirAll(dir, 0700); err != nil {
		log.Printf("tsnetlib: logs dir %s is not usable: %v", dir, err)
		return
	}
	if err := os.Setenv("TS_LOGS_DIR", dir); err != nil {
		log.Printf("tsnetlib: set TS_LOGS_DIR: %v", err)
	}
}
