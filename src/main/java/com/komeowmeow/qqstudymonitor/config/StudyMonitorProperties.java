package com.komeowmeow.qqstudymonitor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "bot.study")
public class StudyMonitorProperties {
    private List<Long> monitoredGroupIds = new ArrayList<>();
    private List<Long> teacherIds = new ArrayList<>();
    private List<Long> committeeIds = new ArrayList<>();
    private int reminderLeadMinutes = 30;

    public List<Long> getMonitoredGroupIds() {
        return monitoredGroupIds;
    }

    public void setMonitoredGroupIds(List<Long> monitoredGroupIds) {
        this.monitoredGroupIds = monitoredGroupIds == null ? new ArrayList<>() : monitoredGroupIds;
    }

    public List<Long> getTeacherIds() {
        return teacherIds;
    }

    public void setTeacherIds(List<Long> teacherIds) {
        this.teacherIds = teacherIds == null ? new ArrayList<>() : teacherIds;
    }

    public List<Long> getCommitteeIds() {
        return committeeIds;
    }

    public void setCommitteeIds(List<Long> committeeIds) {
        this.committeeIds = committeeIds == null ? new ArrayList<>() : committeeIds;
    }

    public int getReminderLeadMinutes() {
        return reminderLeadMinutes;
    }

    public void setReminderLeadMinutes(int reminderLeadMinutes) {
        this.reminderLeadMinutes = reminderLeadMinutes;
    }

    public boolean isMonitoredGroup(long groupId) {
        return monitoredGroupIds.isEmpty() || monitoredGroupIds.contains(groupId);
    }
}
