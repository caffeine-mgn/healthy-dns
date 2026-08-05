# healthy-dns

A lightweight authoritative-ish DNS server for private networks (LANs, homelabs, offices).

Each domain can map to **multiple IP addresses**, and each IP can have a **health check** —
the server only answers with IPs that are currently healthy. Optional **downstream forwarding**
lets it resolve names through an upstream resolver and merge the answers.

Written in Kotlin Multiplatform (Kotlin/Native + JVM) on top of Ktor and kotlinx-coroutines.

---

## Features

- **Multi-IP records** — one domain can point to several addresses (A records).
- **Health checks** — per-IP HTTP or TCP probes; unhealthy IPs are excluded from answers.
- **Downstream forwarding** — per-domain delegation to an upstream DNS server
  (e.g. `8.8.8.8:53`); upstream answers are merged with local ones.
- **Wildcard domains** — `*.example.com`, `*.*.example.com`, etc. Exact matches win over wildcards.
- **Dynamic updates** — supports the DNS `UPDATE` opcode (SOA-based) for adding/removing
  records at runtime, with TTL-based expiry.
- **UDP + TCP** on the same port (default `53`).
- **Optional web/MCP server** — REST health endpoint plus an MCP (Model Context Protocol)
  tool server for managing records and IPs.
- **Multiplatform builds** — Linux x64/arm64, Windows x64 (Kotlin/Native), JVM.

## What it does NOT do (yet)

This is important — the server is intentionally small:

- **No recursive resolution.** Queries for domains that are not in the config return an
  **empty `NOERROR`** response (not `NXDOMAIN`, not a forwarded query). Do not use it as a
  general-purpose resolver with no upstream fallback.
- **Only `A` records are served from the config.** `AAAA` queries get an empty `NOERROR`
  even if an IP in the config is IPv6. (IPv6 addresses in config produce `AAAA`-type records,
  but the query path only answers `A` queries; dynamic `UPDATE` records are served for any type.)
- **`policy` is not implemented.** `ALL_HEALTHY` / `ROUND_ROBIN_HEALTHY` / `FIRST_HEALTHY`
  are accepted and stored in the config, but every record always returns **all healthy IPs**
  in config order.
- **`domains.healthChecks` map is unused.** Configure health checks via `domains.ips[].healthCheck`.
- **No caching** — every query re-evaluates health checks and downstream lookups.
- **No DNSSEC, no EDNS(0) options, no zone transfers (AXFR/IXFR), no SOA/NS records**
  for zone apex queries.
- **No hot reload** — config is read at startup; changes made through the web server are
  written to the config file but require a restart to take effect.

## How it works

```
client ──► UDP/TCP :53 ──► LookupService ──► DomainTree (config records)
                                          ├── local IPs (health-gated)
                                          └── downstream upstream DNS (optional, merged)
```

- A query for `myapp.example.com` looks up the record, filters out unhealthy IPs,
  optionally queries the downstream resolver, and returns the merged answer set.
- The whole lookup has a **5-second budget** (`withTimeout`); each downstream query has its own
  **3-second timeout**. A dead downstream is logged and skipped — it never turns the answer
  into `REFUSED`.

## Configuration

The server reads a YAML config file (first CLI argument, default `config.yaml`):

```yaml
server:
  bind: 0.0.0.0:53        # host:port for UDP and TCP

web:
  enabled: true
  host: 0.0.0.0
  port: 8080
  token: my-secret-key     # optional; required as "Authorization: Bearer <token>"

domains:
  # IPs that are actively health-checked (monitored)
  ips:
    - ip: 192.168.1.10
      healthCheck:
        http:
          url: http://192.168.1.10:8080/health
          method: GET        # default GET
          responseCode: 200  # default 200
          interval: PT10S    # default 10s
          timeout: PT30S     # default 30s
        # tcp:
        #   address: 192.168.1.10:3306
        #   interval: PT10S
        #   timeout: PT30S

  # DNS records
  records:
    - domain: myapp.example.com
      ttl: PT30S            # ISO-8601 duration (default PT30S)
      ips:
        - 192.168.1.10
        - 192.168.1.11

    - domain: '*.example.com'      # wildcard: matches ANY remaining subdomain labels
      ips: [192.168.1.20]

    - domain: legacy.example.com   # forwarded upstream, merged with local answers
      downStream:
        ips: [8.8.8.8:53]
```

Notes:

- **TTL** is an ISO-8601 duration (`PT30S`, `PT360S`, ...).
- **Wildcards** use `*` as a label; `*.example.com` matches `a.example.com` **and**
  `a.b.example.com` (the star matches any remaining labels). Exact records take precedence.
- **Downstream** forwarding is per-record (`downStream.ips`), not global.
- **Unknown domains** → empty `NOERROR`.
- IPs from `records[].ips` that are **not** listed under `domains.ips` are always returned
  (no health gate); only IPs with an explicit health check are filtered by health.

## Health checks

- `http` — sends the configured method to `url` and compares the status code with
  `responseCode` (default `200`).
- `tcp` — opens a TCP connection to `address` and waits for it to close.
- Checks run every `interval` (default `10s`) with a `timeout` (default `30s`).
- The last known status is cached; DNS answers only include healthy IPs.
- IPs without a health check are considered always healthy.

## Dynamic updates (DNS UPDATE)

The server accepts `UPDATE` opcode queries that include an `SOA` question and carry
records in the authority section:

| Authority record             | Effect                         |
|------------------------------|--------------------------------|
| `IN`, TTL > 0                | add or replace the record      |
| `IN`, TTL == 0               | remove exact record            |
| `NONE`, TTL == 0             | remove all records of that type|
| `ANY`, TTL == 0              | remove all records of the name |

Records added this way expire after their TTL. Requests without an `SOA` question
are answered with `REFUSED`.

## Web server & MCP

When `web.enabled` is true, the server exposes:

- `GET /api/health` — liveness probe.
- `POST /mcp` — MCP **Streamable HTTP** endpoint with these tools:
  `dns_list_records`, `dns_add_record`, `dns_delete_record`, `dns_update_record`,
  `ip_list`, `ip_add`, `ip_delete`, `dns_stats`, `config_get`.
- Optional Bearer-token auth on all endpoints except `/api/health`.

MCP tool calls that change the config persist the updated YAML to the config file;
restart the DNS server to apply.

## Building and running

Requirements: JDK 17+ and the Kotlin/Native toolchain (downloaded automatically on first build).

```bash
# JVM (development)
./gradlew jvmRun --args='config.yaml'

# Native release binary (Linux x64)
./gradlew linkReleaseExecutableLinuxX64
./build/bin/linuxX64/releaseExecutable/powerdns-health.kexe config.yaml

# Tests
./gradlew jvmTest
```

Run as root (or give the binary `CAP_NET_BIND_SERVICE`) to bind port 53.

### systemd example

```ini
[Unit]
Description=healthy-dns
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory=/opt/dns
ExecStart=/opt/dns/powerdns-health.kexe
Restart=on-abnormal
RestartSec=2s

[Install]
WantedBy=multi-user.target
```

## Operational notes

- Per-query logging is at `DEBUG` level — the default journald/console output stays quiet
  even under load.
- The process is a single-threaded event loop for DNS I/O; UDP request handlers are bounded
  by a semaphore (default 1024 concurrent).
