package com.example.rpsservice.web;

import com.example.rpsservice.config.EtcdProperties;
import com.example.rpsservice.etcd.InstanceRegistry;
import com.example.rpsservice.service.LocalRpsLimitHolder;
import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.KV;
import io.etcd.jetcd.kv.GetResponse;
import io.etcd.jetcd.options.GetOption;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * REST-контроллер: отдаёт текущие RPS-лимиты инстанса и список ключей в etcd в нашей области.
 */
@RestController
public class RpsLimitsController {

    private final InstanceRegistry instanceRegistry;
    private final LocalRpsLimitHolder limitHolder;
    private final Client etcdClient;
    private final EtcdProperties etcdProperties;

    public RpsLimitsController(
            InstanceRegistry instanceRegistry,
            LocalRpsLimitHolder limitHolder,
            Client etcdClient,
            EtcdProperties etcdProperties
    ) {
        this.instanceRegistry = instanceRegistry;
        this.limitHolder = limitHolder;
        this.etcdClient = etcdClient;
        this.etcdProperties = etcdProperties;
    }

    /**
     * Текущие RPS-лимиты этого инстанса по каналам (inbox id → лимит).
     * Значения приходят из etcd и обновляются через {@link com.example.rpsservice.etcd.InstanceRpsWatcher}.
     *
     * @return карта с ключами instanceId и limits (inbox → rps)
     */
    @GetMapping("/limits")
    public Map<String, Object> currentLimits() {
        Map<String, Object> result = new HashMap<>();
        result.put("instanceId", instanceRegistry.getInstanceId());
        result.put("limits", limitHolder.getCurrentLimits());
        return result;
    }

    /**
     * Список всех ключей в etcd под префиксом сервиса (instances, leader, rps/*).
     * Нужен для отладки и проверки состояния кластера.
     *
     * @return rootPrefix и список ключей (строки)
     */
    @GetMapping("/etcd/keys")
    public Map<String, Object> etcdKeys() throws Exception {
        KV kv = etcdClient.getKVClient();
        String prefixStr = etcdProperties.getRootPrefix();
        if (!prefixStr.endsWith("/")) {
            prefixStr = prefixStr + "/";
        }
        ByteSequence prefix = ByteSequence.from(prefixStr, StandardCharsets.UTF_8);

        GetOption option = GetOption.newBuilder()
                .withPrefix(prefix)
                .build();

        GetResponse response = kv.get(prefix, option).get(5, TimeUnit.SECONDS);

        List<String> keys = new ArrayList<>();
        response.getKvs().forEach(kvEntry ->
                keys.add(kvEntry.getKey().toString(StandardCharsets.UTF_8))
        );

        Map<String, Object> result = new HashMap<>();
        result.put("rootPrefix", etcdProperties.getRootPrefix());
        result.put("keys", keys);
        return result;
    }
}

