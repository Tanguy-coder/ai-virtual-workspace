package dev.aiw;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AiwApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiwApplication.class, args);
    }
}
