# Tasks

## Найденные проблемы (глубокий анализ кода)

### 🔴 Критические (Critical)

- [x] 1. **DnsUdpServer — двойной вызов handler.lookup() (удвоение работы)**
  Файл: `src/commonMain/kotlin/pw/binom/services/DnsUdpServer.kt`, строки ~87–98.
  `handler.lookup(income)` вызывается ДВАЖДЫ: первый раз внутри блока `if (timeout == ...)`, его результат полностью игнорируется; второй раз сразу после — его результат идёт в ответ. На каждый UDP-запрос DNS-обработчик отрабатывает дважды, что вдвое увеличивает нагрузку. Таймаут (5 сек в Main.kt) применяется к первому (бесполезному) вызову, а реальная работа идёт без таймаута.

- [x] 2. **DnsUdpClient.lookup — race condition: send() до регистрации continuation**
  Файл: `src/commonMain/kotlin/pw/binom/services/DnsUdpClient.kt`, метод `lookup()`.
  Datagram отправляется через `client.send()` до того, как `continuation` сохранён в `waters`. Ответ от сервера может прийти быстрее, receiver его обработает, `waters.remove(...)` вернёт null — пакет будет молча потерян. Корректный порядок: сначала зарегистрировать continuation, потом отправлять.

- [x] 3. **DnsUdpClient.lookup — потенциальный дедлок spinlock'а через invokeOnCancellation**
  Файл: `src/commonMain/kotlin/pw/binom/services/DnsUdpClient.kt`, строки ~95–104.
  `lock` уже захвачен (через `lock.lock()`), затем вызывается `continuation.invokeOnCancellation { lock.synchronize { ... } }`. `synchronize` снова пытается захватить тот же `AtomicBoolean` spinlock. Spinlock НЕ реентерабельный — это приводит к вечному циклу (дедлок). `lock.unlock()` в `finally` так и не выполнится.

### 🟠 Высокие (High)

- [ ] 4. **DnsClientService — мёртвый код и сломанный receive-цикл**
  Файл: `src/commonMain/kotlin/pw/binom/services/DnsClientService.kt`.
  Класс не используется (в `Main.kt` и `DomainsServices` используется `DnsUdpClient`). Даже если бы использовался — в корутине `job2` нет цикла, после первого же полученного пакета socket закрывается. Это dead code.

- [ ] 5. **LookupService — кэш temporalRecords никогда не чистится (утечка памяти)**
  Файл: `src/commonMain/kotlin/pw/binom/services/LookupService.kt`, поле `temporalRecords`.
  DNS UPDATE записи добавляются в `HashMap<String, TemporalRecord>`, но никогда не очищаются по TTL. Кэш растёт неограниченно, что в долгоживущем DNS-сервере (uptime недели/месяцы) приведёт к OOM.

- [ ] 6. **DnsUdpServer — launch без try-catch, исключения молча теряются**
  Файл: `src/commonMain/kotlin/pw/binom/services/DnsUdpServer.kt`, строка ~87.
  `manager.launch { val income = DnsPackage.read(...) }` — если `read()` бросит исключение, корутина упадёт через необработанное исключение в `CoroutineScope`, запрос клиента будет молча потерян без ответа.

- [ ] 7. **Spinlock (AtomicBoolean) в suspend-функциях — блокировка потока вместо приостановки**
  Файлы: `DnsUdpClient.kt`, `IpService.kt`, `AtomicBooleanExtensions.kt`.
  Используется блокирующий spinlock (`while(true) { compareAndSet }`) в `suspend`-функциях (`DnsUdpClient.lookup`, `IpService.get/addIp`). Это блокирует поток (thread), что противоречит модели coroutines. Нужно использовать `Mutex` из `kotlinx.coroutines.sync`.

### 🟡 Средние (Medium)

- [ ] 8. **withRetry — ретраит NXDOMAIN и другие «постоянные» ошибки**
  Файл: `src/commonMain/kotlin/pw/binom/DnsHandle.kt`, метод `withRetry`.
  Ретраит любой ответ с `rcode != NOERROR`, включая NXDOMAIN (домен не существует), REFUSED итд. По логике retry должен быть только для транзиентных ошибок (SERVFAIL, таймаут). NXDOMAIN не станет NOERROR от повторного запроса.

- [ ] 9. **InetSocketAddressSerializer — нет валидации порта (0–65535)**
  Файл: `src/commonMain/kotlin/pw/binom/serialization/InetSocketAddressSerializer.kt`.
  Порт парсится через `toIntOrNull()`, но диапазон 0–65535 не проверяется. Можно передать порт 99999 — он пройдёт десериализацию, но вызовет ошибку при открытии сокета.

- [ ] 10. **DnsUdpClient.lookup — отправка до регистрации (race condition, дубль с #2)**
  Уже описан в #2, но это отдельная задача на исправление порядка операций.

- [ ] 11. **DnsTcpServer — отсутствие лимита на concurrent-соединения**
  Файл: `src/commonMain/kotlin/pow/binom/services/DnsTcpServer.kt`, `GlobalScope.launch`.
  Для каждого TCP-клиента запускается корутина без какого-либо лимита. Злоумышленник может открыть тысячи соединений и исчерпать файловые дескрипторы/память.

- [ ] 12. **Main.kt — ресурсы не закрываются при завершении**
  Файл: `src/commonMain/kotlin/pw/binom/Main.kt`.
  `SelectorManager`, `HttpClient`, `DnsUdpClient` создаются, но `close()` нигде не вызывается при выходе из `main`. Потоки/сокеты не освобождаются.

- [ ] 13. **DomainsServices — downstream запрашивает только A-записи, игнорируя AAAA**
  Файл: `src/commonMain/kotlin/pw/binom/services/DomainsServices.kt`, строка ~59.
  `val request = DnsPackage.request(hostname = name, listOf(DnsType.A))` — downstream-серверу всегда шлётся запрос только A-записей. Если исходный запрос клиента был AAAA, downstream вернёт неверный тип.

- [ ] 14. **IpService.init — мёртвый код (очистка пустого ips)**
  Файл: `src/commonMain/kotlin/pw/binom/services/IpService.kt`, блок `init`.
  Блок `init` забирает все IP из пустого `ips`, чистит его и закрывает пустой список чекеров — код никогда ничего не делает. Остаток рефакторинга.

- [ ] 15. **LookupService.query — temporalRecords не фильтруется по типу запроса**
  Файл: `src/commonMain/kotlin/pw/binom/services/LookupService.kt`, метод `query`.
  `temporalRecords` ищется только по `clazz == DnsClass.IN`, а `ans` фильтруется ещё и по `type == DnsType.A`. Ответ может содержать неожиданные типы записей для клиента, запросившего конкретный тип.

- [ ] 16. **PowerDnsProperty — мёртвый код (никем не используется)**
  Файл: `src/commonMain/kotlin/pw/binom/properties/PowerDnsProperty.kt`.
  Data-класс для конфигурации PowerDNS API (с токеном) определён, но нигде не импортируется и не используется.

### 🟢 Низкие (Low)

- [x] 17. **DnsTest.kt — тест использует несуществующие/устаревшие API**
  Файл: `src/commonTest/kotlin/pw/binom/DnsTest.kt`.
  Использует `MultiFixedSizeThreadNetworkDispatcher`, `UdpNetSocket`, `InetSocketAddress.resolve` — эти классы не из текущего стека зависимостей (Ktor network). Тест, скорее всего, не компилируется.

- [ ] 18. **HostName — дублирование `host` и `raw`**
  Файл: `src/commonMain/kotlin/pw/binom/utils/HostName.kt`.
  `val host get() = raw` — свойство `host` полностью дублирует `raw`. В `DomainsServices.init` используется `ip.host`, а в `IpService.get` — `ip.raw`. Разные «ключи» для одного IP могут привести к тому, что healthcheck не найдёт уже зарегистрированный IP.

- [ ] 19. **DnsUdpServer — SocketAddress не валидируется при bind**
  Если в конфиге указать порт 0 или невалидный hostname, сервер упадёт с неинформативной ошибкой. Нет ранней валидации.

- [ ] 20. **Hardcoded config path (`config.yaml`)**
  Файл: `src/commonMain/kotlin/pw/binom/Main.kt`, строка ~30.
  Путь к конфигу жёстко зашит как `"config.yaml"`. Нет поддержки аргументов командной строки, переменных окружения.

### ✅ Исправлено (в этом раунде)

- [x] 21. **Написаны тесты для исправленных компонентов (20 тестов, все проходят)**
  Файл: `src/commonTest/kotlin/pw/binom/DnsTest.kt`.
  Тесты покрывают: `DnsHandle.withTimeout` (3), `DnsHandle.withRetry` (5), `DomainTree` (5), `DnsPackage` utils (3), wildcard-матчинг (4). Все с timeout.

