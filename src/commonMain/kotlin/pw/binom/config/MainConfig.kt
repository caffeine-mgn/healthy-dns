package pw.binom.config

import pw.binom.http.client.HttpClientRunnable
import pw.binom.http.client.factory.Http11ConnectionFactory
import pw.binom.http.client.factory.Https11ConnectionFactory
import pw.binom.http.client.factory.NativeNetChannelFactory
import pw.binom.network.NetworkManager
import pw.binom.services.*
import pw.binom.strong.Strong
import pw.binom.strong.bean
import pw.binom.strong.properties.StrongProperties

fun MainConfig(
    nm: NetworkManager,
    strongProperties: StrongProperties,
) = Strong.config {
    it.bean { nm }
    it.bean { strongProperties }
    it.bean { DnsServerService() }
    it.bean { IpService() }
    it.bean { EchoService() }
//    it.bean { ForwardingUDPService() }
    it.bean { DnsClientService() }
    it.bean { DomainsServices() }
    it.bean {
        HttpClientRunnable(
            factory = Https11ConnectionFactory(fallback = Http11ConnectionFactory()),
            source = NativeNetChannelFactory(nm)
        )
//        HttpClient.create(networkDispatcher = nm)
    }
}