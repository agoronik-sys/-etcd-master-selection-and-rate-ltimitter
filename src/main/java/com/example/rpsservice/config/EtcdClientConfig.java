package com.example.rpsservice.config;

import io.etcd.jetcd.Client;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.security.KeyStore;
import java.util.List;

/**
 * Создаёт и регистрирует клиент etcd для работы с KV, Lease и Watch.
 * При {@code etcd.ssl.enabled=true} поднимает TLS с загрузкой клиентского сертификата из PFX.
 */
@Configuration
public class EtcdClientConfig {

    private static final Logger log = LoggerFactory.getLogger(EtcdClientConfig.class);

    /**
     * Собирает jetcd Client по адресам из {@link EtcdProperties#getEndpoints()}.
     * При включённом SSL загружает PFX из {@code etcd.ssl.certificate-path} и опционально trust store.
     *
     * @param properties настройки etcd (endpoints обязательны)
     * @param resourceLoader для загрузки classpath:/file: ресурсов сертификатов
     * @return настроенный клиент etcd
     */
    @Bean
    public Client etcdClient(EtcdProperties properties, ResourceLoader resourceLoader) {
        List<String> endpoints = properties.getEndpoints();
        if (endpoints == null || endpoints.isEmpty()) {
            throw new IllegalStateException("etcd.endpoints must be configured");
        }
        var builder = Client.builder()
                .endpoints(endpoints.toArray(new String[0]));

        if (properties.isSslEnabled() && properties.getSsl() != null) {
            SslContext sslContext = buildSslContext(properties.getSsl(), resourceLoader);
            builder.sslContext(sslContext);
            log.info("etcd client configured with TLS (keystore: {}, truststore: {})",
                    properties.getSsl().getEffectiveKeystorePath(),
                    properties.getSsl().getTrustStorePath() != null ? properties.getSsl().getTrustStorePath() : "default");
        }

        return builder.build();
    }

    private SslContext buildSslContext(EtcdProperties.Ssl ssl, ResourceLoader resourceLoader) {
        String keystorePath = ssl.getEffectiveKeystorePath();
        if (keystorePath == null || keystorePath.isBlank()) {
            throw new IllegalStateException("etcd.ssl.enabled is true but etcd.ssl.keystore-path (or certificate-path) is not set");
        }
        char[] keystorePassword = ssl.getEffectiveKeystorePassword() != null
                ? ssl.getEffectiveKeystorePassword().toCharArray()
                : new char[0];
        String keystoreType = ssl.getEffectiveKeystoreType();

        try {
            KeyStore keyStore = KeyStore.getInstance(keystoreType);
            try (InputStream is = resourceLoader.getResource(keystorePath).getInputStream()) {
                keyStore.load(is, keystorePassword);
            }

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, keystorePassword);

            SslContextBuilder sslBuilder = SslContextBuilder.forClient().keyManager(kmf);

            String trustPath = ssl.getTrustStorePath();
            if (trustPath != null && !trustPath.isBlank()) {
                char[] trustPassword = ssl.getTrustStorePassword() != null
                        ? ssl.getTrustStorePassword().toCharArray()
                        : new char[0];
                String trustType = ssl.getTrustStoreType() != null && !ssl.getTrustStoreType().isBlank()
                        ? ssl.getTrustStoreType()
                        : "PKCS12";
                KeyStore trustStore = KeyStore.getInstance(trustType);
                try (InputStream is = resourceLoader.getResource(trustPath).getInputStream()) {
                    trustStore.load(is, trustPassword);
                }
                TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(trustStore);
                sslBuilder.trustManager(tmf);
            }

            return sslBuilder.build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build SSL context for etcd (keystore: " + keystorePath + ")", e);
        }
    }
}

