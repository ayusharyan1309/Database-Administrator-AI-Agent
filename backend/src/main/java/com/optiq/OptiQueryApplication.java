package com.optiq;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class OptiQueryApplication {

    public static void main(String[] args) {
        SpringApplication.run(OptiQueryApplication.class, args);
    }
}
