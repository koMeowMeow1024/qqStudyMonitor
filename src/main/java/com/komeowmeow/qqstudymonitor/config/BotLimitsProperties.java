package com.komeowmeow.qqstudymonitor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bot.limits")
public class BotLimitsProperties {
    private long imageMaxBytesPerImage = 5 * 1024 * 1024;
    private long imageMaxTotalBytes = 8 * 1024 * 1024;
    private int privateContextMaxMessages = 12;

    public long getImageMaxBytesPerImage() {
        return imageMaxBytesPerImage;
    }

    public void setImageMaxBytesPerImage(long imageMaxBytesPerImage) {
        this.imageMaxBytesPerImage = imageMaxBytesPerImage;
    }

    public long getImageMaxTotalBytes() {
        return imageMaxTotalBytes;
    }

    public void setImageMaxTotalBytes(long imageMaxTotalBytes) {
        this.imageMaxTotalBytes = imageMaxTotalBytes;
    }

    public int getPrivateContextMaxMessages() {
        return privateContextMaxMessages;
    }

    public void setPrivateContextMaxMessages(int privateContextMaxMessages) {
        this.privateContextMaxMessages = privateContextMaxMessages;
    }
}
