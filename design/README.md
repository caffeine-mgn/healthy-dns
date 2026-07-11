# Design / UI Mockups

Макеты интерфейса для PowerDNS Health.

## Страницы

| Файл | Назначение |
|------|-----------|
| `dashboard.html` | Главная панель — сводка статусов: DNS сервер, HTTP сервер, количество записей, здоровье IP |
| `records.html` | Управление DNS-записями: таблица доменов, IP, TTL, downstream. CRUD через модалку |
| `ip-health.html` | Мониторинг IP-адресов: карточки с индикаторами здоровья, добавление/редактирование healthcheck |
| `settings.html` | Настройки сервера: bind, порты, TTL по умолчанию, просмотр/редактирование raw конфига |

## API эндпоинты (предполагаемые)

```
GET    /api/health          — статус сервера
GET    /api/records         — список DNS-записей
POST   /api/records         — создать запись
PUT    /api/records/:domain — обновить запись
DELETE /api/records/:domain — удалить запись
GET    /api/ips             — список IP со статусами
POST   /api/ips             — добавить IP с healthcheck
DELETE /api/ips/:ip         — удалить IP
PUT    /api/config          — обновить конфиг
```

## Стиль

- Тёмная тема (#121212 фон, #1E1E1E карточки)
- Единая навигация (Dashboard / Records / IP Health / Settings)
- Все CRUD через модальные окна
- Карточки для IP health, таблицы для records
