package com.example.rpsservice.etcd;

import com.example.rpsservice.config.EtcdProperties;
import com.example.rpsservice.service.LocalRpsLimitHolder;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.etcd.jetcd.*;
import io.etcd.jetcd.kv.GetResponse;
import io.etcd.jetcd.options.GetOption;
import io.etcd.jetcd.options.WatchOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Следит за ключом /rps-service/rps/{instanceId} в etcd: при изменении парсит JSON и обновляет локальные лимиты в {@link LocalRpsLimitHolder}.
 * При восстановлении соединения с etcd ({@link EtcdReconnectedEvent}) переподключает watch и перечитывает лимиты.
 */
@Component
public class InstanceRpsWatcher {

    private static final Logger log = LoggerFactory.getLogger(InstanceRpsWatcher.class);

    private final Client client;
    private final EtcdProperties properties;
    private final InstanceRegistry instanceRegistry;
    private final LocalRpsLimitHolder limitHolder;
    private final ObjectMapper objectMapper;

    private volatile Watch.Watcher watcher;
    private volatile ByteSequence rpsKey;

    public InstanceRpsWatcher(
            Client client,
            EtcdProperties properties,
            InstanceRegistry instanceRegistry,
            LocalRpsLimitHolder limitHolder,
            ObjectMapper objectMapper
    ) {
        this.client = client;
        this.properties = properties;
        this.instanceRegistry = instanceRegistry;
        this.limitHolder = limitHolder;
        this.objectMapper = objectMapper;
    }

    /**
     * После старта приложения: один раз читает текущее значение ключа RPS, затем подписывается на watch.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        String key = properties.rpsPrefix() + "/" + instanceRegistry.getInstanceId();
        this.rpsKey = ByteSequence.from(key, StandardCharsets.UTF_8);
        readInitialValue(rpsKey);
        startWatch(rpsKey);
    }

    /**
     * При восстановлении соединения с etcd: закрывает старый watch, заново читает лимиты и подписывается на watch.
     * Это обеспечивает получение RPS после перезапуска etcd без перезапуска сервиса.
     */
    @EventListener(EtcdReconnectedEvent.class)
    public void onEtcdReconnected() {
        if (rpsKey == null) {
            return;
        }
        try {
            closeWatcher();
            readInitialValue(rpsKey);
            startWatch(rpsKey);
            log.info("RPS watch re-established after etcd reconnection");
        } catch (Exception e) {
            log.warn("Failed to re-establish RPS watch after etcd reconnection", e);
        }
    }

    /** Читает текущее значение ключа RPS из etcd и обновляет {@link LocalRpsLimitHolder}. */
    private void readInitialValue(ByteSequence key) {
        try {
            KV kv = client.getKVClient();
            GetOption option = GetOption.newBuilder().build();
            GetResponse resp = kv.get(key, option).get(3, TimeUnit.SECONDS);
            if (!resp.getKvs().isEmpty()) {
                String json = resp.getKvs().getFirst().getValue().toString(StandardCharsets.UTF_8);
                Map<String, Integer> limits = parseLimits(json);
                limitHolder.updateLimits(limits);
                log.info("Initial RPS limits loaded: {}", limits);
            }
        } catch (Exception e) {
            log.warn("Failed to read initial RPS limits", e);
        }
    }

    /** Запускает watch на ключ RPS этого инстанса; при каждом событии обновляет лимиты. */
    private void startWatch(ByteSequence key) {
        Watch watchClient = client.getWatchClient();
        WatchOption option = WatchOption.newBuilder().build();

        watcher = watchClient.watch(key, option, Watch.listener(response -> {
            response.getEvents().forEach(event -> {
                KeyValue kv = event.getKeyValue();
                String json = kv.getValue().toString(StandardCharsets.UTF_8);
                Map<String, Integer> limits = parseLimits(json);
                limitHolder.updateLimits(limits);
                log.info("Updated RPS limits: {}", limits);
            });
        }));
    }

    /** Парсит JSON вида {"cf":10,"sb":7,"tc":5} в карту inbox → RPS; при ошибке возвращает пустую карту. */
    private Map<String, Integer> parseLimits(String json) {
        try {
            if (json == null || json.isBlank()) {
                return Collections.emptyMap();
            }
            return objectMapper.readValue(json, new TypeReference<Map<String, Integer>>() {
            });
        } catch (Exception e) {
            log.warn("Failed to parse RPS limits JSON: {}", json, e);
            return Collections.emptyMap();
        }
    }

    private void closeWatcher() {
        try {
            if (watcher != null) {
                watcher.close();
                watcher = null;
            }
        } catch (Exception e) {
            log.debug("Error closing RPS watcher", e);
        }
    }

    /** При остановке контекста закрывает watcher. */
    @javax.annotation.PreDestroy
    public void shutdown() {
        closeWatcher();
    }
}

