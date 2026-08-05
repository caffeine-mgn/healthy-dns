---
name: "prod-dns-109-stale-binary-and-root-cause"
description: "local-dns 192.168.76.109: /opt/dns kexe rebuilt & deployed; stale spinlock+double-lookup caused 100% CPU/REFUSED"
type: project
lastUpdated: 2026-08-05T16:28
---

Проект healthy-dns (powerdns-health). Прод-бокс: `root@192.168.76.109` (hostname local-dns), SSH доступен, сервис systemd `dns`, рабочая директория `/opt/dns/` (powerdns-health.kexe, config.yaml, dns.service, logs.txt, heaptrack dump).

## ИСТОРИЯ: устаревший бинарник и деградация (ИСПРАВЛЕНО 2026-08-05)
До 2026-08-05 `/opt/dns/powerdns-health.kexe` был собран 2026-06-21 (после коммита d49d33e) и НЕ содержал июльских фиксов (93cb7dc, 4dee5b3). Из-за этого на живом боксе: спинлок `AtomicBoolean.lock()` (busy-wait) давал 100% CPU и фриз DNS при контеншене; двойной вызов `handler.lookup()` на UDP-пакет (в логах два «Query X» на запрос); race send-до-регистрации в DnsUdpClient.lookup вешал downstream на 5с → REFUSED. Бёрст 400 запросов к `*.otpbank.ru` (wildcard с downstream 8.8.8.8) поднимал load 4→13 на 2-ядерном боксе, сервер переставал отвечать даже на router.xx. Рестарт systemctl dns восстанавливал.

## Что сделано 2026-08-05 (задеплоено, проверено на проде)
1. Пересобраны и задеплоены фиксы репо + новые: DnsUdpClient переписан на `CompletableDeferred` + `withTimeout(3s)` (без runBlocking), мёртвый апстрим → `DownstreamTimeoutException` → пропуск (пустой ответ вместо REFUSED); makeRefused сохраняет question-секцию; DnsUdpServer — семафор maxConcurrency=1024; withRetry — счётчик на вызов; HttpChecker/TcpChecker освобождают ресурсы; DnsTcpServer — своя scope вместо GlobalScope; покадровые логи — DEBUG (журнал притих, journald был 813МБ).
2. Починена сборка native: `System.nanoTime()` заменён на `TimeSource.Monotonic` (java.lang.System нет на native) — после июльских фиксов linuxX64 НЕ компилировался; добавлен `src/nativeMain/kotlin/pw/binom/web/WebService.kt` (actual для expect-класса, раньше был только jvmMain).
3. Проверено на проде: бёрст 400 запросов — 0% CPU у процесса, load стабилен, сервер отвечает. Старый бинарник: `/opt/dns/powerdns-health.kexe.bak-20260805`.

## Деплой-конфиг отличается от репо
На проде порт 53 (в репо config.yaml — 8053); `*.otpbank.ru` имеет `downStream: [8.8.8.8:53]`; есть дубликаты записей (zulip.otpbank.ru дважды). 8.8.8.8 из сети разработчика отвечает НЕСТАБИЛЬНО (иногда таймаут, иногда NXDOMAIN за 70мс); с прода отвечает за ~60мс. Health-чеки 192.168.76.110/119:8384 (Syncthing) реально недоступны → photo.tm.xx пустой — это корректно.

## Операционные факты
journald на боксе разросся до 813МБ (старый бинарник писал INFO на каждый пакет). Бокс: 2 ядра, 512МБ RAM.

