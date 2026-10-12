package com.resumeai.stability;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.resumeai.stability.config.AiProperties;

@SpringBootApplication
@EnableConfigurationProperties(AiProperties.class)
public class StabilityApplication {
    public static void main(String[] args) {
        SpringApplication.run(StabilityApplication.class, args);
    }
}
