@file:OptIn(ExperimentalMcpApi::class)

package pw.binom.mcp

import io.modelcontextprotocol.kotlin.sdk.ExperimentalMcpApi
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import pw.binom.health.shared.model.*
import pw.binom.properties.DomainsProperty
import pw.binom.properties.WebServerProperty
import pw.binom.services.IpService
import pw.binom.utils.HostName
import io.ktor.http.Url
import io.ktor.network.sockets.InetSocketAddress
import kotlin.time.Duration.Companion.seconds

fun DomainsProperty.Record.toDto(): DnsRecordDto = DnsRecordDto(
    domain = domain,
    ips = ips,
    ttl = ttl.toString(),
    policy = policy.name,
    downstream = downStream?.let { DownstreamDto(ips = it.ips.map { a -> "${a.hostname}:${a.port}" }) },
)

fun DomainsProperty.HealthCheck.toDto(): HealthCheckDto = HealthCheckDto(
    http = http?.let { HttpCheckDto(url = it.url.toString(), method = it.method.value, responseCode = it.responseCode, interval = it.interval.toString(), timeout = it.timeout.toString()) },
    tcp = tcp?.let { TcpCheckDto(host = it.address.hostname, port = it.address.port, interval = it.interval.toString(), timeout = it.timeout.toString()) },
)

class DnsTools(
    private val config: WebServerProperty,
    private val domainsPropertyRef: () -> DomainsProperty,
    private val setDomainsProperty: suspend (DomainsProperty) -> Unit,
    private val ipService: IpService,
) {
    val server: Server = Server(
        serverInfo = Implementation(name = "powerdns-health", version = "1.0.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true))
        )
    )

    init {
        registerTools()
    }

    private fun registerTools() {
        // === dns_list_records ===
        server.addTool(
            name = "dns_list_records",
            description = "List all DNS records with their IPs, TTL, policy and downstream",
        ) {
            val props = domainsPropertyRef()
            val json = Json { prettyPrint = true }
            CallToolResult(content = listOf(TextContent(json.encodeToString(ListSerializer(DnsRecordDto.serializer()), props.records.map { it.toDto() }))))
        }

        // === dns_add_record ===
        server.addTool(
            name = "dns_add_record",
            description = "Add a new DNS record",
            inputSchema = ToolSchema(properties = buildJsonObject {
                put("domain", buildJsonObject { put("type", "string"); put("description", "Domain name (e.g. myapp.xx)") })
                put("ips", buildJsonObject { put("type", "string"); put("description", "Comma-separated IP addresses") })
                put("ttl", buildJsonObject { put("type", "string"); put("description", "TTL as ISO 8601 duration (e.g. PT30S)"); put("default", "PT30S") })
                put("policy", buildJsonObject { put("type", "string"); put("description", "ALL_HEALTHY, FIRST_HEALTHY or ROUND_ROBIN_HEALTHY"); put("default", "ALL_HEALTHY") })
                put("downstream", buildJsonObject { put("type", "string"); put("description", "Comma-separated downstream addresses (host:port). Optional.") })
            })
        ) { request ->
            val args = request.arguments ?: return@addTool CallToolResult(content = listOf(TextContent("Missing arguments")))
            val domain = args["domain"]?.jsonPrimitive?.contentOrNull ?: return@addTool CallToolResult(content = listOf(TextContent("Missing domain")))
            val props = domainsPropertyRef()
            if (props.records.any { it.domain == domain }) {
                return@addTool CallToolResult(content = listOf(TextContent("Record already exists: $domain")))
            }
            val ips = (args["ips"]?.jsonPrimitive?.contentOrNull ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val ttl = try { kotlin.time.Duration.parse(args["ttl"]?.jsonPrimitive?.contentOrNull ?: "PT30S") } catch (_: Exception) { 30.seconds }
            val policy = try { DomainsProperty.Policy.valueOf(args["policy"]?.jsonPrimitive?.contentOrNull ?: "ALL_HEALTHY") } catch (_: Exception) { DomainsProperty.Policy.ALL_HEALTHY }
            val downstream = args["downstream"]?.jsonPrimitive?.contentOrNull?.let { ds ->
                val addresses = ds.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                if (addresses.isEmpty()) null else DomainsProperty.Downstream(
                    ips = addresses.map { addr ->
                        val parts = addr.split(":")
                        InetSocketAddress(parts[0], (parts.getOrNull(1)?.toIntOrNull() ?: 53))
                    }
                )
            }
            val record = DomainsProperty.Record(domain = domain, ips = ips, ttl = ttl, policy = policy, downStream = downstream)
            setDomainsProperty(props.copy(records = props.records + record))
            CallToolResult(content = listOf(TextContent("Created record: $domain")))
        }

        // === dns_delete_record ===
        server.addTool(
            name = "dns_delete_record",
            description = "Delete a DNS record by domain",
            inputSchema = ToolSchema(properties = buildJsonObject {
                put("domain", buildJsonObject { put("type", "string"); put("description", "Domain name to delete") })
            })
        ) { request ->
            val args = request.arguments ?: return@addTool CallToolResult(content = listOf(TextContent("Missing arguments")))
            val domain = args["domain"]?.jsonPrimitive?.contentOrNull ?: return@addTool CallToolResult(content = listOf(TextContent("Missing domain")))
            val props = domainsPropertyRef()
            if (props.records.none { it.domain == domain }) {
                return@addTool CallToolResult(content = listOf(TextContent("Record not found: $domain")))
            }
            setDomainsProperty(props.copy(records = props.records.filter { it.domain != domain }))
            CallToolResult(content = listOf(TextContent("Deleted record: $domain")))
        }

        // === dns_update_record ===
        server.addTool(
            name = "dns_update_record",
            description = "Update existing DNS record fields (partial update)",
            inputSchema = ToolSchema(properties = buildJsonObject {
                put("domain", buildJsonObject { put("type", "string"); put("description", "Domain name to update") })
                put("ips", buildJsonObject { put("type", "string"); put("description", "Comma-separated IP addresses. Omit to keep current.") })
                put("ttl", buildJsonObject { put("type", "string"); put("description", "TTL as ISO 8601. Omit to keep current.") })
                put("policy", buildJsonObject { put("type", "string"); put("description", "ALL_HEALTHY / FIRST_HEALTHY / ROUND_ROBIN_HEALTHY. Omit to keep current.") })
                put("downstream", buildJsonObject { put("type", "string"); put("description", "Comma-separated downstream hosts. Empty string to remove. Omit to keep current.") })
            })
        ) { request ->
            val args = request.arguments ?: return@addTool CallToolResult(content = listOf(TextContent("Missing arguments")))
            val domain = args["domain"]?.jsonPrimitive?.contentOrNull ?: return@addTool CallToolResult(content = listOf(TextContent("Missing domain")))
            val props = domainsPropertyRef()
            val idx = props.records.indexOfFirst { it.domain == domain }
            if (idx == -1) return@addTool CallToolResult(content = listOf(TextContent("Record not found: $domain")))
            val existing = props.records[idx]
            val newIps = args["ips"]?.jsonPrimitive?.contentOrNull?.let { it.split(",").map { v -> v.trim() }.filter { v -> v.isNotEmpty() } } ?: existing.ips
            val newTtl = args["ttl"]?.jsonPrimitive?.contentOrNull?.let { try { kotlin.time.Duration.parse(it) } catch (_: Exception) { null } } ?: existing.ttl
            val newPolicy = args["policy"]?.jsonPrimitive?.contentOrNull?.let { try { DomainsProperty.Policy.valueOf(it) } catch (_: Exception) { null } } ?: existing.policy
            val newDownstream = when {
                args["downstream"] == null -> existing.downStream
                args["downstream"]?.jsonPrimitive?.contentOrNull?.isEmpty() == true -> null
                else -> {
                    val addresses = args["downstream"]?.jsonPrimitive?.contentOrNull?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
                    if (addresses.isEmpty()) null
                    else DomainsProperty.Downstream(ips = addresses.map { addr ->
                        val parts = addr.split(":")
                        InetSocketAddress(parts[0], (parts.getOrNull(1)?.toIntOrNull() ?: 53))
                    })
                }
            }
            val updated = existing.copy(ips = newIps, ttl = newTtl, policy = newPolicy, downStream = newDownstream)
            val newList = props.records.toMutableList().apply { set(idx, updated) }
            setDomainsProperty(props.copy(records = newList))
            CallToolResult(content = listOf(TextContent("Updated record: $domain")))
        }

        // === ip_list ===
        server.addTool(
            name = "ip_list",
            description = "List all monitored IPs with their health status and associated domains",
        ) {
            val props = domainsPropertyRef()
            val allIps = (props.ips.map { it.ip } + props.records.flatMap { it.ips }).distinct()
            val json = Json { prettyPrint = true }
            val dtos = allIps.map { ip ->
                val health = ipService.get(ip)
                val domains = props.records.filter { it.ips.contains(ip) }.map { it.domain }
                IpHealthDto(
                    ip = ip,
                    healthy = health?.healthy,
                    healthCheck = props.ips.find { it.ip == ip }?.healthCheck?.toDto(),
                    domains = domains,
                )
            }
            CallToolResult(content = listOf(TextContent(json.encodeToString(ListSerializer(IpHealthDto.serializer()), dtos))))
        }

        // === ip_add ===
        server.addTool(
            name = "ip_add",
            description = "Add an IP address optionally with HTTP or TCP health check",
            inputSchema = ToolSchema(properties = buildJsonObject {
                put("ip", buildJsonObject { put("type", "string"); put("description", "IP address") })
                put("check_type", buildJsonObject { put("type", "string"); put("description", "Health check type: http, tcp, or none (default)"); put("default", "none") })
                put("url", buildJsonObject { put("type", "string"); put("description", "HTTP check URL (required for http type)") })
                put("method", buildJsonObject { put("type", "string"); put("description", "HTTP method (default GET)"); put("default", "GET") })
                put("response_code", buildJsonObject { put("type", "number"); put("description", "Expected HTTP response code (default 200)"); put("default", 200) })
                put("host", buildJsonObject { put("type", "string"); put("description", "TCP check host (required for tcp type)") })
                put("port", buildJsonObject { put("type", "number"); put("description", "TCP check port (required for tcp type)") })
                put("interval", buildJsonObject { put("type", "string"); put("description", "Check interval as ISO 8601 (default PT10S)"); put("default", "PT10S") })
                put("timeout", buildJsonObject { put("type", "string"); put("description", "Check timeout as ISO 8601 (default PT30S)"); put("default", "PT30S") })
            })
        ) { request ->
            val args = request.arguments ?: return@addTool CallToolResult(content = listOf(TextContent("Missing arguments")))
            val ipStr = args["ip"]?.jsonPrimitive?.contentOrNull ?: return@addTool CallToolResult(content = listOf(TextContent("Missing ip")))
            val checkType = args["check_type"]?.jsonPrimitive?.contentOrNull ?: "none"

            val healthCheck = when (checkType) {
                "http" -> DomainsProperty.HealthCheck(
                    http = DomainsProperty.HealthCheck.Http(
                        url = Url(args["url"]?.jsonPrimitive?.contentOrNull ?: return@addTool CallToolResult(content = listOf(TextContent("url required for http check")))),
                        interval = kotlin.time.Duration.parse(args["interval"]?.jsonPrimitive?.contentOrNull ?: "PT10S"),
                        timeout = kotlin.time.Duration.parse(args["timeout"]?.jsonPrimitive?.contentOrNull ?: "PT30S"),
                        responseCode = args["response_code"]?.jsonPrimitive?.intOrNull ?: 200,
                    )
                )
                "tcp" -> DomainsProperty.HealthCheck(
                    tcp = DomainsProperty.HealthCheck.Tcp(
                        address = InetSocketAddress(
                            args["host"]?.jsonPrimitive?.contentOrNull ?: return@addTool CallToolResult(content = listOf(TextContent("host required for tcp check"))),
                            args["port"]?.jsonPrimitive?.intOrNull ?: return@addTool CallToolResult(content = listOf(TextContent("port required for tcp check"))),
                        ),
                        interval = kotlin.time.Duration.parse(args["interval"]?.jsonPrimitive?.contentOrNull ?: "PT10S"),
                        timeout = kotlin.time.Duration.parse(args["timeout"]?.jsonPrimitive?.contentOrNull ?: "PT30S"),
                    )
                )
                else -> null
            }

            val props = domainsPropertyRef()
            if (props.ips.any { it.ip == ipStr }) {
                return@addTool CallToolResult(content = listOf(TextContent("IP already exists: $ipStr")))
            }
            val newIp = DomainsProperty.IP(ip = ipStr, healthCheck = healthCheck)
            setDomainsProperty(props.copy(ips = props.ips + newIp))
            CallToolResult(content = listOf(TextContent("Added IP: $ipStr")))
        }

        // === ip_delete ===
        server.addTool(
            name = "ip_delete",
            description = "Remove an IP address",
            inputSchema = ToolSchema(properties = buildJsonObject {
                put("ip", buildJsonObject { put("type", "string"); put("description", "IP address to remove") })
            })
        ) { request ->
            val args = request.arguments ?: return@addTool CallToolResult(content = listOf(TextContent("Missing arguments")))
            val ipStr = args["ip"]?.jsonPrimitive?.contentOrNull ?: return@addTool CallToolResult(content = listOf(TextContent("Missing ip")))
            val props = domainsPropertyRef()
            if (props.ips.none { it.ip == ipStr }) {
                return@addTool CallToolResult(content = listOf(TextContent("IP not found: $ipStr")))
            }
            setDomainsProperty(props.copy(ips = props.ips.filter { it.ip != ipStr }))
            ipService.removeIp(HostName(ipStr))
            CallToolResult(content = listOf(TextContent("Deleted IP: $ipStr")))
        }

        // === dns_stats ===
        server.addTool(
            name = "dns_stats",
            description = "Get DNS server statistics: record count, IP count, healthy/unhealthy counts",
        ) {
            val props = domainsPropertyRef()
            val allIps = (props.ips.map { it.ip } + props.records.flatMap { it.ips }).distinct()
            var healthy = 0; var unhealthy = 0; var unmonitored = 0
            for (ip in allIps) {
                when (val status = ipService.get(ip)) { null -> unmonitored++; else -> if (status.healthy) healthy++ else unhealthy++ }
            }
            val stats = DashboardStatsDto(
                dnsStatus = "running", webStatus = if (config.enabled) "running" else "disabled",
                recordsCount = props.records.size, ipsCount = allIps.size,
                healthyCount = healthy, unhealthyCount = unhealthy, unmonitoredCount = unmonitored,
            )
            val json = Json { prettyPrint = true }
            CallToolResult(content = listOf(TextContent(json.encodeToString(stats))))
        }

        // === config_get ===
        server.addTool(
            name = "config_get",
            description = "Get current full configuration as YAML",
        ) {
            val props = domainsPropertyRef()
            val yaml = com.charleskorn.kaml.Yaml.default
            CallToolResult(content = listOf(TextContent(yaml.encodeToString(DomainsProperty.serializer(), props))))
        }
    }
}
