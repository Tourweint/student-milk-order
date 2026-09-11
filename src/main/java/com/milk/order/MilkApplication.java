package com.milk.order;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 学生奶订购系统启动类
 */
@SpringBootApplication
@EnableScheduling
@MapperScan("com.milk.order.module.**.mapper")
public class MilkApplication {

    public static void main(String[] args) {
        SpringApplication.run(MilkApplication.class, args);
    }
}
