package com.komeowmeow.qqstudymonitor.application;

import com.komeowmeow.qqstudymonitor.infrastructure.persistence.AssignmentDraftRepository;
import com.komeowmeow.qqstudymonitor.infrastructure.persistence.AssignmentRepository;
import com.komeowmeow.qqstudymonitor.infrastructure.persistence.AssignmentRepository.AssignmentRow;
import com.komeowmeow.qqstudymonitor.infrastructure.persistence.CourseMaterialRepository;
import com.komeowmeow.qqstudymonitor.infrastructure.persistence.GroupMemberRepository;
import com.komeowmeow.qqstudymonitor.infrastructure.persistence.MemberStatusRepository;
import org.springframework.stereotype.Service;

import java.util.List;
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
    private final GroupRosterSyncService groupRosterSyncService;

    public GroupClassAffairsService(
            RoleGuard roleGuard,
            StudyAiService studyAiService,
            AssignmentDraftRepository draftRepository,
            AssignmentRepository assignmentRepository,
            MemberStatusRepository memberStatusRepository,
            GroupMemberRepository groupMemberRepository,
            CourseMaterialRepository courseMaterialRepository,
            GroupRosterSyncService groupRosterSyncService) {
        this.roleGuard = roleGuard;
        this.studyAiService = studyAiService;
        this.draftRepository = draftRepository;
        this.assignmentRepository = assignmentRepository;
        this.memberStatusRepository = memberStatusRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.courseMaterialRepository = courseMaterialRepository;
        this.groupRosterSyncService = groupRosterSyncService;
    }

    public void registerMember(long groupId, long userId, String userName) {
        groupMemberRepository.upsert(groupId, userId, userName);
    }

    public String handle(long groupId, long senderId, IncomingMessage message) {
        registerMember(groupId, senderId, message.senderName());

        String text = message.text();
        if (roleGuard.canPublishOrClose(senderId) && GroupCommandText.isConfirm(text)) {
            return confirmLatestDraft(groupId, senderId);
        }

        if (roleGuard.canPublishOrClose(senderId) && GroupCommandText.isClose(text)) {
            return closeAssignment(groupId, senderId, text);
        }

        if (message.mentionsBot() && GroupCommandText.isSubmit(text)) {
            return markSelfSubmitted(groupId, senderId, message.senderName(), text);
        }
        if (message.mentionsBot() && GroupCommandText.isPendingQuery(text)) {
            return formatPendingMembers(groupId);
        }

        if (roleGuard.canPublishOrClose(senderId) && !message.isBlank() && !GroupCommandText.isClassCommand(text)) {
            return createDraftFromNotice(groupId, senderId, message);
        }

        if (!message.mentionsBot()) {
            return null;
        }
        return """
                学习委员班务指令：
                · 老师/委员发通知或截图（可 @ 我）→ 整理确认卡，回复「确认发布」后入库
                · 老师/委员「关闭作业」或「关闭作业 标题」→ 结束对应进行中的班务
                · @ 我 +「我交了」→ 只有一项时标记本人；多项时加上作业标题
                · @ 我 +「还没交」→ 查看未交名单
                """;
    }

    private String createDraftFromNotice(long groupId, long publisherId, IncomingMessage message) {
        StudyAiService.AssignmentExtraction extraction = studyAiService.extractAssignment(message);
        if (!extraction.missingFields().isEmpty()) {
            String missing = String.join("、", extraction.missingFields());
            return "以下信息还不清楚：" + missing + "。请补充后我再生成确认卡。";
        }
        String dueAt = DueAtNormalizer.toCanonicalStorage(extraction.dueAt());
        draftRepository.insert(groupId, publisherId, extraction.title(), extraction.description(), dueAt);
        courseMaterialRepository.add(
                groupId,
                extraction.title(),
                extraction.description(),
                "来自群班务通知：" + message.effectiveText());
        return formatConfirmCard(extraction.withDueAt(dueAt));
    }

    private String confirmLatestDraft(long groupId, long senderId) {
        if (!roleGuard.canPublishOrClose(senderId)) {
            return "你没有权限发布或关闭班务。";
        }
        var draft = draftRepository.findLatest(groupId).orElse(null);
        if (draft == null) {
            return "当前没有待确认的作业草稿。";
        }
        String dueAt = DueAtNormalizer.toCanonicalStorage(draft.dueAt());
        groupRosterSyncService.syncGroupMembers(groupId);
        long assignmentId = assignmentRepository.publish(
                groupId, draft.teacherId(), draft.title(), draft.description(), dueAt);
        var members = groupMemberRepository.listMembers(groupId);
        memberStatusRepository.ensurePendingForAllMembers(assignmentId, groupId, members);
        draftRepository.delete(draft.id());
        return "已发布作业：《" + draft.title() + "》"
                + (dueAt.isBlank() ? "" : "，截止 " + dueAt);
    }

    private String closeAssignment(long groupId, long senderId, String text) {
        if (!roleGuard.canPublishOrClose(senderId)) {
            return "你没有权限发布或关闭班务。";
        }
        var active = assignmentRepository.findActiveByGroup(groupId);
        if (active.isEmpty()) {
            return "当前没有进行中的作业可关闭。";
        }
        String hint = GroupCommandText.closeHint(text);
        AssignmentRow target = pickAssignment(active, hint);
        if (target == null) {
            return ambiguousAssignmentMessage("关闭", hint, active);
        }
        if (!assignmentRepository.closePublished(target.id())) {
            return "关闭失败，请稍后再试。";
        }
        return "已关闭班务：《" + target.title() + "》，不再跟催与统计为进行中。";
    }

    private String markSelfSubmitted(long groupId, long userId, String userName, String text) {
        var assignments = assignmentRepository.findActiveByGroup(groupId);
        if (assignments.isEmpty()) {
            return "当前没有进行中的作业。";
        }
        String hint = GroupCommandText.submitHint(text);
        AssignmentRow target = pickAssignment(assignments, hint);
        if (target == null) {
            return ambiguousAssignmentMessage("提交", hint, assignments);
        }
        memberStatusRepository.markSubmitted(target.id(), groupId, userId, userName);
        return "已记录你提交了《" + target.title() + "》。";
    }

    static AssignmentRow pickAssignment(List<AssignmentRow> active, String hint) {
        if (active.isEmpty()) {
            return null;
        }
        if (hint == null || hint.isBlank()) {
            return active.size() == 1 ? active.getFirst() : null;
        }
        String needle = hint.toLowerCase();
        List<AssignmentRow> matched = active.stream()
                .filter(row -> row.title() != null && row.title().toLowerCase().contains(needle))
                .toList();
        return matched.size() == 1 ? matched.getFirst() : null;
    }

    private static String ambiguousAssignmentMessage(String action, String hint, List<AssignmentRow> active) {
        String listed = formatTitles(active);
        if (hint == null || hint.isBlank()) {
            return "有多项进行中的作业，请写明要" + action + "的标题：\n" + listed;
        }
        return "没有唯一对应「" + hint + "」的进行中作业。当前有：\n" + listed;
    }

    private static String formatTitles(List<AssignmentRow> active) {
        return active.stream()
                .map(row -> "· " + row.title())
                .collect(Collectors.joining("\n"));
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

    private static String displayName(String userName, long userId) {
        return userName == null || userName.isBlank() ? String.valueOf(userId) : userName;
    }
}
