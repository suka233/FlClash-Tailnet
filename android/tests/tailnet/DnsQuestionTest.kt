package com.follow.clash.tailnet

import org.junit.Assert.*
import org.junit.Test

class DnsQuestionTest {
    private fun question() = byteArrayOf(0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 4, 112, 101, 101, 114, 2, 116, 115, 3, 110, 101, 116, 0, 0, 1, 0, 1)
    @Test fun decodesOrdinaryQuestion() {
        assertEquals("peer.ts.net." to "A", DnsQuestion.decode(question()))
    }
    @Test fun rejectsTruncatedAndCompressedNames() {
        assertTrue(runCatching { DnsQuestion.decode(question().copyOf(14)) }.isFailure)
        assertTrue(runCatching { DnsQuestion.decode(question().also { it[12] = 0xc0.toByte() }) }.isFailure)
    }
    @Test fun rejectsNonInternetClass() {
        assertTrue(runCatching { DnsQuestion.decode(question().also { it[it.lastIndex] = 3 }) }.isFailure)
    }
    @Test fun failureRetainsTransactionAndReturnsServfail() {
        val response = DnsQuestion.failure(question())
        assertEquals(0x12, response[0].toInt()); assertEquals(0x34, response[1].toInt())
        assertEquals(2, response[3].toInt() and 15)
        assertEquals(12, response.size)
    }
}
