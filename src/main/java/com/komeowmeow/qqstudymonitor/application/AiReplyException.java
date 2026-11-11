package com.komeowmeow.qqstudymonitor.application;

public class AiReplyException extends RuntimeException {
    private final String userMessage;

    public AiReplyException(String userMessage, Throwable cause) {
        super(userMessage, cause);
        this.userMessage = userMessage;
    }

    public String getUserMessage() {
        return userMessage;
    }
}
