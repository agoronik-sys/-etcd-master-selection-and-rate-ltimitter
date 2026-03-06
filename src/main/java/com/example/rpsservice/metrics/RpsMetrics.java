package com.example.rpsservice.metrics;

import com.example.rpsservice.config.EtcdProperties;
import com.example.rpsservice.config.RpsConfigLoader;
import com.example.rpsservice.etcd.InstanceRegistry;
import com.example.rpsservice.etcd.LeaderCoordinator;
import com.example.rpsservice.service.LocalRpsLimitHolder;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Регистрирует метрики Prometheus: настройки из конфига и состояние текущего инстанса (лидер, лимиты RPS).
 */
@Component
public class RpsMetrics {

    private static final String PREFIX = "rps_";

    public RpsMetrics(
            MeterRegistry registry,
            EtcdProperties etcdProperties,
            RpsConfigLoader rpsConfigLoader,
            InstanceRegistry instanceRegistry,
            LeaderCoordinator leaderCoordinator,
            LocalRpsLimitHolder limitHolder,
            @Value("${rps.reconciliation-interval-ms:30000}") long reconciliationIntervalMs
    ) {
        String instanceId = instanceRegistry.getInstanceId();

        // --- Конфиг (общий для всех инстансов) ---
        Gauge.builder(PREFIX + "etcd_instance_ttl_seconds", etcdProperties, p -> p.getInstanceTtlSeconds())
                .description("ETCD instance lease TTL from config (seconds)")
                .register(registry);

        Gauge.builder(PREFIX + "etcd_leader_ttl_seconds", etcdProperties, p -> p.getLeaderTtlSeconds())
                .description("ETCD leader lease TTL from config (seconds)")
                .register(registry);

        Gauge.builder(PREFIX + "etcd_ssl_enabled", etcdProperties, p -> etcdProperties.isSslEnabled() ? 1 : 0)
                .description("ETCD SSL enabled from config (1=yes, 0=no)")
                .register(registry);

        Gauge.builder(PREFIX + "config_reconciliation_interval_ms", () -> reconciliationIntervalMs)
                .description("RPS reconciliation interval from config (milliseconds)")
                .register(registry);

        for (Map.Entry<String, Integer> entry : rpsConfigLoader.getInboxTotalRps().entrySet()) {
            String inbox = entry.getKey();
            int totalRps = entry.getValue();
            Gauge.builder(PREFIX + "config_inbox_total_rps", () -> totalRps)
                    .tag("inbox", inbox)
                    .description("Total RPS per inbox from rps-config (cluster-wide)")
                    .register(registry);
        }

        // --- Текущий инстанс ---
        Gauge.builder(PREFIX + "instance_info", () -> 1)
                .tag("instance_id", instanceId)
                .description("Instance identity (always 1, use for grouping by instance_id)")
                .register(registry);

        Gauge.builder(PREFIX + "leader", leaderCoordinator, c -> leaderCoordinator.isCurrentLeader() ? 1 : 0)
                .tag("instance_id", instanceId)
                .description("Is this instance the leader (1=yes, 0=no)")
                .register(registry);

        // Лимиты по inbox для этого инстанса (значения из etcd, обновляются при перераспределении)
        for (String inbox : rpsConfigLoader.getInboxTotalRps().keySet()) {
            Gauge.builder(PREFIX + "limit", limitHolder, h -> {
                        Map<String, Integer> limits = h.getCurrentLimits();
                        return limits.getOrDefault(inbox, 0).doubleValue();
                    })
                    .tag("instance_id", instanceId)
                    .tag("inbox", inbox)
                    .description("Current RPS limit for this instance and inbox")
                    .register(registry);
        }
    }
}
