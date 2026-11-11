package com.komeowmeow.qqstudymonitor.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class MemberStatusRepository {
    private final JdbcTemplate jdbc;

    public MemberStatusRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void ensurePendingForAllMembers(long assignmentId, long groupId, List<GroupMemberRepository.MemberRow> members) {
        for (GroupMemberRepository.MemberRow member : members) {
            jdbc.update("""
                    INSERT INTO assignment_member_status
                        (assignment_id, group_id, user_id, user_name, status, updated_at)
                    VALUES (?, ?, ?, ?, 'pending', CURRENT_TIMESTAMP)
                    ON CONFLICT(assignment_id, user_id) DO NOTHING
                    """, assignmentId, groupId, member.userId(), member.userName());
        }
    }

    public void markSubmitted(long assignmentId, long groupId, long userId, String userName) {
        jdbc.update("""
                INSERT INTO assignment_member_status
                    (assignment_id, group_id, user_id, user_name, status, updated_at)
                VALUES (?, ?, ?, ?, 'submitted', CURRENT_TIMESTAMP)
                ON CONFLICT(assignment_id, user_id) DO UPDATE SET
                    status = 'submitted',
                    user_name = excluded.user_name,
                    updated_at = CURRENT_TIMESTAMP
                """, assignmentId, groupId, userId, userName == null ? "" : userName);
    }

    public List<PendingMemberRow> listPending(long assignmentId) {
        return jdbc.query("""
                SELECT user_id, user_name FROM assignment_member_status
                WHERE assignment_id = ? AND status = 'pending'
                ORDER BY user_name, user_id
                """, (rs, rowNum) -> new PendingMemberRow(rs.getLong("user_id"), rs.getString("user_name")), assignmentId);
    }

    public List<UserAssignmentStatusRow> listPendingForUser(long userId) {
        return jdbc.query("""
                SELECT a.id, a.group_id, a.title, a.due_at, s.status
                FROM assignment a
                JOIN assignment_member_status s ON s.assignment_id = a.id AND s.user_id = ?
                WHERE a.status = 'published' AND s.status = 'pending'
                ORDER BY a.group_id, a.id DESC
                """, (rs, rowNum) -> new UserAssignmentStatusRow(
                rs.getLong("id"),
                rs.getLong("group_id"),
                rs.getString("title"),
                rs.getString("due_at"),
                rs.getString("status")), userId);
    }

    public record PendingMemberRow(long userId, String userName) {
    }

    public record UserAssignmentStatusRow(long assignmentId, long groupId, String title, String dueAt, String status) {
    }
}
