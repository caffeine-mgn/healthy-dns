package pw.binom.services

import kotlinx.coroutines.*
import pw.binom.concurrency.SpinLock
import pw.binom.concurrency.synchronize
import pw.binom.dns.*
import pw.binom.io.ByteBuffer
import pw.binom.io.socket.InetSocketAddress
import pw.binom.io.socket.UdpNetSocket
import pw.binom.io.use
import pw.binom.logger.Logger
import pw.binom.logger.info
import pw.binom.network.NetworkManager
import pw.binom.network.UdpConnection
import pw.binom.network.tcpConnect
import pw.binom.strong.BeanLifeCycle
import pw.binom.strong.inject
import kotlin.coroutines.resume
import kotlin.random.Random

@OptIn(DelicateCoroutinesApi::class)
class DnsClientService {
    private val networkManager by inject<NetworkManager>()

    private val waters = HashMap<Short, CancellableContinuation<DnsRecord>>()
    private val lock = SpinLock()

    private var job: Job? = null
    private var client: UdpConnection? = null
    private val logger by Logger.ofThisOrGlobal

    init {
        BeanLifeCycle.postConstruct {
            val client = networkManager.attach(UdpNetSocket())
            this.client = client
            job = GlobalScope.launch(networkManager) {
                ByteBuffer(1500).use { buffer ->
                    client.use { client ->
                        while (isActive) {
                            try {
                                buffer.clear()
                                val len = client.read(buffer, null)
                                if (len.isNotAvailable) {
                                    continue
                                }
                                buffer.flip()
                                val record = DnsRecord.read(buffer)
                                val water = lock.synchronize {
                                    waters.remove(record.header.id)
                                }
                                water?.resume(record)
                            } catch (e: CancellationException) {
                                break
                            } catch (e: Throwable) {
                                e.printStackTrace()
                            }
                        }
                    }
                }
            }
        }

        BeanLifeCycle.preDestroy {
            runCatching { job?.cancelAndJoin() }
        }
    }

    suspend fun sendRequestTcp(request: DnsRecord, server: InetSocketAddress): DnsRecord =
        ByteBuffer(1500).use { buf ->
            networkManager.tcpConnect(server).use { client ->
                request.write(client, buf)
                DnsRecord.read(client, buf)
            }
        }

    suspend fun sendRequest(request: DnsRecord, server: InetSocketAddress): DnsRecord {
        ByteBuffer(1500).use { buf ->
            val client = client
            if (client != null) {
                request.write(buf)
                buf.flip()
                client.write(buf, server)
                return suspendCancellableCoroutine { continuation ->
                    lock.synchronize {
                        waters[request.header.id] = continuation
                    }
                }
            } else {
                logger.info("Client not defined")
                throw IllegalStateException("Client not defined")
            }
        }
    }

    suspend fun sendRequest(name: String, server: InetSocketAddress): DnsRecord {
        val id = Random.nextInt().toShort()
        val record = DnsRecord(
            header = Header(
                id = id,
                rd = true,
                tc = false,
                aa = false,
                opcode = Opcode.QUERY,
                qr = false,
                ra = false,
                z = false,
                ad = true,
                cd = false,
                rcode = Rcode.NOERROR
            ),
            queries = listOf(Query(name = name, type = QType.A, clazz = QClass.IN)),
            ans = emptyList(),
            auth = emptyList(),
            add = listOf()
        )
        return sendRequest(request = record, server = server)
    }
}