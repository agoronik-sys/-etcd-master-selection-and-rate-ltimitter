package com.example.rpsservice.config;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Загружает конфигурацию RPS из YAML (rps-config.yml): список inbox-каналов и общий лимит RPS для каждого.
 */
@Component
public class RpsConfigLoader {

    private final Map<String, Integer> inboxTotalRps;

    public RpsConfigLoader(ResourceLoader resourceLoader) {
        this.inboxTotalRps = loadConfig(resourceLoader);
    }

    /**
     * Читает classpath:rps-config.yml, парсит секцию rps-inbox (id + rps) и возвращает неизменяемую карту.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Integer> loadConfig(ResourceLoader resourceLoader) {
        try {
            Resource resource = resourceLoader.getResource("classpath:rps-config.yml");
            if (!resource.exists()) {
                throw new IllegalStateException("rps-config.yml not found on classpath");
            }

            try (InputStream is = resource.getInputStream()) {
                Yaml yaml = new Yaml();
                Object root = yaml.load(is);
                if (!(root instanceof Map<?, ?> rootMap)) {
                    throw new IllegalStateException("rps-config.yml has unexpected structure");
                }

                Object listObj = rootMap.get("rps-inbox");
                if (!(listObj instanceof List<?> list)) {
                    throw new IllegalStateException("rps-config.yml must contain 'rps-inbox' list");
                }

                Map<String, Integer> result = new LinkedHashMap<>();
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> m)) {
                        continue;
                    }
                    Object idObj = m.get("id");
                    Object rpsObj = m.get("rps");
                    if (idObj == null || rpsObj == null) {
                        continue;
                    }
                    String id = Objects.toString(idObj);
                    int rps = ((Number) rpsObj).intValue();
                    result.put(id, rps);
                }
                return Collections.unmodifiableMap(result);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load rps-config.yml", e);
        }
    }

    /**
     * Возвращает карту «inbox id → общий лимит RPS» для расчёта распределения лидером.
     */
    public Map<String, Integer> getInboxTotalRps() {
        return inboxTotalRps;
    }
}

