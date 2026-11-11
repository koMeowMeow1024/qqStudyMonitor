package com.komeowmeow.qqstudymonitor.application;

public record ImageAttachment(String mimeType, String base64Data) {

    public ImageAttachment {
        if (mimeType == null || mimeType.isBlank()) {
            mimeType = "image/jpeg";
        }
        mimeType = mimeType.trim();
    }

    public boolean isPresent() {
        return base64Data != null && !base64Data.isBlank();
    }

    public long estimatedDecodedBytes() {
        if (!isPresent()) {
            return 0;
        }
        String normalized = base64Data.replaceAll("\\s", "");
        return (normalized.length() * 3L) / 4;
    }
}
