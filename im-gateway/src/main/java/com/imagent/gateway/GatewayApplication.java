package com.imagent.gateway;

import com.imagent.common.security.JwtTokenUtil;
import com.imagent.gateway.config.GatewayProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }

    @Bean
    public JwtTokenUtil jwtTokenUtil(GatewayProperties properties) {
        return new JwtTokenUtil(properties.getJwtSecret(), properties.getJwtExpireSeconds());
    }
}
