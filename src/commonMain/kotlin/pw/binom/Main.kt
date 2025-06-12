package pw.binom

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import pw.binom.config.MainConfig
import pw.binom.io.file.readText
import pw.binom.io.file.takeIfFile
import pw.binom.io.file.workDirectoryFile
import pw.binom.io.use
import pw.binom.network.MultiFixedSizeThreadNetworkDispatcher
import pw.binom.properties.ini.addIni
import pw.binom.signal.Signal
import pw.binom.strong.Strong
import pw.binom.strong.properties.BaseStrongProperties
import pw.binom.strong.properties.StrongProperties
import pw.binom.strong.properties.yaml.addYaml

@OptIn(DelicateCoroutinesApi::class)
fun main(args: Array<String>) {
//    InternalLog.replace {
//        object : InternalLog {
//            override val enabled: Boolean
//                get() = true
//
//            override fun log(level: InternalLog.Level, file: String?, line: Int?, method: String?, text: () -> String) {
//                println("$level $file $line: ${text()}")
//            }
//
//            override fun <T> tx(func: (InternalLog.Transaction) -> T): T =
//                func(InternalLog.Transaction.NULL)
//        }
//    }
    runBlocking {
        val properties = BaseStrongProperties()
        properties.addArgs(args)
        properties.addEnvironment()
        Environment.workDirectoryFile.relative("config.yaml")
            .takeIfFile()
            ?.readText()
            ?.let {
                properties.addYaml(it)
            }

        Environment.workDirectoryFile.relative("config.ini")
            .takeIfFile()
            ?.readText()
            ?.let {
                properties.addIni(it)
            }

        MultiFixedSizeThreadNetworkDispatcher(Environment.availableProcessors).use { nm ->
            val strong = Strong.create(MainConfig(nm = nm, strongProperties = properties))
            Signal.handler {
                println("Signal got! $it")
                if (it.isInterrupted) {
                    GlobalScope.launch {
                        strong.destroy()
                    }
                }
            }
            strong.awaitDestroy()
        }
    }

}