package com.komeowmeow.qqstudymonitor.application;

import java.util.List;

public record IncomingMessage(String text, String senderName, List<ImageAttachment> images, boolean mentionsBot) {

    private static final int MAX_IMAGES = 3;

    public IncomingMessage {
        images = images == null ? List.of() : List.copyOf(images.stream()
                .filter(ImageAttachment::isPresent)
                .limit(MAX_IMAGES)
                .toList());
        text = text == null ? "" : text.trim();
        senderName = senderName == null ? "" : senderName.replace('\n', ' ').replace('\r', ' ').trim();
    }

    public boolean isBlank() {
        return text.isBlank() && images.isEmpty();
    }

    public boolean hasImages() {
        return !images.isEmpty();
    }

    public String effectiveText() {
        return text.isBlank() ? "请根据图片内容处理" : text;
    }
}
