package com.komeowmeow.qqstudymonitor.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class CourseMaterialRepository {
    private final JdbcTemplate jdbc;

    public CourseMaterialRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void add(long groupId, String title, String content, String sourceNote) {
        jdbc.update("""
                INSERT INTO course_material (group_id, title, content, source_note)
                VALUES (?, ?, ?, ?)
                """, groupId, title, content, sourceNote == null ? "" : sourceNote);
    }

    public List<MaterialRow> listRecent(long groupId, int limit) {
        return jdbc.query("""
                SELECT title, content, source_note FROM course_material
                WHERE group_id = ?
                ORDER BY id DESC
                LIMIT ?
                """, (rs, rowNum) -> new MaterialRow(
                rs.getString("title"),
                rs.getString("content"),
                rs.getString("source_note")), groupId, limit);
    }

    public record MaterialRow(String title, String content, String sourceNote) {
    }
}
