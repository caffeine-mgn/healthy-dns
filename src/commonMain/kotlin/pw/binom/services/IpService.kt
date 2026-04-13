package pw.binom.services

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import kotlinx.coroutines.*
import pw.binom.utils.HostName
import pw.binom.utils.synchronize
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration

@OptIn(DelicateCoroutinesApi::class, ExperimentalAtomicApi::class)
class IpService(
    private val httpClient: HttpClient,
    private val networkManager: SelectorManager,
) {
    sealed interface Checker {
        data class Http(
            val interval: Duration,
            val method: HttpMethod,
            val url: Url,
            val timeout: Duration,
            val responseCode: Int = 200,
        ) : Checker

        data class Tcp(
            val address: InetSocketAddress,
            val interval: Duration,
            val timeout: Duration,
        ) : Checker
    }

    interface Ip {
        val healthy: Boolean
    }

    private interface CheckerImpl : AutoCloseable {
        val healthy: Boolean
        fun start(context: CoroutineContext = EmptyCoroutineContext)
    }

    @OptIn(ExperimentalAtomicApi::class)
    private abstract class AbstractChecker : CheckerImpl {
        protected abstract val checkInternal: Duration
        protected abstract val timeout: Duration
        private var job: Job? = null
        protected abstract suspend fun isHealthy(): Boolean
        private val internalHealthy = AtomicBoolean(false)

        final override val healthy: Boolean
            get() = internalHealthy.load()

        final override fun start(context: CoroutineContext) {
            job = CoroutineScope(context).launch {
                while (isActive) {
                    val h = try {
                        withTimeout(timeout) {
                            isHealthy()
                        }
                    } catch (_: Exception) {
                        false
                    }
                    internalHealthy.store(h)
                    delay(checkInternal)
                }
            }
        }

        override fun close() {
            job?.cancel()
        }
    }

    private class TcpChecker(
        val data: Checker.Tcp,
        val networkManager: SelectorManager,
    ) : AbstractChecker() {
        override val checkInternal: Duration
            get() = data.interval
        override val timeout: Duration
            get() = data.timeout

        override suspend fun isHealthy(): Boolean {
            aSocket(networkManager).tcp().connect(data.address).awaitClosed()
            return true
        }
    }

    private class HttpChecker(
        val data: Checker.Http,
        val client: HttpClient,
    ) : AbstractChecker() {
        override val checkInternal: Duration
            get() = data.interval
        override val timeout: Duration
            get() = data.timeout

        override suspend fun isHealthy(): Boolean {
            val connection = try {
                client.request {
                    this.url(data.url)
                    this.method = data.method
                }
            } catch (e: Exception) {
                return false
            }
            return connection.status.value == data.responseCode
        }

    }

    private class IpHolderImpl(
        val address: HostName,
        val checker: CheckerImpl,
    ) : Ip {
        override val healthy: Boolean
            get() = checker.healthy
    }

    private val ips = HashMap<String, IpHolderImpl>()

    private val lock = AtomicBoolean(false)

    operator fun get(ip: HostName): Ip? = lock.synchronize { ips[ip.raw] }
    operator fun get(ip: String): Ip? = lock.synchronize { ips[ip] }

    fun addIp(ip: HostName, checker: Checker): Ip? {
        val impl = lock.synchronize {
            if (ips.containsKey(ip.raw)) {
                return null
            }
            val checkerImpl = when (checker) {
                is Checker.Tcp -> TcpChecker(data = checker, networkManager = networkManager)
                is Checker.Http -> HttpChecker(data = checker, client = httpClient)
            }
            val ipImpl = IpHolderImpl(address = ip, checkerImpl)
            ips[ip.host] = ipImpl
            ipImpl
        }
        impl.checker.start(context = Dispatchers.IO)
        return impl
    }

    fun removeIp(ip: HostName): Boolean {
        val ipImpl = lock.synchronize {
            ips.remove(ip.host) ?: return false
        }
        ipImpl.checker.close()
        return true
    }

    init {
        val actualIps = lock.synchronize {
            val result = HashSet(ips.values)
            ips.clear()
            result
        }
        actualIps.forEach {
            it.checker.close()
        }
    }
}