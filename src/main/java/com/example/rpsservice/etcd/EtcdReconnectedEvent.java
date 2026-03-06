package com.example.rpsservice.etcd;

import org.springframework.context.ApplicationEvent;

/**
 * Публикуется при успешном восстановлении соединения с etcd после отвала
 * (например, после перезапуска etcd). Слушатели должны переподключить watch'и
 * и перечитать состояние.
 */
public class EtcdReconnectedEvent extends ApplicationEvent {

    public EtcdReconnectedEvent(Object source) {
        super(source);
    }
}
