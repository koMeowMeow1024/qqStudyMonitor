package com.komeowmeow.qqstudymonitor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

@ConfigurationProperties(prefix = "bot.qq")
public class BotConfig {
    private boolean enabled = true;
    private URI oneBotWebSocketUrl;
    private String accessToken;
    private long selfId;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public URI getOneBotWebSocketUrl() {
        return oneBotWebSocketUrl;
    }

    public void setOneBotWebSocketUrl(URI oneBotWebSocketUrl) {
        this.oneBotWebSocketUrl = oneBotWebSocketUrl;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public long getSelfId() {
        return selfId;
    }

    public void setSelfId(long selfId) {
        this.selfId = selfId;
    }
}
