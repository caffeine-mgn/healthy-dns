package pw.binom.services

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import pw.binom.dns.protocol.DnsPackage
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Исключение: downstream не ответил в пределах [DnsUdpClient.timeout].
 * Отличается от [TimeoutCancellationException] тем, что НЕ является отменой
 * текущей корутины — это ожидаемый сбой конкретного апстрима.
 */
class DownstreamTimeoutException(message: String) : Exception(message)

class DnsUdpClient(
    selectorManager: SelectorManager?,
    private val timeout: Duration = 3.seconds,
) : AutoCloseable {
    private val selectorManager: SelectorManager
    private val closeManager: Boolean

    private val waters = HashMap<Pair<Short, SocketAddress>, CompletableDeferred<DnsPackage>>()
    private val mutex = Mutex()
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
                    val water = mutex.withLock {
                        waters.remove(record.header.id to d.address)
                    }
                    water?.complete(record)
                }
            } finally {
                this@DnsUdpClient.client = null
                // Cancel all pending continuations on disconnect
                mutex.withLock {
                    waters.values.forEach { it.cancel() }
                    waters.clear()
                }
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

        val key = record.header.id to server
        val deferred = CompletableDeferred<DnsPackage>()

        // Шаг 1: регистрируем ожидание ДО отправки (fix race condition)
        mutex.withLock {
            waters[key] = deferred
        }

        // Шаг 2: отправляем датаграмму ПОСЛЕ регистрации
        try {
            client.send(Datagram(buffer, server))
        } catch (e: Throwable) {
            mutex.withLock {
                waters.remove(key)
            }
            deferred.cancel()
            throw e
        }

        // Шаг 3: ждём ответ, но не дольше собственного таймаута —
        // мёртвый/недоступный апстрим не должен вешать весь запрос.
        return try {
            withTimeout(timeout) {
                deferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            throw DownstreamTimeoutException("No response from $server within $timeout")
        } finally {
            mutex.withLock {
                waters.remove(key)
            }
            deferred.cancel()
        }
    }
}
