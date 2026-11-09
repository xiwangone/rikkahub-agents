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
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"tailscale.com/ipn"
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
// acceptRoutes: accept subnet routes advertised by peers. Tailscale defaults this
// to true; the app passes its own setting so the tunnel never starts by pulling a
// peer LAN in and then correcting itself.
// exitNode: tailnet IP of the peer to use as exit node, empty for none. Same
// reason: applied before the first status report so the UI never shows drift.
// advertiseRoutes: comma separated CIDRs this node advertises to the tailnet
// (e.g. "192.168.1.0/24,10.0.0.0/24"), empty for none. Advertising only tells the
// control panel what this node offers; peers still need the route enabled there.
// stateDir: local state directory for tailscaled.state; caller decides the location.
//
// Returns the node IP inside the tailnet (for example 100.64.0.5).
func Start(controlURL, authKey, hostname string, acceptRoutes bool, exitNode, advertiseRoutes, stateDir string) (string, error) {
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
	// Apply before Up: EditPrefs may fail while the backend is still starting, the
	// second call after Up makes it authoritative. Doing it here (rather than from
	// the app right after Start returns) means no window where RouteAll is on.
	_ = applyStartPrefs(s, acceptRoutes, exitNode, advertiseRoutes)

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
	if err := applyStartPrefs(s, acceptRoutes, exitNode, advertiseRoutes); err != nil {
		log.Printf("apply start prefs: %v", err)
	}

	srv = s
	return st.TailscaleIPs[0].String(), nil
}

// applyStartPrefs applies subnet-route acceptance, the exit node and the routes
// this node advertises, all in one EditPrefs call. Tailscale defaults RouteAll to
// true, so leaving it unset silently pulls every advertised peer subnet in.
// Every field is set explicitly (including empty values) so that clearing it in
// the app really takes effect.
func applyStartPrefs(s *tsnet.Server, acceptRoutes bool, exitNode, advertiseRoutes string) error {
	lc, err := s.LocalClient()
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	mp := &ipn.MaskedPrefs{Prefs: ipn.Prefs{RouteAll: acceptRoutes}, RouteAllSet: true}

	mp.Prefs.ExitNodeIP = netip.Addr{}
	mp.ExitNodeIPSet = true
	if exitNode != "" {
		addr, perr := netip.ParseAddr(exitNode)
		if perr != nil {
			return fmt.Errorf("bad exit node %q: %w", exitNode, perr)
		}
		mp.Prefs.ExitNodeIP = addr
	}

	prefixes := make([]netip.Prefix, 0, 2)
	for _, raw := range strings.Split(advertiseRoutes, ",") {
		item := strings.TrimSpace(raw)
		if item == "" {
			continue
		}
		p, perr := netip.ParsePrefix(item)
		if perr != nil {
			return fmt.Errorf("bad route %q: %w", item, perr)
		}
		prefixes = append(prefixes, p.Masked())
	}
	mp.Prefs.AdvertiseRoutes = prefixes
	mp.AdvertiseRoutesSet = true

	_, err = lc.EditPrefs(ctx, mp)
	return err
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

// PeerStatus describes one tailnet peer for the app side.
type peerStatus struct {
	HostName   string   `json:"hostName"`
	DNSName    string   `json:"dnsName,omitempty"`
	OS         string   `json:"os,omitempty"`
	IPs        []string `json:"ips,omitempty"`
	Online     bool     `json:"online"`
	ExitNode   bool     `json:"exitNode,omitempty"`
	ExitOption bool     `json:"exitNodeOption,omitempty"`
}

// StatusJSON reports the node state plus the peer list; it never fails, errors
// go into the "error" field. The app uses it to render the device list and the
// current accept-routes / exit-node selection.
func StatusJSON() string {
	mu.Lock()
	s := srv
	mu.Unlock()
	out := map[string]any{"running": false}
	if s == nil {
		return marshalJSON(out)
	}
	lc, err := s.LocalClient()
	if err != nil {
		out["error"] = err.Error()
		return marshalJSON(out)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	st, err := lc.Status(ctx)
	if err != nil {
		out["error"] = err.Error()
		return marshalJSON(out)
	}
	out["running"] = st.BackendState == "Running"
	out["backendState"] = st.BackendState
	if st.Self != nil {
		out["dnsName"] = st.Self.DNSName
		out["hostName"] = st.Self.HostName
		ips := make([]string, 0, len(st.Self.TailscaleIPs))
		for _, ip := range st.Self.TailscaleIPs {
			ips = append(ips, ip.String())
		}
		out["tailscaleIPs"] = ips
	}
	peers := make([]peerStatus, 0, len(st.Peer))
	for _, p := range st.Peer {
		item := peerStatus{
			HostName:   p.HostName,
			DNSName:    p.DNSName,
			OS:         p.OS,
			Online:     p.Online,
			ExitNode:   p.ExitNode,
			ExitOption: p.ExitNodeOption,
		}
		for _, ip := range p.TailscaleIPs {
			item.IPs = append(item.IPs, ip.String())
		}
		peers = append(peers, item)
	}
	out["peers"] = peers
	if prefs, perr := lc.GetPrefs(ctx); perr == nil {
		out["acceptRoutes"] = prefs.RouteAll
		out["exitNodeIP"] = prefs.ExitNodeIP.String()
	}
	return marshalJSON(out)
}

// SetAcceptRoutes turns acceptance of peers' subnet routes on or off
// (Prefs.RouteAll). Tailscale's default is ON, which can pull a peer's LAN
// into the tailnet; the app applies its own setting right after Start.
func SetAcceptRoutes(on bool) error {
	mu.Lock()
	s := srv
	mu.Unlock()
	if s == nil {
		return errors.New("tsnet: not started")
	}
	lc, err := s.LocalClient()
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_, err = lc.EditPrefs(ctx, &ipn.MaskedPrefs{Prefs: ipn.Prefs{RouteAll: on}, RouteAllSet: true})
	return err
}

// SetExitNode routes all non-tailnet traffic through the peer with the given
// Tailscale IP ("" turns it off). LAN access stays allowed.
func SetExitNode(ip string) error {
	mu.Lock()
	s := srv
	mu.Unlock()
	if s == nil {
		return errors.New("tsnet: not started")
	}
	lc, err := s.LocalClient()
	if err != nil {
		return err
	}
	mp := &ipn.MaskedPrefs{
		ExitNodeIPSet:            true,
		ExitNodeIDSet:            true,
		ExitNodeAllowLANAccessSet: true,
	}
	mp.ExitNodeAllowLANAccess = true
	if ip != "" {
		addr, perr := netip.ParseAddr(ip)
		if perr != nil {
			return perr
		}
		mp.ExitNodeIP = addr
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_, err = lc.EditPrefs(ctx, mp)
	return err
}

// Logout signs this node out of the tailnet (the control server invalidates the
// stored state), then stops it.
func Logout() error {
	mu.Lock()
	s := srv
	mu.Unlock()
	if s != nil {
		if lc, err := s.LocalClient(); err == nil {
			ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			_ = lc.Logout(ctx)
			cancel()
		}
	}
	return Stop()
}

// marshalJSON renders v as JSON, falling back to a fixed error object.
func marshalJSON(v any) string {
	b, err := json.Marshal(v)
	if err != nil {
		return `{"error":"marshal failed"}`
	}
	return string(b)
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
