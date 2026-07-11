package pw.binom.services

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import pw.binom.dns.protocol.DnsPackage
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class DnsUdpClient(
    selectorManager: SelectorManager?,
) : AutoCloseable {
    private val selectorManager: SelectorManager
    private val closeManager: Boolean

    private val waters = HashMap<Pair<Short, SocketAddress>, CancellableContinuation<DnsPackage>>()
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
                    water?.resume(record)
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

        return suspendCancellableCoroutine { continuation ->
            // Шаг 1: регистрируем continuation ДО отправки (fix race condition)
            runBlocking {
                mutex.withLock {
                    waters[key] = continuation
                }
            }

            // Шаг 2: отправляем датаграмму ПОСЛЕ регистрации
            try {
                runBlocking {
                    client.send(Datagram(buffer, server))
                }
            } catch (e: Throwable) {
                // Отправка не удалась — чистим waters
                runBlocking {
                    mutex.withLock {
                        waters.remove(key)
                    }
                }
                continuation.resumeWithException(e)
                return@suspendCancellableCoroutine
            }

            // Шаг 3: обработка отмены (lock не захвачен — нет дедлока)
            continuation.invokeOnCancellation {
                runBlocking {
                    mutex.withLock {
                        waters.remove(key)
                    }
                }
            }
        }
    }
}
