package com.komeowmeow.qqstudymonitor.infrastructure.persistence;

import com.komeowmeow.qqstudymonitor.config.BotLimitsProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class PrivateMessageRepository {
    private final JdbcTemplate jdbc;
    private final BotLimitsProperties limits;

    public PrivateMessageRepository(JdbcTemplate jdbc, BotLimitsProperties limits) {
        this.jdbc = jdbc;
        this.limits = limits;
    }

    public void append(long userId, String role, String content) {
        jdbc.update("""
                INSERT INTO private_message (user_id, role, content)
                VALUES (?, ?, ?)
                """, userId, role, content);
    }

    public List<HistoryRow> recentForUser(long userId) {
        int max = Math.max(1, Math.min(limits.getPrivateContextMaxMessages(), 50));
        List<HistoryRow> rows = jdbc.query("""
                SELECT role, content FROM private_message
                WHERE user_id = ?
                ORDER BY id DESC
                LIMIT ?
                """, (rs, rowNum) -> new HistoryRow(rs.getString("role"), rs.getString("content")), userId, max);
        return rows.reversed();
    }

    public record HistoryRow(String role, String content) {
    }
}
