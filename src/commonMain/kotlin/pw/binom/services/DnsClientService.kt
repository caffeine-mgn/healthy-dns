package pw.binom.services

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import kotlinx.coroutines.*
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import pw.binom.DnsHandle
import pw.binom.dns.protocol.*
import pw.binom.utils.synchronize
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.resume
import kotlin.random.Random

@OptIn(DelicateCoroutinesApi::class, ExperimentalAtomicApi::class)
class DnsClientService(
    private val selector: SelectorManager
) : AutoCloseable {
    private val waters = HashMap<Short, CancellableContinuation<DnsPackage>>()
    private val lock = AtomicBoolean(false)

    private var client: BoundDatagramSocket? = null
    private val job2 = CoroutineScope(Dispatchers.IO).launch {
        aSocket(selector).udp().bind().use { client ->
            this@DnsClientService.client = client
            try {
                val bytes = client.receive().packet.readByteArray()
                val record = DnsPackage.read(bytes)
                val water = lock.synchronize {
                    waters.remove(record.header.id)
                }
                water?.resume(record)
            } finally {
                this@DnsClientService.client = null
            }
        }
    }


    override fun close() {
        job2.cancel()
        lock.synchronize {
            waters.clear()
        }
    }

    suspend fun sendRequest(request: DnsPackage, server: InetSocketAddress): DnsPackage {
        check(!job2.isCancelled) { "DnsClient is closed" }
        val client = client
        checkNotNull(client) { "Client not ready" }
        val buffer = Buffer()
        request.write(buffer)
        client.send(Datagram(buffer, server))
        return suspendCancellableCoroutine { continuation ->
            lock.synchronize {
                waters[request.header.id] = continuation
            }
        }
    }

    suspend fun sendRequest(name: String, server: InetSocketAddress): DnsPackage {
        val id = Random.nextInt().toShort()

        val record = DnsPackage(
            header = DnsHeader(
                id = id,
                qr = false,
                opcode = Opcode.QUERY,
                aa = false,
                tc = false,
                rd = true,
                ra = false,
                z = 0,
                rcode = RCode.NOERROR
//                ad = true,
//                cd = false,

            ),
            queries = listOf(Question(name = name, type = DnsType.A, clazz = DnsClass.IN)),
            answer = emptyList(),
            authority = emptyList(),
            additional = listOf()
        )
        return sendRequest(request = record, server = server)
    }
}