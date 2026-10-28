package com.ray.web3;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan("com.ray.web3.mapper")
public class Web3BackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(Web3BackendApplication.class, args);
    }
}
