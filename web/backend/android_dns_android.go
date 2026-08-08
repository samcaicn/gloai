//go:build android

package main

import (
	"context"
	"log"
	"net"
	"time"
)

// androidDNSServers are public DNS resolvers used on Android, where the pure
// Go resolver cannot read /etc/resolv.conf (Android has none) and falls back
// to the built-in defaultNS (127.0.0.1:53 / [::1]:53), which does not exist on
// the device. Without this, every outbound hostname lookup fails.
var androidDNSServers = []string{
	"223.5.5.5:53",   // AliDNS
	"119.29.29.29:53", // DNSPod
	"114.114.114.114:53", // 114DNS
	"8.8.8.8:53",
}

// fixAndroidDNS installs a custom net.Resolver that dials public DNS servers
// directly, bypassing the broken defaultNS fallback on Android.
func fixAndroidDNS() {
	dial := func(ctx context.Context, _network, _address string) (net.Conn, error) {
		d := net.Dialer{Timeout: 3 * time.Second}
		var lastErr error
		for _, server := range androidDNSServers {
			conn, err := d.DialContext(ctx, "udp", server)
			if err == nil {
				return conn, nil
			}
			lastErr = err
		}
		return nil, lastErr
	}
	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial:     dial,
	}
	log.Println("android dns: installed public DNS resolver")
}
