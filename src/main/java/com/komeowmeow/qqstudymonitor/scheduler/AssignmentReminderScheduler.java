package com.komeowmeow.qqstudymonitor.scheduler;

import com.komeowmeow.qqstudymonitor.adapter.qq.QqClient;
import com.komeowmeow.qqstudymonitor.config.StudyMonitorProperties;
import com.komeowmeow.qqstudymonitor.infrastructure.persistence.AssignmentRepository;
import com.komeowmeow.qqstudymonitor.infrastructure.persistence.MemberStatusRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

@Component
public class AssignmentReminderScheduler {
    private static final Logger log = LoggerFactory.getLogger(AssignmentReminderScheduler.class);

    private final AssignmentRepository assignmentRepository;
    private final MemberStatusRepository memberStatusRepository;
    private final StudyMonitorProperties properties;
    private final QqClient qqClient;

    public AssignmentReminderScheduler(
            AssignmentRepository assignmentRepository,
            MemberStatusRepository memberStatusRepository,
            StudyMonitorProperties properties,
            QqClient qqClient) {
        this.assignmentRepository = assignmentRepository;
        this.memberStatusRepository = memberStatusRepository;
        this.properties = properties;
        this.qqClient = qqClient;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void remindDueAssignments() {
        LocalDateTime now = LocalDateTime.now();
        var dueRows = assignmentRepository.findDueForReminder(now, properties.getReminderLeadMinutes());
        for (AssignmentRepository.AssignmentRow assignment : dueRows) {
            if (!properties.isMonitoredGroup(assignment.groupId())) {
                continue;
            }
            var pending = memberStatusRepository.listPending(assignment.id());
            if (pending.isEmpty()) {
                assignmentRepository.markReminded(assignment.id());
                continue;
            }
            String names = pending.stream()
                    .map(row -> row.userName() == null || row.userName().isBlank()
                            ? String.valueOf(row.userId())
                            : row.userName())
                    .reduce((a, b) -> a + "、" + b)
                    .orElse("");
            String text = "【截止提醒】《" + assignment.title() + "》"
                    + (assignment.dueAt() == null || assignment.dueAt().isBlank() ? "" : " 截止 " + assignment.dueAt())
                    + "，尚未提交：" + names;
            try {
                CompletableFuture<Void> send = qqClient.sendGroupMessage(assignment.groupId(), text);
                send.join();
                assignmentRepository.markReminded(assignment.id());
                log.info("已提醒群 {} 作业 {}", assignment.groupId(), assignment.title());
            } catch (Exception error) {
                log.warn("发送截止提醒失败，assignment={}", assignment.id(), error);
            }
        }
    }
}
