package pw.binom.services

import kotlinx.coroutines.*
import pw.binom.Environment
import pw.binom.System
import pw.binom.dns.*
import pw.binom.io.ByteBuffer
import pw.binom.io.EOFException
import pw.binom.io.StreamClosedException
import pw.binom.io.socket.InetSocketAddress
import pw.binom.io.socket.MutableInetSocketAddress
import pw.binom.io.use
import pw.binom.logger.Logger
import pw.binom.logger.info
import pw.binom.network.NetworkManager
import pw.binom.network.SocketClosedException
import pw.binom.network.bindUdp
import pw.binom.properties.DnsServerProperty
import pw.binom.strong.BeanLifeCycle
import pw.binom.strong.inject
import pw.binom.strong.properties.injectProperty

@OptIn(ExperimentalStdlibApi::class, DelicateCoroutinesApi::class)
class DnsServerService {
    private val nm by inject<NetworkManager>()
    private val config by injectProperty<DnsServerProperty>()
    private val domainsServices by inject<DomainsServices>()
    private var jobUdp: Job? = null
    private var jobTcp: Job? = null
    private val logger by Logger.ofThisOrGlobal

    init {
        BeanLifeCycle.postConstruct {
            val server = nm.bindUdp(config.bind)
            val server2 = try {
                nm.bindTcp(config.bind)
            } catch (e: Throwable) {
                server.close()
                throw e
            }
            logger.info("Bind DNS server on ${config.bind.host}:${config.bind.port}}")
            jobTcp = GlobalScope.launch(nm) {
                server2.use { server ->
                    while (isActive) {
                        val newClient = server.accept()
                        GlobalScope.launch(nm) {
                            newClient.use { client ->
                                ByteBuffer(1500).use { buffer ->
                                    while (isActive) {
                                        try {
                                            val pack = DnsPackage.read(client, buffer)
                                            val income = pack.toImmutable()
                                            val outcome = processing(income)
                                            outcome.toMutable().write(client, buffer)
                                        } catch (e: EOFException) {
                                            break
                                        } catch (e: CancellationException) {
                                            break
                                        } catch (e: SocketClosedException) {
                                            break
                                        } catch (e: StreamClosedException) {
                                            break
                                        } catch (e: Throwable) {
                                            e.printStackTrace()
                                            break
                                        }
                                        System.gc()
                                    }
                                }

                            }
                        }
                    }
                }
            }
            jobUdp = GlobalScope.launch(nm) {
                server.use { server ->
                    ByteBuffer(config.packageSize).use { buffer ->
                        val address = MutableInetSocketAddress()
                        while (isActive) {
                            try {
                                buffer.clear()
                                val l = server.read(buffer, address)
                                if (l.isNotAvailable) {
                                    continue
                                }
                                buffer.flip()
                                val income = DnsRecord.read(buffer)
                                ByteBuffer(1500).use {
                                    income.write(it)
                                    it.flip()
                                }

                                val outcome = processing(income)
                                buffer.clear()
                                outcome.write(buffer)
                                buffer.flip()
                                server.write(buffer, address)
                            } catch (e: CancellationException) {
                                break
                            } catch (e: Throwable) {
                                e.printStackTrace()
                                break
                            }
                            System.gc()
                        }
                    }
                }
            }
        }
        BeanLifeCycle.preDestroy {
            runCatching { jobUdp?.cancelAndJoin() }
            runCatching { jobTcp?.cancelAndJoin() }
        }
    }

    private suspend fun processing(record: DnsRecord): DnsRecord {
//        val resp =
//            dnsClientService.sendRequestTcp(
//                request = record,
//                server = InetSocketAddress.resolve("192.168.76.101", 53)
//            )
//        return resp
        val ans = record.queries
            .asSequence()
            .filter { it.clazz == QClass.IN }
            .filter { it.type == QType.A }
            .map { query ->
                domainsServices.findRecords(query.name)
                    .asSequence()
                    .map {
                        query.name to it
                    }
            }.flatten()
            .map { (name, record) ->
                Resource(
                    name = name,
                    type = record.type,
                    clazz = QClass.IN,
                    ttl = record.ttl.inWholeSeconds.toUInt(),
                    rdata = record.content,
                )
            }
            .toList()

        return DnsRecord(
            ans = ans,
            queries = record.queries,
            auth = emptyList(),
            header = Header(
                id = record.header.id,
                rd = true,
                tc = false,
                aa = true,
                opcode = Opcode.QUERY,
                qr = true,
                ra = false,
                z = false,
                ad = false,
                cd = false,
                rcode = Rcode.NOERROR,
            ),
            add = listOf()
        )
    }
}