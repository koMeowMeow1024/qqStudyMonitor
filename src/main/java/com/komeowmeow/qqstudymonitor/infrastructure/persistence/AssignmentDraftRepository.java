package com.komeowmeow.qqstudymonitor.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class AssignmentDraftRepository {
    private final JdbcTemplate jdbc;

    public AssignmentDraftRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(long groupId, long teacherId, String title, String description, String dueAt) {
        jdbc.update("""
                INSERT INTO assignment_draft (group_id, teacher_id, title, description, due_at)
                VALUES (?, ?, ?, ?, ?)
                """, groupId, teacherId, title, description, dueAt);
        Long id = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        return id == null ? 0 : id;
    }

    public Optional<DraftRow> findLatest(long groupId) {
        var rows = jdbc.query("""
                SELECT id, group_id, teacher_id, title, description, due_at
                FROM assignment_draft
                WHERE group_id = ?
                ORDER BY id DESC
                LIMIT 1
                """, (rs, rowNum) -> new DraftRow(
                rs.getLong("id"),
                rs.getLong("group_id"),
                rs.getLong("teacher_id"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("due_at")), groupId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public void delete(long draftId) {
        jdbc.update("DELETE FROM assignment_draft WHERE id = ?", draftId);
    }

    public record DraftRow(long id, long groupId, long teacherId, String title, String description, String dueAt) {
    }
}
