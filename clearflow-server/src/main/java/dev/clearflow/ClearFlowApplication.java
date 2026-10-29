package dev.clearflow;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("dev.clearflow")
@EnableScheduling
public class ClearFlowApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClearFlowApplication.class, args);
    }
}
