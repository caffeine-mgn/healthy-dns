package pw.binom.services

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import kotlinx.coroutines.*
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import pw.binom.DnsHandle
import pw.binom.dns.protocol.DnsPackage
import pw.binom.utils.lock
import pw.binom.utils.synchronize
import pw.binom.utils.unlock
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@OptIn(ExperimentalAtomicApi::class)
class DnsUdpClient(
    selectorManager: SelectorManager?,
) : AutoCloseable {
    private val selectorManager: SelectorManager
    private val closeManager: Boolean

    private val waters = HashMap<Pair<Short, SocketAddress>, CancellableContinuation<DnsPackage>>()
    private val lock = AtomicBoolean(false)
    private var client: BoundDatagramSocket? = null

    init {
        if (selectorManager != null) {
            this.selectorManager = selectorManager
            closeManager = false
        } else {
            this.selectorManager = SelectorManager()
            closeManager = true
        }
    }

    private var job = this.selectorManager.launch(start = CoroutineStart.LAZY) {
        aSocket(this@DnsUdpClient.selectorManager).udp().bind().use { client ->
            try {
                this@DnsUdpClient.client = client
                while (isActive) {
                    val d = client.receive()
                    val bytes = d.packet.readByteArray()
                    val record = DnsPackage.read(bytes)
                    val water = lock.synchronize {
                        waters.remove(record.header.id to d.address)
                    }
                    water?.resume(record)
                }
            } finally {
                this@DnsUdpClient.client = null
            }
        }
    }

    init {
        if (closeManager) {
            job.invokeOnCompletion {
                this.selectorManager.close()
            }
        }
        job.start()
    }

    suspend fun join() {
        job.join()
    }

    override fun close() {
        job.cancel()
    }

    suspend fun lookup(record: DnsPackage, server: SocketAddress): DnsPackage {
        check(!job.isCancelled) { "DnsClient is closed" }
        val client = client
        checkNotNull(client) { "Client not ready" }
        val buffer = Buffer()
        record.write(buffer)
        lock.lock()
        try {
            client.send(Datagram(buffer, server))
        } catch (e: Throwable) {
            lock.unlock()
            throw e
        }
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                lock.synchronize {
                    waters.remove(record.header.id to server)
                }
            }
            try {
                waters[record.header.id to server] = continuation
            } catch (e: Throwable) {
                continuation.resumeWithException(e)
            } finally {
                lock.unlock()
            }

        }
    }
}