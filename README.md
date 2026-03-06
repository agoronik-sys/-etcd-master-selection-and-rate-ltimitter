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

- **Дополнительно**:
  - Debounce перерасчёта: серия событий по `/instances/*` склеивается таймером (300мс) перед перерасчётом.
  - Graceful shutdown: при остановке инстанс закрывает `keepAlive` и ревокает оба lease (инстанса и лидера, если он лидер).
  - Периодическая сверка состояния: лидер запускает reconciliation-цикл с интервалом `rps.reconciliation-interval-ms`.

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

