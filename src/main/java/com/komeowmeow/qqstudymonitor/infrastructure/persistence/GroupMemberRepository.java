package com.komeowmeow.qqstudymonitor.infrastructure.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class GroupMemberRepository {
    private final JdbcTemplate jdbc;

    public GroupMemberRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void upsert(long groupId, long userId, String userName) {
        jdbc.update("""
                INSERT INTO group_member (group_id, user_id, user_name, updated_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT(group_id, user_id) DO UPDATE SET
                    user_name = excluded.user_name,
                    updated_at = CURRENT_TIMESTAMP
                """, groupId, userId, userName == null ? "" : userName);
    }

    public List<MemberRow> listMembers(long groupId) {
        return jdbc.query("""
                SELECT user_id, user_name FROM group_member WHERE group_id = ?
                ORDER BY user_name, user_id
                """, (rs, rowNum) -> new MemberRow(rs.getLong("user_id"), rs.getString("user_name")), groupId);
    }

    public record MemberRow(long userId, String userName) {
    }
}
