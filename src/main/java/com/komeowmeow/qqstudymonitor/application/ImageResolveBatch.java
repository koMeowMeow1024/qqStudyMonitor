package com.komeowmeow.qqstudymonitor.application;

import java.util.List;

public record ImageResolveBatch(List<ImageAttachment> images, boolean rejectedOversized) {
    public ImageResolveBatch {
        images = images == null ? List.of() : List.copyOf(images);
    }
}
