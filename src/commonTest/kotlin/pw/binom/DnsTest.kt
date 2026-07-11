package pw.binom

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import pw.binom.dns.protocol.*
import pw.binom.utils.makeRefused
import pw.binom.utils.request
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import pw.binom.utils.isWildcardMatch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DnsTest {

    private fun DnsPackage.makeServerFail() = DnsPackage(
        header = DnsHeader(
            id = header.id,
            rd = true,
            tc = false,
            aa = true,
            opcode = header.opcode,
            qr = true,
            ra = false,
            z = 0,
            rcode = RCode.SERVFAIL,
        ),
        queries = emptyList(),
        answer = emptyList(),
        authority = emptyList(),
        additional = emptyList()
    )

    // ========================
    // DnsHandle.withTimeout
    // ========================

    @Test
    fun `withTimeout returns result when handler completes in time`() = runTest {
        val handle = DnsHandle { pack: DnsPackage ->
            pack  // echo back
        }.withTimeout(5.seconds)

        val request = DnsPackage.request("example.com")
        val result = handle.lookup(request)
        assertEquals(request.header.id, result.header.id)
    }

    @Test
    fun `withTimeout returns refused on timeout`() = runTest {
        val handle = DnsHandle { _: DnsPackage ->
            // Бесконечное ожидание — гарантированный таймаут
            kotlinx.coroutines.delay(10.seconds)
            throw IllegalStateException("Should not reach here")
        }.withTimeout(50.milliseconds)

        val request = DnsPackage.request("example.com")
        withTimeout(5.seconds) {
            val result = handle.lookup(request)
            assertEquals(RCode.REFUSED, result.header.rcode)
        }
    }

    @Test
    fun `withTimeout infinite does not wrap`() = runTest {
        val handle = DnsHandle { pack: DnsPackage ->
            pack
        }.withTimeout(kotlin.time.Duration.INFINITE)

        val request = DnsPackage.request("example.com")
        val result = handle.lookup(request)
        assertNotNull(result)
    }

    // ========================
    // DnsHandle.withRetry
    // ========================

    @Test
    fun `withRetry returns result on first success`() = runTest {
        var callCount = 0
        val handle = DnsHandle { pack: DnsPackage ->
            callCount++
            pack  // success
        }.withRetry(3)

        val request = DnsPackage.request("example.com")
        val result = handle.lookup(request)
        assertEquals(1, callCount)
        assertEquals(RCode.NOERROR, result.header.rcode)
    }

    @Test
    fun `withRetry retries on SERVFAIL only`() = runTest {
        var callCount = 0
        val handle = DnsHandle { pack: DnsPackage ->
            callCount++
            pack.makeServerFail()  // always SERVFAIL
        }.withRetry(3)

        val request = DnsPackage.request("example.com")
        val result = handle.lookup(request)
        // 3 retries exhausted + final makeRefused
        assertEquals(3, callCount)
        assertEquals(RCode.REFUSED, result.header.rcode)
    }

    @Test
    fun `withRetry succeeds after retries`() = runTest {
        var callCount = 0
        val handle = DnsHandle { pack: DnsPackage ->
            callCount++
            if (callCount < 3) {
                pack.makeServerFail()
            } else {
                pack  // success on 3rd attempt
            }
        }.withRetry(5)

        val request = DnsPackage.request("example.com")
        val result = handle.lookup(request)
        assertEquals(3, callCount)
        assertEquals(RCode.NOERROR, result.header.rcode)
    }

    @Test
    fun `withRetry zero count returns original handler`() = runTest {
        var callCount = 0
        val original = DnsHandle { pack: DnsPackage ->
            callCount++
            pack
        }
        val handle = original.withRetry(0)

        val request = DnsPackage.request("example.com")
        handle.lookup(request)
        assertEquals(1, callCount)
    }

    @Test
    fun `withRetry negative count returns original handler`() = runTest {
        var callCount = 0
        val original = DnsHandle { pack: DnsPackage ->
            callCount++
            pack
        }
        val handle = original.withRetry(-1)

        val request = DnsPackage.request("example.com")
        handle.lookup(request)
        assertEquals(1, callCount)
    }

    // ========================
    // DomainTree
    // ========================

    @Test
    fun `domainTree getOrPut stores and retrieves`() {
        val tree = DomainTree<String>()
        val path = tree.getOrPut("example.com")
        path.value = "test"
        assertEquals("test", tree.get(listOf("example", "com"))?.value)
    }

    @Test
    fun `domainTree get returns null for missing domain`() {
        val tree = DomainTree<String>()
        assertNull(tree.get(listOf("missing", "com")))
    }

    @Test
    fun `domainTree get returns wildcard match`() {
        val tree = DomainTree<String>()
        tree.getOrPut("*.com").value = "wildcard"
        assertEquals("wildcard", tree.get(listOf("example", "com"))?.value)
    }

    @Test
    fun `domainTree get prefers exact match over wildcard`() {
        val tree = DomainTree<String>()
        tree.getOrPut("*.com").value = "wildcard"
        tree.getOrPut("example.com").value = "exact"
        assertEquals("exact", tree.get(listOf("example", "com"))?.value)
    }

    @Test
    fun `domainTree multi-level domain`() {
        val tree = DomainTree<String>()
        tree.getOrPut("sub.example.com").value = "found"
        assertEquals("found", tree.get(listOf("sub", "example", "com"))?.value)
        assertNull(tree.get(listOf("other", "example", "com")))
    }

    // ========================
    // DnsPackage utils
    // ========================

    @Test
    fun `makeRefused preserves header id`() {
        val request = DnsPackage.request("example.com")
        val refused = request.makeRefused()
        assertEquals(request.header.id, refused.header.id)
        assertEquals(RCode.REFUSED, refused.header.rcode)
        assertTrue(refused.header.qr)  // response flag
    }

    @Test
    fun `request creates valid query`() {
        val request = DnsPackage.request("example.com", listOf(DnsType.A))
        assertEquals(1, request.queries.size)
        assertEquals("example.com", request.queries.first().name)
        assertEquals(DnsType.A, request.queries.first().type)
        assertFalse(request.header.qr)  // query flag
    }

    @Test
    fun `request with custom types`() {
        val request = DnsPackage.request("example.com", listOf(DnsType.A, DnsType.AAAA))
        assertEquals(2, request.queries.size)
        assertEquals(DnsType.A, request.queries[0].type)
        assertEquals(DnsType.AAAA, request.queries[1].type)
    }

    // ========================
    // Wildcard match
    // ========================

    @Test
    fun `wildcardMatch exact match`() {
        assertTrue("example.com".isWildcardMatch("example.com"))
    }

    @Test
    fun `wildcardMatch star`() {
        assertTrue("anything.example.com".isWildcardMatch("*.example.com"))
    }

    @Test
    fun `wildcardMatch double star`() {
        assertTrue("sub.anything.example.com".isWildcardMatch("*.*.example.com"))
    }

    @Test
    fun `wildcardMatch no match`() {
        assertFalse("other.com".isWildcardMatch("*.example.com"))
    }
}
