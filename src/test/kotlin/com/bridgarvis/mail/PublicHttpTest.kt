package com.bridgarvis.mail

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PublicHttpTest {
    private val dns = mapOf(
        "public.example.com" to "93.184.215.14",
        "metadata.example.com" to "169.254.169.254",
        "vpc.example.com" to "10.20.1.5",
        "loop.example.com" to "127.0.0.1",
        "cgnat.example.com" to "100.64.0.1",
        "v6local.example.com" to "fd00::1",
        "mapped.example.com" to "::ffff:10.0.0.1",
    )
    private val http = PublicHttp(resolve = { host -> listOf(InetAddress.getByName(dns[host] ?: error("unknown"))) })

    @Test
    fun `only public http(s) hosts on default ports are allowed`() {
        assertEquals("public.example.com", http.check("https://public.example.com/u/abc~~").host)
        assertEquals("public.example.com", http.check("http://public.example.com:443/x").host)
        listOf(
            "https://metadata.example.com/latest/meta-data/", "https://vpc.example.com/", "http://loop.example.com/",
            "https://cgnat.example.com/", "https://v6local.example.com/", "https://mapped.example.com/",
            "https://public.example.com:8443/", "ftp://public.example.com/", "file:///etc/passwd",
            "https://user:pw@public.example.com/", "https://localhost/", "https://nope.example.com/", "not a url",
        ).forEach { url -> assertFailsWith<RefusedUrlException>(url) { http.check(url) } }
    }

    @Test
    fun `address classification`() {
        assertTrue(PublicHttp.isPublic(InetAddress.getByName("8.8.8.8")))
        assertTrue(PublicHttp.isPublic(InetAddress.getByName("2606:4700::1111")))
        listOf("0.0.0.0", "127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.100.1.1",
            "224.0.0.1", "255.255.255.255", "::1", "fe80::1", "fc00::1", "::ffff:127.0.0.1")
            .forEach { assertFalse(PublicHttp.isPublic(InetAddress.getByName(it)), it) }
    }
}
