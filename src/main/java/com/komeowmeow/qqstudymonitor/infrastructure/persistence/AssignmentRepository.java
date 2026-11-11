package com.komeowmeow.qqstudymonitor.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.komeowmeow.qqstudymonitor.application.DueAtNormalizer;
import com.komeowmeow.qqstudymonitor.application.ReminderWindow;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class AssignmentRepository {

    private final JdbcTemplate jdbc;

    public AssignmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long publish(long groupId, long createdBy, String title, String description, String dueAt) {
        jdbc.update("""
                INSERT INTO assignment (group_id, title, description, due_at, created_by, status, reminded)
                VALUES (?, ?, ?, ?, ?, 'published', 0)
                """, groupId, title, description, dueAt, createdBy);
        Long id = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        return id == null ? 0 : id;
    }

    public List<AssignmentRow> findActiveByGroup(long groupId) {
        return jdbc.query("""
                SELECT id, group_id, title, description, due_at, reminded
                FROM assignment
                WHERE group_id = ? AND status = 'published'
                ORDER BY id DESC
                """, (rs, rowNum) -> new AssignmentRow(
                rs.getLong("id"),
                rs.getLong("group_id"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("due_at"),
                rs.getInt("reminded") != 0), groupId);
    }

    public List<AssignmentRow> findDueForReminder(LocalDateTime now, int leadMinutes) {
        return jdbc.query("""
                SELECT id, group_id, title, description, due_at, reminded
                FROM assignment
                WHERE status = 'published' AND reminded = 0 AND due_at IS NOT NULL AND due_at != ''
                """, (rs, rowNum) -> new AssignmentRow(
                rs.getLong("id"),
                rs.getLong("group_id"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("due_at"),
                rs.getInt("reminded") != 0)).stream()
                .filter(row -> ReminderWindow.shouldRemind(parseDue(row.dueAt()), now, leadMinutes))
                .toList();
    }

    public void markReminded(long assignmentId) {
        jdbc.update("UPDATE assignment SET reminded = 1 WHERE id = ?", assignmentId);
    }

    public Optional<AssignmentRow> findLatestPublished(long groupId) {
        List<AssignmentRow> rows = jdbc.query("""
                SELECT id, group_id, title, description, due_at, reminded
                FROM assignment
                WHERE group_id = ? AND status = 'published'
                ORDER BY id DESC
                LIMIT 1
                """, (rs, rowNum) -> new AssignmentRow(
                rs.getLong("id"),
                rs.getLong("group_id"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("due_at"),
                rs.getInt("reminded") != 0), groupId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public boolean closePublished(long assignmentId) {
        int updated = jdbc.update(
                "UPDATE assignment SET status = 'closed' WHERE id = ? AND status = 'published'",
                assignmentId);
        return updated > 0;
    }

    public boolean closeLatestPublished(long groupId) {
        return findLatestPublished(groupId)
                .map(row -> closePublished(row.id()))
                .orElse(false);
    }

    public static LocalDateTime parseDue(String dueAt) {
        return DueAtNormalizer.parse(dueAt);
    }

    public record AssignmentRow(
            long id, long groupId, String title, String description, String dueAt, boolean reminded) {
    }
}
