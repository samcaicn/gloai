package com.wangzan101.mika.core.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalHostsTest {
    @Test fun localAndLanHosts() {
        listOf(
            "localhost", "LOCALHOST", "api.localhost", "127.0.0.1", "127.8.9.10", "::1", "[::1]",
            "10.0.0.5", "172.16.0.1", "172.31.255.255", "192.168.1.10", "169.254.3.4", "nas.local", "nas.local.",
            "fd00::1", "fc12:3456::1", "fe80::1",
        ).forEach { assertTrue(it, LocalHosts.isLocal(it)) }
    }

    @Test fun publicHosts() {
        listOf(
            "example.com", "8.8.8.8", "172.15.0.1", "172.32.0.1", "192.169.1.1", "11.0.0.1", "169.255.0.1",
            "2001:db8::1", "localhost.example.com", "local", "10.0.0", "10.0.0.256", "", "1.2.3.4.5",
        ).forEach { assertFalse(it, LocalHosts.isLocal(it)) }
    }

    @Test fun onlyPlainHttpToNonLocalHostsIsFlagged() {
        assertTrue(LocalHosts.unencryptedRemote("http://example.com/v1"))
        assertTrue(LocalHosts.unencryptedRemote("http://203.0.113.7:8000/v1"))
        assertFalse(LocalHosts.unencryptedRemote("https://example.com/v1"))
        assertFalse(LocalHosts.unencryptedRemote("http://192.168.1.10:11434/v1"))
        assertFalse(LocalHosts.unencryptedRemote("http://localhost:1234/v1"))
        assertFalse(LocalHosts.unencryptedRemote("http://[::1]:8080/v1"))
        assertFalse(LocalHosts.unencryptedRemote("http://nas.local:11434"))
        assertFalse("not a URL", LocalHosts.unencryptedRemote("example.com"))
    }

    @Test fun ipv4MappedAndExpandedLoopbackAreLocal() {
        listOf(
            "::ffff:127.0.0.1", "[::ffff:127.0.0.1]", "::ffff:10.1.2.3",
            "::ffff:192.168.1.5", "0:0:0:0:0:0:0:1",
        ).forEach { assertTrue(it, LocalHosts.isLocal(it)) }
        assertFalse(LocalHosts.isLocal("::ffff:8.8.8.8"))
        assertFalse(LocalHosts.isLocal("::ffff:203.0.113.7"))
        assertFalse(LocalHosts.unencryptedRemote("http://[::ffff:127.0.0.1]:8080/v1"))
        assertFalse(LocalHosts.unencryptedRemote("http://[0:0:0:0:0:0:0:1]:8080/v1"))
        assertTrue(LocalHosts.unencryptedRemote("http://[::ffff:8.8.8.8]:8080/v1"))
    }

    @Test fun cgnatIsPrivateOrMeshButNotLocal() {
        // 组网工具(Tailscale 等)常用的 100.64/10:对「明文 http 可不可接受」按私有网段看,
        // 但严格意义上不是本机/局域网,所以 isLocal 不认、isPrivateOrMesh 认。
        listOf(
            "100.64.0.1", "100.100.1.2", "100.127.255.254", "::ffff:100.64.0.1",
        ).forEach { assertTrue(it, LocalHosts.isPrivateOrMesh(it)) }
        listOf(
            "100.64.0.1", "100.100.1.2", "100.127.255.254",
        ).forEach { assertFalse(it, LocalHosts.isLocal(it)) }
        assertFalse(LocalHosts.isPrivateOrMesh("100.63.255.255"))
        assertFalse(LocalHosts.isPrivateOrMesh("100.128.0.0"))
        assertFalse(LocalHosts.isPrivateOrMesh("203.0.113.7"))
        assertFalse(LocalHosts.isPrivateOrMesh("::ffff:8.8.8.8"))
        // 本机、局域网地址它也照认
        assertTrue(LocalHosts.isPrivateOrMesh("192.168.1.10"))
        assertTrue(LocalHosts.isPrivateOrMesh("localhost"))
    }
}
