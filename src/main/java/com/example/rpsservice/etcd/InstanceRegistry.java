package com.example.rpsservice.etcd;

import com.example.rpsservice.config.EtcdProperties;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.KV;
import io.etcd.jetcd.Lease;
import io.etcd.jetcd.lease.LeaseGrantResponse;
import io.etcd.jetcd.options.PutOption;
import io.etcd.jetcd.support.CloseableClient;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Регистрирует этот инстанс в etcd по ключу /rps-service/instances/{instanceId} с lease и keepAlive.
 * При падении процесса ключ исчезает по TTL; при shutdown — lease ревокается (graceful).
 * При отвале etcd при восстановлении соединения повторно регистрирует инстанс и публикует
 * {@link EtcdReconnectedEvent} для переподключения watch'ей и перераспределения RPS.
 */
@Component
public class InstanceRegistry {

    private static final Logger log = LoggerFactory.getLogger(InstanceRegistry.class);

    private static final long RECONNECT_INITIAL_DELAY_MS = 1000;
    private static final long RECONNECT_MAX_DELAY_MS = 30_000;

    private final Client client;
    private final EtcdProperties properties;
    private final String instanceId;
    private final ApplicationEventPublisher eventPublisher;

    private volatile long leaseId;
    private volatile CloseableClient keepAliveClient;
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    private final ExecutorService reconnectExecutor =
            Executors.newSingleThreadExecutor(Thread.ofVirtual().name("instance-registry-reconnect").factory());

    public InstanceRegistry(
            Client client,
            EtcdProperties properties,
            ApplicationEventPublisher eventPublisher,
            @Value("${instance.id:}") String configuredInstanceId
    ) {
        this.client = client;
        this.properties = properties;
        this.eventPublisher = eventPublisher;
        this.instanceId = (configuredInstanceId == null || configuredInstanceId.isBlank())
                ? generateInstanceId()
                : configuredInstanceId;
    }

    /** Идентификатор этого инстанса (из конфига или сгенерированный). */
    public String getInstanceId() {
        return instanceId;
    }

    /** ID lease, привязанного к ключу регистрации в etcd. */
    public long getLeaseId() {
        return leaseId;
    }

    /**
     * После старта приложения: создаёт lease, запускает keepAlive и пишет ключ instances/{instanceId} в etcd.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void register() {
        try {
            Lease leaseClient = client.getLeaseClient();
            Duration ttl = properties.instanceTtl();
            CompletableFuture<LeaseGrantResponse> grantFuture = leaseClient.grant(ttl.getSeconds());
            LeaseGrantResponse grant = grantFuture.get(5, TimeUnit.SECONDS);
            this.leaseId = grant.getID();

            this.keepAliveClient = leaseClient.keepAlive(leaseId, new StreamObserver<>() {
                @Override
                public void onNext(io.etcd.jetcd.lease.LeaseKeepAliveResponse value) {
                    // heartbeat ok
                }

                @Override
                public void onError(Throwable t) {
                    log.warn("Instance lease keepAlive error (etcd may be down), will try to reconnect", t);
                    scheduleReconnect();
                }

                @Override
                public void onCompleted() {
                    log.info("Instance lease keepAlive stream completed, will try to reconnect");
                    scheduleReconnect();
                }
            });

            String key = properties.instancesPrefix() + "/" + instanceId;
            KV kvClient = client.getKVClient();
            kvClient.put(
                    ByteSequence.from(key, StandardCharsets.UTF_8),
                    ByteSequence.EMPTY,
                    PutOption.newBuilder().withLeaseId(leaseId).build()
            ).get(5, TimeUnit.SECONDS);

            log.info("Registered instance {} with key {}", instanceId, key);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to register instance in etcd", e);
        }
    }

    /**
     * Запускает фоновое восстановление соединения с etcd: повторная регистрация с экспоненциальной задержкой.
     * При успехе публикует {@link EtcdReconnectedEvent}.
     */
    private void scheduleReconnect() {
        if (!reconnecting.compareAndSet(false, true)) {
            return;
        }
        reconnectExecutor.execute(this::reconnectLoop);
    }

    private void reconnectLoop() {
        long delayMs = RECONNECT_INITIAL_DELAY_MS;
        while (true) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                reconnecting.set(false);
                return;
            }
            try {
                reRegister();
                log.info("Reconnected to etcd, instance {} registered again", instanceId);
                eventPublisher.publishEvent(new EtcdReconnectedEvent(this));
                reconnecting.set(false);
                return;
            } catch (Exception e) {
                log.warn("Reconnect to etcd failed (next attempt in {} ms): {}", delayMs, e.getMessage());
                delayMs = Math.min(delayMs * 2, RECONNECT_MAX_DELAY_MS);
            }
        }
    }

    /**
     * Повторная регистрация в etcd (новый lease, put, keepAlive). Вызывается при восстановлении после отвала etcd.
     */
    private void reRegister() throws Exception {
        closeKeepAliveAndRevoke();
        Lease leaseClient = client.getLeaseClient();
        Duration ttl = properties.instanceTtl();
        LeaseGrantResponse grant = leaseClient.grant(ttl.getSeconds()).get(5, TimeUnit.SECONDS);
        this.leaseId = grant.getID();

        this.keepAliveClient = leaseClient.keepAlive(leaseId, new StreamObserver<>() {
            @Override
            public void onNext(io.etcd.jetcd.lease.LeaseKeepAliveResponse value) {
            }

            @Override
            public void onError(Throwable t) {
                log.warn("Instance lease keepAlive error (etcd may be down), will try to reconnect", t);
                scheduleReconnect();
            }

            @Override
            public void onCompleted() {
                log.info("Instance lease keepAlive stream completed, will try to reconnect");
                scheduleReconnect();
            }
        });

        String key = properties.instancesPrefix() + "/" + instanceId;
        KV kvClient = client.getKVClient();
        kvClient.put(
                ByteSequence.from(key, StandardCharsets.UTF_8),
                ByteSequence.EMPTY,
                PutOption.newBuilder().withLeaseId(leaseId).build()
        ).get(5, TimeUnit.SECONDS);
    }

    private void closeKeepAliveAndRevoke() {
        try {
            if (keepAliveClient != null) {
                keepAliveClient.close();
                keepAliveClient = null;
            }
        } catch (Exception e) {
            log.debug("Error closing instance keepAlive client", e);
        }
        try {
            if (leaseId != 0L) {
                client.getLeaseClient().revoke(leaseId).get(3, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            log.debug("Error revoking instance lease (etcd may be down)", e);
        }
        leaseId = 0L;
    }

    /**
     * При остановке контекста: закрывает keepAlive и ревокает lease, чтобы ключ инстанса удалился из etcd.
     */
    @javax.annotation.PreDestroy
    public void shutdown() {
        reconnecting.set(false);
        reconnectExecutor.shutdownNow();
        closeKeepAliveAndRevoke();
    }

    /** Генерирует уникальный id инстанса, если не задан через instance.id. */
    private String generateInstanceId() {
        return "node-" + UUID.randomUUID();
    }
}

