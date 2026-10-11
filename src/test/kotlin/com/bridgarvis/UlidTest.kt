package com.bridgarvis

import com.bridgarvis.message.Ulid
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class UlidTest {
    @Test
    fun `ulids are 26 chars, sortable by time and deterministic per seed`() {
        val t1 = Instant.parse("2026-10-11T10:00:00Z")
        val t2 = t1.plusMillis(1)
        val a = Ulid.deterministic(t1, "x")
        assertEquals(26, a.length)
        assertTrue(Regex("^[0-9A-HJKMNP-TV-Z]{26}$").matches(a))
        assertEquals(a, Ulid.deterministic(t1, "x"))
        assertNotEquals(a, Ulid.deterministic(t1, "y"))
        assertTrue(a < Ulid.deterministic(t2, "a"))
        assertTrue(Ulid.random(t1) < Ulid.random(t2))
    }
}
