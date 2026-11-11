package com.komeowmeow.qqstudymonitor.application;

import com.komeowmeow.qqstudymonitor.infrastructure.persistence.AssignmentDraftRepository;
import com.komeowmeow.qqstudymonitor.infrastructure.persistence.AssignmentRepository;
import com.komeowmeow.qqstudymonitor.infrastructure.persistence.CourseMaterialRepository;
import com.komeowmeow.qqstudymonitor.infrastructure.persistence.GroupMemberRepository;
import com.komeowmeow.qqstudymonitor.infrastructure.persistence.MemberStatusRepository;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

@Service
public class GroupClassAffairsService {
    private final RoleGuard roleGuard;
    private final StudyAiService studyAiService;
    private final AssignmentDraftRepository draftRepository;
    private final AssignmentRepository assignmentRepository;
    private final MemberStatusRepository memberStatusRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final CourseMaterialRepository courseMaterialRepository;

    public GroupClassAffairsService(
            RoleGuard roleGuard,
            StudyAiService studyAiService,
            AssignmentDraftRepository draftRepository,
            AssignmentRepository assignmentRepository,
            MemberStatusRepository memberStatusRepository,
            GroupMemberRepository groupMemberRepository,
            CourseMaterialRepository courseMaterialRepository) {
        this.roleGuard = roleGuard;
        this.studyAiService = studyAiService;
        this.draftRepository = draftRepository;
        this.assignmentRepository = assignmentRepository;
        this.memberStatusRepository = memberStatusRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.courseMaterialRepository = courseMaterialRepository;
    }

    public void registerMember(long groupId, long userId, String userName) {
        groupMemberRepository.upsert(groupId, userId, userName);
    }

    public String handle(long groupId, long senderId, IncomingMessage message) {
        registerMember(groupId, senderId, message.senderName());

        if (roleGuard.canPublishOrClose(senderId) && isConfirmCommand(message.text())) {
            return confirmLatestDraft(groupId, senderId);
        }

        if (roleGuard.isTeacher(senderId) && !message.mentionsBot() && !message.isBlank()) {
            return createDraftFromNotice(groupId, senderId, message);
        }

        if (!message.mentionsBot()) {
            return null;
        }

        String text = message.text();
        if (containsSubmitDeclaration(text)) {
            return markSelfSubmitted(groupId, senderId, message.senderName());
        }
        if (containsPendingQuery(text)) {
            return formatPendingMembers(groupId);
        }
        return """
                学习委员班务指令：
                · 老师发通知/截图 → 整理确认卡，回复「确认发布」后入库
                · @ 我 +「我交了」→ 标记本人已交
                · @ 我 +「还没交」→ 查看未交名单
                """;
    }

    private String createDraftFromNotice(long groupId, long teacherId, IncomingMessage message) {
        StudyAiService.AssignmentExtraction extraction = studyAiService.extractAssignment(message);
        if (!extraction.missingFields().isEmpty()) {
            String missing = String.join("、", extraction.missingFields());
            return "以下信息还不清楚：" + missing + "。请补充后我再生成确认卡。";
        }
        draftRepository.insert(groupId, teacherId, extraction.title(), extraction.description(), extraction.dueAt());
        courseMaterialRepository.add(
                groupId,
                extraction.title(),
                extraction.description(),
                "来自老师群通知：" + message.effectiveText());
        return formatConfirmCard(extraction);
    }

    private String confirmLatestDraft(long groupId, long senderId) {
        if (!roleGuard.canPublishOrClose(senderId)) {
            return "你没有权限发布或关闭班务。";
        }
        var draft = draftRepository.findLatest(groupId).orElse(null);
        if (draft == null) {
            return "当前没有待确认的作业草稿。";
        }
        long assignmentId = assignmentRepository.publish(
                groupId, draft.teacherId(), draft.title(), draft.description(), draft.dueAt());
        var members = groupMemberRepository.listMembers(groupId);
        memberStatusRepository.ensurePendingForAllMembers(assignmentId, groupId, members);
        draftRepository.delete(draft.id());
        return "已发布作业：《" + draft.title() + "》"
                + (draft.dueAt() == null || draft.dueAt().isBlank() ? "" : "，截止 " + draft.dueAt());
    }

    private String markSelfSubmitted(long groupId, long userId, String userName) {
        var assignments = assignmentRepository.findActiveByGroup(groupId);
        if (assignments.isEmpty()) {
            return "当前没有进行中的作业。";
        }
        for (var assignment : assignments) {
            memberStatusRepository.markSubmitted(assignment.id(), groupId, userId, userName);
        }
        return "已记录你提交了当前进行中的作业（共 " + assignments.size() + " 项）。";
    }

    private String formatPendingMembers(long groupId) {
        var assignments = assignmentRepository.findActiveByGroup(groupId);
        if (assignments.isEmpty()) {
            return "当前没有进行中的作业。";
        }
        StringBuilder builder = new StringBuilder();
        for (var assignment : assignments) {
            var pending = memberStatusRepository.listPending(assignment.id());
            builder.append("《").append(assignment.title()).append("》未交：");
            if (pending.isEmpty()) {
                builder.append("全部已交\n");
            } else {
                String names = pending.stream()
                        .map(row -> displayName(row.userName(), row.userId()))
                        .collect(Collectors.joining("、"));
                builder.append(names).append('\n');
            }
        }
        return builder.toString().trim();
    }

    private static String formatConfirmCard(StudyAiService.AssignmentExtraction extraction) {
        return """
                【作业确认卡】
                标题：%s
                事项：%s
                截止：%s
                老师或学习委员回复「确认发布」后才会记入班务。
                """.formatted(
                extraction.title(),
                extraction.description().isBlank() ? "（见通知）" : extraction.description(),
                extraction.dueAt().isBlank() ? "（待补充）" : extraction.dueAt());
    }

    private static boolean isConfirmCommand(String text) {
        if (text == null) {
            return false;
        }
        String normalized = text.replace(" ", "");
        return normalized.contains("确认发布") || normalized.equals("确认");
    }

    private static boolean containsSubmitDeclaration(String text) {
        return text != null && text.replace(" ", "").contains("我交了");
    }

    private static boolean containsPendingQuery(String text) {
        if (text == null) {
            return false;
        }
        String normalized = text.replace(" ", "");
        return normalized.contains("还没交") || normalized.contains("未交");
    }

    private static String displayName(String userName, long userId) {
        return userName == null || userName.isBlank() ? String.valueOf(userId) : userName;
    }
}
