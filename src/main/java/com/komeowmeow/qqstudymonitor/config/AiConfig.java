package com.komeowmeow.qqstudymonitor.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.community.model.dashscope.QwenChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    public QwenChatModel qwenChatModel(
            @Value("${ai.dashscope.model:qwen3.7-flash}") String modelName) {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("未配置环境变量 DASHSCOPE_API_KEY");
        }
        return QwenChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .build();
    }
}
