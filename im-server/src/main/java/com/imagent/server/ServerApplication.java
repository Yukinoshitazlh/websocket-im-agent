package com.imagent.server;

import com.imagent.common.security.JwtTokenUtil;
import com.imagent.server.config.RateLimitProperties;
import com.imagent.server.config.ServerSecurityProperties;
import com.imagent.server.config.StorageProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@SpringBootApplication
@EnableScheduling
@MapperScan("com.imagent.server.mapper")
@EnableConfigurationProperties({ServerSecurityProperties.class, RateLimitProperties.class, StorageProperties.class})
public class ServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServerApplication.class, args);
    }

    @Bean
    public JwtTokenUtil jwtTokenUtil(ServerSecurityProperties properties) {
        return new JwtTokenUtil(properties.getJwtSecret(), properties.getJwtExpireSeconds());
    }

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
