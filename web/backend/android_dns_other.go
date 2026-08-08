//go:build !android

package main

// fixAndroidDNS is a no-op on non-Android platforms.
func fixAndroidDNS() {}
