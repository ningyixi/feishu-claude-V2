package com.lark.oapi.sample.ws;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling   // Skill Hook 的 cron 调度（SkillHookService.cronTick）
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
