package com.ray.web3;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
// 显式绑定业务池的 SqlSessionTemplate：应用里还有一个账本池的 template，不指定会变成按类型注入的偶然行为
@MapperScan(basePackages = "com.ray.web3.mapper", sqlSessionTemplateRef = "sqlSessionTemplate")
public class Web3BackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(Web3BackendApplication.class, args);
    }
}
