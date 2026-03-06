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
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Регистрирует этот инстанс в etcd по ключу /rps-service/instances/{instanceId} с lease и keepAlive.
 * При падении процесса ключ исчезает по TTL; при shutdown — lease ревокается (graceful).
 */
@Component
public class InstanceRegistry {

    private static final Logger log = LoggerFactory.getLogger(InstanceRegistry.class);

    private final Client client;
    private final EtcdProperties properties;
    private final String instanceId;

    private volatile long leaseId;
    private volatile CloseableClient keepAliveClient;

    public InstanceRegistry(
            Client client,
            EtcdProperties properties,
            @Value("${instance.id:}") String configuredInstanceId
    ) {
        this.client = client;
        this.properties = properties;
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
                    log.warn("Instance lease keepAlive error", t);
                }

                @Override
                public void onCompleted() {
                    log.info("Instance lease keepAlive stream completed");
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
     * При остановке контекста: закрывает keepAlive и ревокает lease, чтобы ключ инстанса удалился из etcd.
     */
    @javax.annotation.PreDestroy
    public void shutdown() {
        try {
            if (keepAliveClient != null) {
                keepAliveClient.close();
            }
        } catch (Exception e) {
            log.warn("Error closing instance keepAlive client", e);
        }
        try {
            if (leaseId != 0L) {
                client.getLeaseClient().revoke(leaseId).get(3, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            log.warn("Error revoking instance lease", e);
        }
    }

    /** Генерирует уникальный id инстанса, если не задан через instance.id. */
    private String generateInstanceId() {
        return "node-" + UUID.randomUUID();
    }
}

