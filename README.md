# RPS Etcd Service

Spring Boot 3 / Java 21 сервис для распределения RPS между несколькими экземплярами приложения с использованием etcd для координации.

## Архитектура

- **Конфигурация RPS**: читается из файла `rps-config.yml` в classpath. Формат:

```yaml
rps-inbox:
  - id: cf
    rps: 30
  - id: sb
    rps: 20
  - id: tc
    rps: 15
```

- **Регистрация инстанса**:
  - При старте каждый экземпляр регистрируется в etcd по ключу `/rps-service/instances/{instanceId}`.
  - Ключ привязан к lease с TTL (`etcd.instance-ttl-seconds`) и поддерживается через `keepAlive`.
  - При падении инстанса его lease истекает, ключ удаляется автоматически.

- **Leader election**:
  - Все инстансы участвуют в конкурсе за ключ `/rps-service/leader`.
  - Лидер записывает в этот ключ свой `instanceId` под отдельным lease (`etcd.leader-ttl-seconds`).
  - Одновременно может существовать только один лидер (используется транзакция etcd).
  - При исчезновении ключа `leader` другие инстансы пытаются стать лидером.

- **Распределение RPS**:
  - Лидер периодически и по событиям (изменения под префиксом `/rps-service/instances/`) пересчитывает распределение RPS.
  - Список активных инстансов строится по ключам `/rps-service/instances/*`, отсортирован по `instanceId` (стабильный порядок).
  - Для каждого inbox используется формула:
    - `base = totalRps / instancesCount`
    - `remainder = totalRps % instancesCount`
    - остаток раздаётся первым `remainder` инстансам в отсортированном списке.
  - Для каждого инстанса публикуется JSON по ключу `/rps-service/rps/{instanceId}`, например:

```json
{
  "cf": 10,
  "sb": 7,
  "tc": 5
}
```

- **Получение лимитов**:
  - Каждый инстанс делает `watch` на свой ключ `/rps-service/rps/{instanceId}`.
  - При изменении значения лимиты парсятся из JSON и обновляются локально.
  - Текущие лимиты можно посмотреть по HTTP-эндпоинту `GET /limits`.

- **Восстановление после отвала etcd**:
  - При обрыве соединения (перезапуск etcd, сетевой сбой) keepAlive-потоки завершаются с ошибкой.
  - **InstanceRegistry**: по `onError`/`onCompleted` keepAlive запускает фоновое переподключение с экспоненциальной задержкой (1 с → 30 с). После успешной повторной регистрации публикуется `EtcdReconnectedEvent`.
  - **LeaderCoordinator**: при падении keepAlive лидера вызывается `resignFromLeadership()` (закрывается watcher инстансов, keepAlive, ревокация lease). Цикл выбора лидера снова пытается стать лидером; после появления etcd один из инстансов станет лидером и выполнит перераспределение RPS.
  - **InstanceRpsWatcher**: по `EtcdReconnectedEvent` закрывается старый watch, заново читается значение ключа `/rps-service/rps/{instanceId}` и поднимается новый watch. Лимиты снова начинают поступать в сервис без перезапуска приложения.

- **Дополнительно**:
  - Debounce перерасчёта: серия событий по `/instances/*` склеивается таймером (300мс) перед перерасчётом.
  - Graceful shutdown: при остановке инстанс закрывает `keepAlive` и ревокает оба lease (инстанса и лидера, если он лидер).
  - Периодическая сверка состояния: лидер запускает reconciliation-цикл с интервалом `rps.reconciliation-interval-ms`.

### Краевые случаи и ограничения

- **Отвал etcd**: сервис продолжает работать на последних известных лимитах; при восстановлении etcd — переподключение и перераспределение без перезапуска.
- **Пустые лимиты**: если из etcd приходит пустой JSON по ключу RPS, текущие лимиты не затираются (остаются прежние).
- **Частичная запись лидером**: при публикации RPS лидер пишет по одному ключу на инстанс; если запись для одного инстанса падает (таймаут и т.п.), остальные обновляются, упавший — получит лимиты при следующем перерасчёте (reconciliation или событие по instances).
- **Окно без лидера**: когда у лидера обрывается keepAlive, он слагает полномочия; ревокация lease может не дойти до etcd при уже мёртвом соединении. Ключ `leader` в etcd исчезнет только по TTL (`leader-ttl-seconds`). До этого новый лидер не сможет избраться — перераспределение задержится на величину TTL (по умолчанию до 10 с); лимиты у инстансов при этом не меняются.
- **Один и тот же `instance.id` у двух процессов**: оба регистрируются под одним ключом (последний lease выигрывает), оба смотрят один ключ `/rps/{id}` — получают одинаковые лимиты; с точки зрения распределения считаются одним инстансом.
- **Клиент etcd в невосстановимом состоянии**: при «мёртвом» gRPC-канале переподключение (reRegister) может постоянно падать по таймауту; ретраи идут с backoff. В таком случае помогает перезапуск приложения (пересоздание клиента etcd).
- **Пароли keystore/truststore**: в проде задавайте через переменные окружения, например `etcd.ssl.keystore-password: ${ETCD_KEYSTORE_PASSWORD}`.

## Настройка etcd

По умолчанию сервис ожидает etcd на `http://localhost:2379`. Можно запустить etcd в Docker:

```bash
docker run --rm -p 2379:2379 -p 2380:2380 \
  --name etcd \
  quay.io/coreos/etcd:v3.5.13 \
  /usr/local/bin/etcd \
  --name s1 \
  --data-dir /etcd-data \
  --advertise-client-urls http://0.0.0.0:2379 \
  --listen-client-urls http://0.0.0.0:2379 \
  --initial-advertise-peer-urls http://0.0.0.0:2380 \
  --listen-peer-urls http://0.0.0.0:2380 \
  --initial-cluster s1=http://0.0.0.0:2380 \
  --initial-cluster-token tkn \
  --initial-cluster-state new
```

Эндпоинты etcd можно поменять в `application.yml`:

```yaml
etcd:
  endpoints:
    - http://localhost:2379
  root-prefix: /rps-service
  instance-ttl-seconds: 10
  leader-ttl-seconds: 10
```

### TLS (SSL)

В прод среде при etcd по HTTPS включите SSL и укажите keystore (клиентский сертификат) и при необходимости truststore (CA сервера):

```yaml
etcd:
  endpoints:
    - https://etcd-host:2379
  ssl:
    enabled: true
    keystore-path: file:/path/to/client.pfx    # или classpath:etcd-client.pfx
    keystore-password: your-password
    keystore-type: PKCS12                      # или JKS
    trust-store-path: file:/path/to/trust.pfx  # опционально; иначе системный trust store
    trust-store-password: secret
    trust-store-type: PKCS12                   # или JKS
```

- **keystore-path** — путь к PFX/JKS с клиентским сертификатом (префиксы `file:` и `classpath:`).
- **keystore-password** / **keystore-type** — пароль и тип хранилища (PKCS12, JKS).
- **trust-store-path** (необязательно) — путь к хранилищу с CA для проверки сервера. Если не задан — системный trust store JVM.
- **trust-store-password** / **trust-store-type** — пароль и тип (PKCS12, JKS).

## Метрики Prometheus

Эндпоинт: **`GET /actuator/prometheus`**.

Метрики (префикс `rps_`):

| Метрика | Описание |
|--------|----------|
| `rps_etcd_instance_ttl_seconds` | TTL lease инстанса из конфига (с). |
| `rps_etcd_leader_ttl_seconds` | TTL lease лидера из конфига (с). |
| `rps_etcd_ssl_enabled` | SSL включён (1) или нет (0). |
| `rps_config_reconciliation_interval_ms` | Интервал перерасчёта из конфига (мс). |
| `rps_config_inbox_total_rps{inbox="..."}` | Суммарный RPS по inbox из rps-config. |
| `rps_instance_info{instance_id="..."}` | Идентификатор инстанса (всегда 1). |
| `rps_leader{instance_id="..."}` | Является ли инстанс лидером (1 да, 0 нет). |
| `rps_limit{instance_id="...", inbox="..."}` | Текущий лимит RPS для этого инстанса и inbox (из etcd). |

Включение: в `application.yml` задано `management.endpoints.web.exposure.include: health,info,prometheus`.

## Сборка и запуск

Сборка:

```bash
mvn clean package
```

Запуск одного экземпляра:

```bash
mvn spring-boot:run
```

Или из готового JAR:

```bash
java -jar target/rps-etcd-service-0.0.1-SNAPSHOT.jar
```

Опционально можно задать `instance.id`, чтобы инстансы имели предсказуемые идентификаторы:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--instance.id=node1"
```

## Пример запуска нескольких экземпляров

В разных терминалах запустите несколько инстансов с разными `instance.id` и портами:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--instance.id=node1 --server.port=8081"
mvn spring-boot:run -Dspring-boot.run.arguments="--instance.id=node2 --server.port=8082"
mvn spring-boot:run -Dspring-boot.run.arguments="--instance.id=node3 --server.port=8083"
```

Поведение:

- Первый поднявшийся инстанс станет лидером и распределит лимиты.
- При появлении новых инстансов (scale up) / исчезновении (scale down) лидер пересчитает лимиты и обновит ключи `/rps-service/rps/{instanceId}`.
- Если лидер падает, его lease истекает, ключ `/rps-service/leader` удаляется, и один из оставшихся инстансов становится новым лидером.

Проверка текущих лимитов на каждом инстансе:

- `GET http://localhost:8081/limits`
- `GET http://localhost:8082/limits`
- `GET http://localhost:8083/limits`

Ответ содержит `instanceId` и его текущую карту лимитов по inbox-каналам.

