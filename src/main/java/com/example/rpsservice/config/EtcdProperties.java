package com.example.rpsservice.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Настройки подключения к etcd и префиксов ключей для RPS-сервиса.
 * Читаются из application.yml (префикс {@code etcd}).
 */
@ConfigurationProperties(prefix = "etcd")
@Getter
@Setter
public class EtcdProperties {

    /** Список URL etcd (например, http://localhost:2379). */
    private List<String> endpoints = new ArrayList<>();
    /** Корневой префикс всех ключей сервиса в etcd. */
    private String rootPrefix = "/rps-service";
    /** TTL в секундах для ключа регистрации инстанса (lease). */
    private long instanceTtlSeconds = 10;
    /** TTL в секундах для ключа лидера (lease). */
    private long leaderTtlSeconds = 10;

    private Ssl ssl = new Ssl();

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

    /** Включён ли TLS. */
    public boolean isSslEnabled() {
        return ssl != null && ssl.isEnabled();
    }

    /** Настройки TLS для подключения к etcd (keystore + truststore). */
    @Getter
    @Setter
    public static class Ssl {
        private boolean enabled = false;
        private String keystorePath;
        private String keystorePassword;
        private String keystoreType = "PKCS12";
        private String trustStorePath;
        private String trustStorePassword;
        private String trustStoreType = "PKCS12";
        @Deprecated
        private String certificatePath;
        @Deprecated
        private String certificatePassword;

        /** Эффективный путь к keystore: keystore-path или certificate-path (обратная совместимость). */
        public String getEffectiveKeystorePath() {
            return (keystorePath != null && !keystorePath.isBlank()) ? keystorePath : certificatePath;
        }

        /** Эффективный пароль keystore. */
        public String getEffectiveKeystorePassword() {
            return keystorePassword != null ? keystorePassword : certificatePassword;
        }

        /** Эффективный тип keystore. */
        public String getEffectiveKeystoreType() {
            return (keystoreType != null && !keystoreType.isBlank()) ? keystoreType : "PKCS12";
        }
    }
}
