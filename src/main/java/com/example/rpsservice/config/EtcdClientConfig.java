package com.example.rpsservice.config;

import io.etcd.jetcd.Client;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Создаёт и регистрирует клиент etcd для работы с KV, Lease и Watch.
 */
@Configuration
public class EtcdClientConfig {

    /**
     * Собирает jetcd Client по адресам из {@link EtcdProperties#getEndpoints()}.
     * Используется для регистрации инстанса, выбора лидера и чтения/записи RPS-лимитов.
     *
     * @param properties настройки etcd (endpoints обязательны)
     * @return настроенный клиент etcd
     */
    @Bean
    public Client etcdClient(EtcdProperties properties) {
        List<String> endpoints = properties.getEndpoints();
        if (endpoints == null || endpoints.isEmpty()) {
            throw new IllegalStateException("etcd.endpoints must be configured");
        }
        return Client.builder()
                .endpoints(endpoints.toArray(new String[0]))
                .build();
    }
}

