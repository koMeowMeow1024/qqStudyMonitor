package com.komeowmeow.qqstudymonitor.application;

import com.komeowmeow.qqstudymonitor.config.StudyMonitorProperties;
import org.springframework.stereotype.Component;

@Component
public class RoleGuard {
    private final StudyMonitorProperties properties;

    public RoleGuard(StudyMonitorProperties properties) {
        this.properties = properties;
    }

    public boolean canPublishOrClose(long userId) {
        return properties.getTeacherIds().contains(userId)
                || properties.getCommitteeIds().contains(userId);
    }

    public boolean isTeacher(long userId) {
        return properties.getTeacherIds().contains(userId);
    }
}
