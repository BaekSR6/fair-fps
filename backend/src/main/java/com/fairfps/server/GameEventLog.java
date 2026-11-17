package com.fairfps.server;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class GameEventLog {
    private final JdbcTemplate jdbc;

    public GameEventLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String mode, String player, String type, String detail) {
        jdbc.update("INSERT INTO security_events(mode, player_id, event_type, detail) VALUES (?, ?, ?, ?)",
                mode, player, type, detail.length() > 512 ? detail.substring(0, 512) : detail);
    }

    public List<Map<String, Object>> latest(int limit) {
        return jdbc.queryForList("SELECT id, occurred_at, mode, player_id, event_type, detail " +
                "FROM security_events ORDER BY id DESC LIMIT ?", limit);
    }
}
