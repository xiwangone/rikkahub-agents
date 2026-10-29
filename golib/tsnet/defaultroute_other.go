//go:build !android

package tsnetlib

// setDefaultRouteInterface is a no-op off Android: every other platform lets
// the monitor read the routing table itself.
func setDefaultRouteInterface(name string) {}
