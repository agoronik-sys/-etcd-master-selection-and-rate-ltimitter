package com.example.rpsservice.service;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Хранит текущие RPS-лимиты этого инстанса по каналам (inbox id → лимит).
 * Обновляется из etcd через {@link com.example.rpsservice.etcd.InstanceRpsWatcher}; чтение — потокобезопасно.
 */
@Component
public class LocalRpsLimitHolder {

    private final AtomicReference<Map<String, Integer>> currentLimits =
            new AtomicReference<>(Collections.emptyMap());

    /**
     * Возвращает текущую карту лимитов (inbox id → RPS). Не изменяйте возвращаемую карту.
     */
    public Map<String, Integer> getCurrentLimits() {
        return currentLimits.get();
    }

    /**
     * Устанавливает новые лимиты (обычно вызывается при обновлении ключа в etcd).
     * Пустую карту игнорируем — не затираем текущие лимиты (защита от пустого ответа etcd при восстановлении).
     */
    public void updateLimits(Map<String, Integer> newLimits) {
        if (newLimits == null || newLimits.isEmpty()) {
            return;
        }
        currentLimits.set(Collections.unmodifiableMap(new HashMap<>(newLimits)));
    }
}

