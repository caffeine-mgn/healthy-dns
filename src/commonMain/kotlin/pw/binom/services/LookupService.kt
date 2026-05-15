package pw.binom.services

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMap
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import pw.binom.dns.protocol.DnsClass
import pw.binom.dns.protocol.DnsHeader
import pw.binom.dns.protocol.DnsPackage
import pw.binom.dns.protocol.DnsType
import pw.binom.dns.protocol.Opcode
import pw.binom.dns.protocol.RCode
import pw.binom.dns.protocol.RData
import pw.binom.dns.protocol.Resource
import pw.binom.dns.protocol.utils.normalizedRdata

class LookupService(
    private val domainsServices: DomainsServices,
) {

    private val logger = KotlinLogging.logger { }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun query(record: DnsPackage): DnsPackage {
        val temporalRecords = record.queries
            .asFlow()
            .filter { it.clazz == DnsClass.IN }
            .flatMapConcat { query ->
                val records = temporalRecords[query.name]?.getRecords(query.type) ?: return@flatMapConcat emptyFlow()
                records.asFlow()
                    .map {
                        Resource(
                            name = query.name,
                            type = query.type,
                            clazz = DnsClass.IN,
                            ttl = it.ttl,
                            rdata = it.rdata,
                        )
                    }
            }

        val ans = record.queries
            .asFlow()
            .filter { it.clazz == DnsClass.IN }
            .filter { it.type == DnsType.A }
            .flatMapConcat { query ->
                logger.info { "Query ${query.name}" }
                domainsServices.findRecords(query.name)
                    .asFlow()
                    .map {
                        query.name to it
                    }
            }
            .map { (name, record) ->
                Resource(
                    name = name,
                    type = record.type,
                    clazz = DnsClass.IN,
                    ttl = record.ttl.inWholeSeconds.toUInt(),
                    rdata = record.content,
                )
            }

        return DnsPackage(
            answer = (ans.toList() + temporalRecords.toList()),
            queries = record.queries,
            authority = emptyList(),
            header = DnsHeader(
                id = record.header.id,
                rd = true,
                tc = false,
                aa = true,
                opcode = Opcode.QUERY,
                qr = true,
                ra = false,
                z = 0,
                rcode = RCode.NOERROR,
            ),
            additional = emptyList()
        )
    }

    private data class StoredRecord(
        val rdata: RData,
        val ttl: UInt,
    )

    private class TemporalRecord {
        // Map: Type -> List of records with data & ttl
        private val records = HashMap<DnsType, MutableList<StoredRecord>>()
        fun getRecords(type: DnsType): List<StoredRecord> = records[type] ?: emptyList()

        fun add(type: DnsType, rdata: RData, ttl: UInt) {
            val list = records.getOrPut(type) { mutableListOf() }
            // Проверяем на дубликаты (сравниваем только данные, как в вашем коде)
            if (list.none { it.rdata.subData.contentEquals(rdata.subData) }) {
                list.add(StoredRecord(rdata, ttl))
            }
        }

        fun removeExact(type: DnsType, rdata: RData) {
            val list = records[type] ?: return
            // Удаляем первое совпадение по данным
            val index = list.indexOfFirst { it.rdata.subData.contentEquals(rdata.subData) }
            if (index != -1) list.removeAt(index)

            // Опционально: удалить тип, если список стал пустым
            if (list.isEmpty()) records.remove(type)
        }

        fun removeAllOfType(type: DnsType) {
            records.remove(type)
        }

        fun removeAll() {
            records.clear()
        }

        // Метод для получения записей (для обработки обычных DNS запросов)
        fun get(type: DnsType): List<StoredRecord> {
            return records[type]?.toList() ?: emptyList()
        }

        fun isEmpty() = records.isEmpty()
    }

    private val temporalRecords = HashMap<String, TemporalRecord>()

    private fun addOrReplace(domain: String, resource: Resource) {
        logger.info { "Add ${resource.type} to ${resource.name}" }
        val record = temporalRecords.getOrPut(resource.name) { TemporalRecord() }
        record.add(resource.type, resource.normalizedRdata()!!, resource.ttl)
    }

    private fun removeExact(domain: String, resource: Resource) {
        logger.info { "Remove ${resource.type} from ${resource.name}" }
        val record = temporalRecords[resource.name] ?: return
        record.removeExact(resource.type, resource.normalizedRdata()!!)
    }

    private fun removeAllOfType(domain: String, resource: Resource) {
        logger.info { "Remove ALL ${resource.type} from ${resource.name}" }
        val record = temporalRecords[resource.name] ?: return
        record.removeAllOfType(resource.type)
    }

    private fun removeAllOfName(domain: String, resource: Resource) {
        logger.info { "Remove ALL from ${resource.name}" }
        temporalRecords.remove(resource.name)
    }

    suspend fun update(record: DnsPackage): DnsPackage {
        val soeRequest = record.queries.find { it.type == DnsType.SOA }
            ?: return record.makeRefused()
        val domain = soeRequest.name
        record.authority.forEach { resource ->
            when {
                resource.clazz == DnsClass.IN && resource.ttl > 0u -> addOrReplace(domain, resource)
                resource.clazz == DnsClass.IN && resource.ttl == 0u -> removeExact(domain, resource)
                resource.clazz == DnsClass.NONE && resource.ttl == 0u -> removeAllOfType(domain, resource)
                resource.clazz == DnsClass.ANY && resource.ttl == 0u -> removeAllOfName(domain, resource)
            }
        }
        return DnsPackage(
            header = DnsHeader(
                id = record.header.id,
                rd = true,
                tc = false,
                aa = true,
                opcode = Opcode.UPDATE,
                qr = true,
                ra = false,
                z = 0,
                rcode = RCode.NOERROR,
            ),
            queries = record.queries,
            answer = emptyList(),
            authority = emptyList(),
            additional = emptyList()
        )
    }

    private fun DnsPackage.makeRefused() = DnsPackage(
        header = DnsHeader(
            id = header.id,
            rd = true,
            tc = false,
            aa = true,
            opcode = header.opcode,
            qr = true,
            ra = false,
            z = 0,
            rcode = RCode.REFUSED,
        ),
        queries = emptyList(),
        answer = emptyList(),
        authority = emptyList(),
        additional = emptyList()
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun lookup(record: DnsPackage): DnsPackage {
        return when (record.header.opcode) {
            Opcode.QUERY -> query(record)
            Opcode.UPDATE -> update(record)
            else -> record.makeRefused()
        }
    }
}