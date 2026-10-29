//go:build android

// This file exists because the hook that stores the default route interface is
// only part of the android build of the network monitor. Keeping the call here
// lets the rest of the package compile for every platform.

package tsnetlib

import "tailscale.com/net/netmon"

// setDefaultRouteInterface hands the interface name reported by the app's
// connectivity layer to the network monitor. Empty name means no network.
func setDefaultRouteInterface(name string) {
	netmon.UpdateLastKnownDefaultRouteInterface(name)
}
