package com.example.rpsservice.etcd;

import com.example.rpsservice.config.EtcdProperties;
import com.example.rpsservice.config.RpsConfigLoader;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.etcd.jetcd.*;
import io.etcd.jetcd.kv.GetResponse;
import io.etcd.jetcd.Lease;
import io.etcd.jetcd.kv.TxnResponse;
import io.etcd.jetcd.lease.LeaseGrantResponse;
import io.etcd.jetcd.op.Cmp;
import io.etcd.jetcd.op.CmpTarget;
import io.etcd.jetcd.op.Op;
import io.etcd.jetcd.options.GetOption;
import io.etcd.jetcd.options.PutOption;
import io.etcd.jetcd.options.WatchOption;
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
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Участвует в выборе лидера по ключу /rps-service/leader (lease + транзакция).
 * Лидер: следит за списком инстансов, считает распределение RPS и пишет лимиты в /rps-service/rps/{instanceId}.
 * Перерасчёт — по событиям watch на instances и по таймеру (reconciliation), с debounce.
 */
@Component
public class LeaderCoordinator {

    private static final Logger log = LoggerFactory.getLogger(LeaderCoordinator.class);

    private final Client client;
    private final EtcdProperties properties;
    private final InstanceRegistry instanceRegistry;
    private final RpsConfigLoader rpsConfigLoader;
    private final ObjectMapper objectMapper;
    private final long reconciliationIntervalMs;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "rps-leader-scheduler");
                t.setDaemon(true);
                return t;
            });

    private final AtomicBoolean isLeader = new AtomicBoolean(false);
    private volatile boolean running = true;

    private volatile long leaderLeaseId;
    private volatile CloseableClient leaderKeepAliveClient;
    private volatile Watch.Watcher instancesWatcher;

    private final Object debounceLock = new Object();
    private volatile ScheduledFuture<?> pendingRecalcTask;

    public LeaderCoordinator(
            Client client,
            EtcdProperties properties,
            InstanceRegistry instanceRegistry,
            RpsConfigLoader rpsConfigLoader,
            ObjectMapper objectMapper,
            @Value("${rps.reconciliation-interval-ms:30000}") long reconciliationIntervalMs
    ) {
        this.client = client;
        this.properties = properties;
        this.instanceRegistry = instanceRegistry;
        this.rpsConfigLoader = rpsConfigLoader;
        this.objectMapper = objectMapper;
        this.reconciliationIntervalMs = reconciliationIntervalMs;
    }

    /**
     * После старта приложения: запускает фоновый поток выбора лидера и периодический reconciliation.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        Thread electionThread = new Thread(this::electionLoop, "rps-leader-election");
        electionThread.setDaemon(true);
        electionThread.start();

        scheduler.scheduleWithFixedDelay(this::safeRecalculateIfLeader,
                reconciliationIntervalMs,
                reconciliationIntervalMs,
                TimeUnit.MILLISECONDS);
    }

    /** Цикл: пытается стать лидером; при неудаче ждёт исчезновения ключа лидера и повторяет. */
    private void electionLoop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                attemptToBecomeLeader();
            } catch (Exception e) {
                log.warn("Leader election attempt failed", e);
            }
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Создаёт lease для лидера, пытается атомарно записать /leader со своим instanceId.
     * При успехе — становится лидером и крутится в цикле; при неудаче — ревокает lease и ждёт освобождения ключа.
     */
    private void attemptToBecomeLeader() throws Exception {
        if (!running) {
            return;
        }

        Lease leaseClient = client.getLeaseClient();
        Duration ttl = properties.leaderTtl();
        LeaseGrantResponse grant = leaseClient.grant(ttl.getSeconds()).get(5, TimeUnit.SECONDS);
        long leaseId = grant.getID();

        CloseableClient keepAlive = leaseClient.keepAlive(leaseId, new StreamObserver<>() {
            @Override
            public void onNext(io.etcd.jetcd.lease.LeaseKeepAliveResponse value) {
            }

            @Override
            public void onError(Throwable t) {
                log.warn("Leader lease keepAlive error", t);
            }

            @Override
            public void onCompleted() {
                log.info("Leader lease keepAlive stream completed");
            }
        });

        ByteSequence leaderKey = bs(properties.leaderKey());
        ByteSequence leaderValue = bs(instanceRegistry.getInstanceId());
        KV kvClient = client.getKVClient();

        TxnResponse txn = kvClient.txn()
                .If(new Cmp(leaderKey, Cmp.Op.EQUAL, CmpTarget.version(0)))
                .Then(Op.put(leaderKey, leaderValue,
                        PutOption.newBuilder().withLeaseId(leaseId).build()))
                .commit()
                .get(5, TimeUnit.SECONDS);

        if (txn.isSucceeded()) {
            this.leaderLeaseId = leaseId;
            this.leaderKeepAliveClient = keepAlive;
            becomeLeader();
            while (running && isLeader.get()) {
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } else {
            try {
                keepAlive.close();
            } catch (Exception e) {
                log.warn("Error closing leader keepAlive client for follower", e);
            }
            try {
                leaseClient.revoke(leaseId).get(3, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("Error revoking temporary leader lease", e);
            }
            waitForExistingLeaderToDisappear();
        }
    }

    /** Опрашивает ключ лидера, пока он не исчезнет (текущий лидер упал или отключился). */
    private void waitForExistingLeaderToDisappear() {
        try {
            KV kv = client.getKVClient();
            ByteSequence leaderKey = bs(properties.leaderKey());
            while (running && !Thread.currentThread().isInterrupted()) {
                GetResponse resp = kv.get(leaderKey).get(3, TimeUnit.SECONDS);
                if (resp.getKvs().isEmpty()) {
                    return;
                }
                Thread.sleep(1000L);
            }
        } catch (Exception e) {
            log.warn("Error while waiting for leader key to disappear", e);
        }
    }

    /** Переход в роль лидера: включает watch на instances и запускает первый перерасчёт (с debounce). */
    private void becomeLeader() {
        if (!isLeader.compareAndSet(false, true)) {
            return;
        }
        log.info("Instance {} became leader", instanceRegistry.getInstanceId());
        startInstancesWatcher();
        scheduleRecalculationDebounced();
    }

    /** Подписывается на изменения ключей под префиксом instances/; при любом событии планирует перерасчёт. */
    private void startInstancesWatcher() {
        try {
            Watch watchClient = client.getWatchClient();
            ByteSequence prefix = bs(properties.instancesPrefix() + "/");
            WatchOption option = WatchOption.newBuilder()
                    .withPrefix(prefix)
                    .build();

            instancesWatcher = watchClient.watch(prefix, option, Watch.listener(response -> {
                scheduleRecalculationDebounced();
            }));
        } catch (Exception e) {
            log.warn("Failed to start instances watcher", e);
        }
    }

    /** Откладывает перерасчёт на 300 мс, отменяя предыдущую отложенную задачу (debounce). */
    private void scheduleRecalculationDebounced() {
        synchronized (debounceLock) {
            if (pendingRecalcTask != null && !pendingRecalcTask.isDone()) {
                pendingRecalcTask.cancel(false);
            }
            pendingRecalcTask = scheduler.schedule(this::safeRecalculateIfLeader,
                    300,
                    TimeUnit.MILLISECONDS);
        }
    }

    /** Если мы лидер и сервис ещё работает — выполняет перерасчёт и публикацию RPS. */
    private void safeRecalculateIfLeader() {
        if (!isLeader.get() || !running) {
            return;
        }
        try {
            recalculateAndPublish();
        } catch (Exception e) {
            log.warn("RPS recalculation failed", e);
        }
    }

    /**
     * Берёт список активных инстансов, конфиг inbox→totalRPS, делит RPS (base + remainder по первым),
     * пишет для каждого инстанса JSON в /rps-service/rps/{instanceId}.
     */
    private void recalculateAndPublish() throws Exception {
        Map<String, Integer> inboxTotal = rpsConfigLoader.getInboxTotalRps();
        if (inboxTotal.isEmpty()) {
            log.warn("No inbox configuration found, skipping RPS distribution");
            return;
        }

        List<String> instances = fetchActiveInstances();
        if (instances.isEmpty()) {
            log.info("No active instances, skipping RPS distribution");
            return;
        }

        Map<String, Map<String, Integer>> perInstance = new HashMap<>();
        for (String instance : instances) {
            perInstance.put(instance, new LinkedHashMap<>());
        }

        for (Map.Entry<String, Integer> entry : inboxTotal.entrySet()) {
            String inboxId = entry.getKey();
            int totalRps = entry.getValue();
            int n = instances.size();
            int base = totalRps / n;
            int remainder = totalRps % n;

            for (int i = 0; i < n; i++) {
                String instanceId = instances.get(i);
                int value = base + (i < remainder ? 1 : 0);
                perInstance.get(instanceId).put(inboxId, value);
            }
        }

        KV kv = client.getKVClient();
        for (Map.Entry<String, Map<String, Integer>> entry : perInstance.entrySet()) {
            String instanceId = entry.getKey();
            Map<String, Integer> limits = entry.getValue();
            String key = properties.rpsPrefix() + "/" + instanceId;
            String json = objectMapper.writeValueAsString(limits);
            kv.put(bs(key), bs(json)).get(5, TimeUnit.SECONDS);
        }

        log.info("Published RPS distribution for {} instances", instances.size());
    }

    /** Читает ключи под префиксом instances/, извлекает instanceId из пути, возвращает отсортированный список. */
    private List<String> fetchActiveInstances() throws Exception {
        KV kvClient = client.getKVClient();
        ByteSequence prefix = bs(properties.instancesPrefix() + "/");
        GetOption option = GetOption.newBuilder()
                .withPrefix(prefix)
                .build();
        GetResponse resp = kvClient.get(prefix, option).get(5, TimeUnit.SECONDS);

        List<String> instances = new ArrayList<>();
        resp.getKvs().forEach(kvEntry -> {
            String key = kvEntry.getKey().toString(StandardCharsets.UTF_8);
            int idx = key.lastIndexOf('/');
            if (idx >= 0 && idx < key.length() - 1) {
                instances.add(key.substring(idx + 1));
            }
        });
        instances.sort(Comparator.naturalOrder());
        return instances;
    }

    /** При остановке: снимает лидерство, закрывает watcher и keepAlive, ревокает leader lease, останавливает scheduler. */
    @javax.annotation.PreDestroy
    public void shutdown() {
        running = false;
        isLeader.set(false);

        try {
            if (instancesWatcher != null) {
                instancesWatcher.close();
            }
        } catch (Exception e) {
            log.warn("Error closing instances watcher", e);
        }

        try {
            if (leaderKeepAliveClient != null) {
                leaderKeepAliveClient.close();
            }
        } catch (Exception e) {
            log.warn("Error closing leader keepAlive client", e);
        }

        try {
            if (leaderLeaseId != 0L) {
                client.getLeaseClient().revoke(leaderLeaseId).get(3, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            log.warn("Error revoking leader lease", e);
        }

        scheduler.shutdownNow();
    }

    /** Вспомогательный метод: строка в ByteSequence для API jetcd. */
    private static ByteSequence bs(String value) {
        return ByteSequence.from(value, StandardCharsets.UTF_8);
    }
}

