package com.example.rpsservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Настройки подключения к etcd и префиксов ключей для RPS-сервиса.
 * Читаются из application.yml (префикс {@code etcd}).
 */
@ConfigurationProperties(prefix = "etcd")
public class EtcdProperties {

    private List<String> endpoints = new ArrayList<>();
    private String rootPrefix = "/rps-service";
    private long instanceTtlSeconds = 10;
    private long leaderTtlSeconds = 10;

    /** Список URL etcd (например, http://localhost:2379). */
    public List<String> getEndpoints() {
        return endpoints;
    }

    public void setEndpoints(List<String> endpoints) {
        this.endpoints = endpoints;
    }

    /** Корневой префикс всех ключей сервиса в etcd. */
    public String getRootPrefix() {
        return rootPrefix;
    }

    public void setRootPrefix(String rootPrefix) {
        this.rootPrefix = rootPrefix;
    }

    /** TTL в секундах для ключа регистрации инстанса (lease). */
    public long getInstanceTtlSeconds() {
        return instanceTtlSeconds;
    }

    public void setInstanceTtlSeconds(long instanceTtlSeconds) {
        this.instanceTtlSeconds = instanceTtlSeconds;
    }

    /** TTL в секундах для ключа лидера (lease). */
    public long getLeaderTtlSeconds() {
        return leaderTtlSeconds;
    }

    public void setLeaderTtlSeconds(long leaderTtlSeconds) {
        this.leaderTtlSeconds = leaderTtlSeconds;
    }

    /** TTL регистрации инстанса как Duration. */
    public Duration instanceTtl() {
        return Duration.ofSeconds(instanceTtlSeconds);
    }

    /** TTL лидера как Duration. */
    public Duration leaderTtl() {
        return Duration.ofSeconds(leaderTtlSeconds);
    }

    /** Префикс ключей активных инстансов: {@code /rps-service/instances}. */
    public String instancesPrefix() {
        return rootPrefix + "/instances";
    }

    /** Ключ лидера: {@code /rps-service/leader}. */
    public String leaderKey() {
        return rootPrefix + "/leader";
    }

    /** Префикс ключей с RPS-лимитами по инстансам: {@code /rps-service/rps}. */
    public String rpsPrefix() {
        return rootPrefix + "/rps";
    }
}

