package com.example.rpsservice;

import com.example.rpsservice.config.EtcdProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Точка входа приложения: сервис распределения RPS между инстансами с координацией через etcd.
 */
@SpringBootApplication
@EnableConfigurationProperties(EtcdProperties.class)
public class RpsServiceApplication {

    /**
     * Запуск Spring Boot приложения.
     *
     * @param args аргументы командной строки (например, --instance.id=node1, --server.port=8081)
     */
    public static void main(String[] args) {
        SpringApplication.run(RpsServiceApplication.class, args);
    }
}


