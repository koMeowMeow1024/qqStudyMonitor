package com.komeowmeow.qqstudymonitor.application;

import com.komeowmeow.qqstudymonitor.config.StudyMonitorProperties;
import com.komeowmeow.qqstudymonitor.infrastructure.persistence.CourseMaterialRepository;
import com.komeowmeow.qqstudymonitor.infrastructure.persistence.MemberStatusRepository;
import com.komeowmeow.qqstudymonitor.infrastructure.persistence.PrivateMessageRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PrivateChatService {
    private final StudyMonitorProperties properties;
    private final StudyAiService studyAiService;
    private final PrivateMessageRepository privateMessageRepository;
    private final MemberStatusRepository memberStatusRepository;
    private final CourseMaterialRepository courseMaterialRepository;

    public PrivateChatService(
            StudyMonitorProperties properties,
            StudyAiService studyAiService,
            PrivateMessageRepository privateMessageRepository,
            MemberStatusRepository memberStatusRepository,
            CourseMaterialRepository courseMaterialRepository) {
        this.properties = properties;
        this.studyAiService = studyAiService;
        this.privateMessageRepository = privateMessageRepository;
        this.memberStatusRepository = memberStatusRepository;
        this.courseMaterialRepository = courseMaterialRepository;
    }

    public String handle(long userId, IncomingMessage message) {
        if (message.isBlank()) {
            return "可以发文字、题目截图，或问我「我还没交什么」。";
        }

        privateMessageRepository.append(userId, "user", message.effectiveText());

        if (isHomeworkStatusQuery(message.text())) {
            String answer = formatHomeworkStatus(userId);
            privateMessageRepository.append(userId, "assistant", answer);
            return answer;
        }

        String materials = formatMaterials();
        var history = privateMessageRepository.recentForUser(userId);
        String answer = studyAiService.tutorReply(message, materials, history);
        privateMessageRepository.append(userId, "assistant", answer);
        return answer;
    }

    private String formatHomeworkStatus(long userId) {
        var pending = memberStatusRepository.listPendingForUser(userId).stream()
                .filter(row -> properties.isMonitoredGroup(row.groupId()))
                .toList();
        if (pending.isEmpty()) {
            return "你没有未交的作业记录。";
        }
        boolean multiGroup = pending.stream().map(MemberStatusRepository.UserAssignmentStatusRow::groupId).distinct().count() > 1;
        StringBuilder builder = new StringBuilder("你还未交的作业：\n");
        for (var row : pending) {
            builder.append("· ");
            if (multiGroup) {
                builder.append("群 ").append(row.groupId()).append(' ');
            }
            builder.append(row.title());
            if (row.dueAt() != null && !row.dueAt().isBlank()) {
                builder.append("（截止 ").append(row.dueAt()).append('）');
            }
            builder.append('\n');
        }
        return builder.toString().trim();
    }

    private String formatMaterials() {
        List<Long> groups = properties.getMonitoredGroupIds();
        StringBuilder builder = new StringBuilder();
        if (groups.isEmpty()) {
            appendMaterials(builder, courseMaterialRepository.listRecent(8));
            return builder.toString();
        }
        for (Long groupId : groups) {
            appendMaterials(builder, courseMaterialRepository.listRecent(groupId, 5));
        }
        return builder.toString();
    }

    private static void appendMaterials(StringBuilder builder, List<CourseMaterialRepository.MaterialRow> materials) {
        for (var row : materials) {
            if (row.groupId() != 0) {
                builder.append("[群 ").append(row.groupId()).append("] ");
            }
            builder.append('-').append(row.title()).append("：").append(row.content());
            if (row.sourceNote() != null && !row.sourceNote().isBlank()) {
                builder.append("（").append(row.sourceNote()).append(')');
            }
            builder.append('\n');
        }
    }

    private static boolean isHomeworkStatusQuery(String text) {
        if (text == null) {
            return false;
        }
        String normalized = text.replace(" ", "");
        return normalized.contains("还没交什么")
                || normalized.contains("未交什么")
                || normalized.contains("缺什么作业")
                || normalized.contains("还有什么要交");
    }
}
