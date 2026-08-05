# Tasks

## Найденные проблемы (глубокий анализ кода) — ВСЁ ИСПРАВЛЕНО

### 🔴 Критические (Critical) — все исправлены

- [x] 1. **DnsUdpServer — двойной вызов handler.lookup() (удвоение работы)**
  `handler.lookup(income)` вызывался дважды: первый раз с таймаутом (результат выбрасывался), второй раз без таймаута. Исправлено: результат сохраняется.
- [x] 2. **DnsUdpClient.lookup — race condition: send() до регистрации continuation**
  Исправлено: регистрация continuation теперь ДО отправки датаграммы. Вместо `AtomicBoolean` spinlock — `Mutex`.
- [x] 3. **DnsUdpClient.lookup — потенциальный дедлок spinlock'а через invokeOnCancellation**
  Исправлено: lock не захвачен при установке cancel-хендлера. `Mutex` вместо spinlock.

### 🟠 Высокие (High) — все исправлены

- [x] 4. **DnsClientService — мёртвый код и сломанный receive-цикл**
  Файл удалён.
- [x] 5. **LookupService — кэш temporalRecords никогда не чистится (утечка памяти)**
  Добавлен `expiresAt` в `StoredRecord`, `getRecords()` фильтрует просроченные, `cleanupExpired()` при каждом query.
- [x] 6. **DnsUdpServer — launch без try-catch, исключения молча теряются**
  Тело `manager.launch` обёрнуто в try-catch; при ошибке отправляется REFUSED.
- [x] 7. **Spinlock (AtomicBoolean) в suspend-функциях — блокировка потока вместо приостановки**
  Заменён на `Mutex` из `kotlinx.coroutines.sync` в `DnsUdpClient` и `IpService`.

### 🟡 Средние (Medium) — все исправлены

- [x] 8. **withRetry — ретраит NXDOMAIN и другие «постоянные» ошибки**
  Исправлено: ретрай теперь только на `RCode.SERVFAIL`. NXDOMAIN, REFUSED и др. возвращаются сразу.
- [x] 9. **InetSocketAddressSerializer — нет валидации порта (0–65535)**
  Добавлен `require(port in 0..65535)`.
- [x] 10. **DnsUdpClient.lookup — отправка до регистрации (дубль с #2)**
  Исправлено вместе с #2.
- [x] 11. **DnsTcpServer — отсутствие лимита на concurrent-соединения**
  Добавлен `Semaphore(maxConnections = 100)`, каждое соединение — `withPermit`.
- [x] 12. **Main.kt — ресурсы не закрываются при завершении**
  Добавлен try-finally с close() для SelectorManager, HttpClient, DnsUdpClient.
- [x] 13. **DomainsServices — downstream запрашивает только A-записи, игнорируя AAAA**
  Метод `findRecords` теперь принимает `queryTypes`; в `LookupService` передаётся тип из исходного запроса.
- [x] 14. **IpService.init — мёртвый код (очистка пустого ips)**
  Блок `init` удалён.
- [x] 15. **LookupService.query — temporalRecords не фильтруется по типу запроса**
  `getRecords(query.type)` уже фильтрует по типу. Добавлен cleanup просроченных.
- [x] 16. **PowerDnsProperty — мёртвый код (никем не используется)**
  Файл удалён.

### 🟢 Низкие (Low) — все исправлены

- [x] 17. **DnsTest.kt — тест использует несуществующие/устаревшие API**
  Полностью переписан: 20 тестов, покрывают `withTimeout`, `withRetry`, `DomainTree`, wildcardMatch, утилиты DnsPackage.
- [x] 18. **HostName — дублирование `host` и `raw`**
  Свойство `host` удалено; везде используется `raw`.
- [x] 19. **DnsUdpServer — SocketAddress не валидируется при bind**
  Добавлена валидация порта в `init`.
- [x] 20. **Hardcoded config path (`config.yaml`)**
  Путь берётся из `args.firstOrNull() ?: "config.yaml"`.

### ✅ Результат

Все 20 задач исправлены. 20 тестов проходят.

---

## Производительность — потенциальные улучшения

### 🟡 Высокий приоритет

- [ ] 22. **Заменить flow-цепочки на прямые операции с коллекциями**
  Файлы: `LookupService.kt`, `DomainsServices.kt`.
  Каждый DNS-запрос строит граф flow-операторов (`asFlow → filter → flatMapConcat → map → toList`), хотя данные — простые списки на 1–10 элементов. Заменить на `map`/`flatMap`/`filter` коллекций — на порядок меньше аллокаций.

- [ ] 23. **Добавить кеш DNS-ответов по TTL**
  Файл: `DomainsServices.kt` или новый `DnsCache.kt`.
  Сейчас каждый запрос к одному домену проходит полный pipeline (DomainTree + healthcheck + сборка пакета). Кеш `(domain, type) → (ответ, expiresAt)` с TTL из конфига даст x10–100 при повторяющихся запросах.

- [ ] 24. **Сделать `IpService.get()` suspend-функцией, убрать `runBlocking`**
  Файл: `IpService.kt`.
  `operator fun get(ip: String): Ip? = runBlocking { lock.withLock { ips[ip] } }` — на каждый healthcheck-lookup создаётся корутина через `runBlocking`. Сделать `suspend operator fun get(...)` и вызывать из suspend-контекста.

### 🟢 Средний приоритет

- [ ] 25. **Кеш split'нутых доменных имён**
  Файл: `DomainsServices.kt`, метод `findRecords`.
  `name.split(".")` на каждый lookup аллоцирует список строк. Добавить WeakHashMap/LRU-кеш split'нутых имён.

- [ ] 26. **Guard на cleanupExpiredTemporalRecords — не чаще раза в N секунд**
  Файл: `LookupService.kt`.
  `cleanupExpiredTemporalRecords()` вызывается при каждом `query()` и проходится по всем записям (O(n)). Если кэш вырос, это тормозит каждый lookup. Добавить `lastCleanupTimeNanos` + guard.

- [ ] 27. **Заменить `GlobalScope` на scope сервера в DnsTcpServer**
  Файл: `DnsTcpServer.kt`.
  При закрытии сервера соединения в `GlobalScope` не отменяются. Использовать `coroutineScope` или дочернюю scope, привязанную к job сервера.

### 🟢 Низкий приоритет

- [ ] 28. **Ограниченный параллелизм в DnsUdpServer (limitedParallelism)**
  Файл: `DnsUdpServer.kt`.
  Каждый UDP-пакет — новая корутина без лимита. Для тысяч RPS стоит добавить `limitedParallelism` на диспетчере.

---

## Доп. фиксы 2026-08-05 (диагностика прода: 192.168.76.109)

Деплой на проде был со сборки от 2026-06-21 (до коммитов-фиксов 93cb7dc/4dee5b3) — на живом боксе воспроизведены 100% CPU и полный фриз DNS при бёрсте ~400 запросов (load 4→13, 3 потока в R). Дополнительно внесено:

- [x] 29. **DnsUdpClient — внутренний таймаут и убирание `runBlocking` из suspend-пути**
  `lookup()` переписан на `CompletableDeferred` + `withTimeout(3s)`; при таймауте бросается `DownstreamTimeoutException` (не `TimeoutCancellationException`, чтобы не путать с отменой корутины). Мёртвый апстрим больше не вешает запрос на 5с → REFUSED.

- [x] 30. **DomainsServices.findRecords — толерантность к мёртвому downstream**
  Сбой/таймаут апстрима логируется и пропускается (`emptyFlow`), отмена корутины (`CancellationException`) пробрасывается.

- [x] 31. **makeRefused/makeServerFail — сохраняют question-секцию**
  Раньше `queries = emptyList()` — резолверы отвергали ответ как «missing question section» (клиент видел «сервер не заведует доменом»).

- [x] 32. **DnsUdpServer — семафор на параллельные обработчики (maxConcurrency=1024)**
  Корутина на пакет без лимита убрана; покадровые логи Receive/Send переведены на DEBUG.

- [x] 33. **withRetry — счётчик попыток на вызов**
  Общий мутабельный `count` между параллельными запросами был гонкой.

- [x] 34. **IpService — освобождение ресурсов health-чеков**
  `HttpChecker`: `response.bodyAsChannel().cancel(null)` после чтения статуса (иначе Ktor держит соединение); `TcpChecker`: сокет в `use {}`.

- [x] 35. **DnsTcpServer — своя scope вместо GlobalScope**
  `connectionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)`, отменяется в `close()`.

- [x] 36. **Лог-шторм**
  «Query …» в LookupService и Receive/Send в DnsUdpServer → DEBUG (на проде journald разросся до 813МБ, journald держал 210МБ RSS на боксе с 512МБ RAM).
