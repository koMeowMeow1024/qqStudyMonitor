package com.komeowmeow.qqstudymonitor;

import com.komeowmeow.qqstudymonitor.config.BotConfig;
import com.komeowmeow.qqstudymonitor.config.BotLimitsProperties;
import com.komeowmeow.qqstudymonitor.config.StudyMonitorProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({BotConfig.class, BotLimitsProperties.class, StudyMonitorProperties.class})
public class QqStudyMonitorApplication {
    public static void main(String[] args) {
        SpringApplication.run(QqStudyMonitorApplication.class, args);
    }
}
