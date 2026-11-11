package com.komeowmeow.qqstudymonitor.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

@Repository
public class AssignmentRepository {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

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
        LocalDateTime windowEnd = now.plusMinutes(leadMinutes);
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
                .filter(row -> shouldRemind(row, now, windowEnd))
                .toList();
    }

    public void markReminded(long assignmentId) {
        jdbc.update("UPDATE assignment SET reminded = 1 WHERE id = ?", assignmentId);
    }

    private static boolean shouldRemind(AssignmentRow row, LocalDateTime now, LocalDateTime windowEnd) {
        LocalDateTime due = parseDue(row.dueAt());
        if (due == null) {
            return false;
        }
        return !due.isAfter(windowEnd) && !due.isBefore(now.minusDays(1));
    }

    public static LocalDateTime parseDue(String dueAt) {
        if (dueAt == null || dueAt.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(dueAt.trim(), FORMATTER);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    public record AssignmentRow(
            long id, long groupId, String title, String description, String dueAt, boolean reminded) {
    }
}
