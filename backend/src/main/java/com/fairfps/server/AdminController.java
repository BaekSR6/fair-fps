package com.fairfps.server;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

@RestController
public class AdminController {
    private final GameEventLog events;
    private final byte[] adminKey;

    public AdminController(GameEventLog events, @Value("${fairfps.admin-key}") String adminKey) {
        this.events = events;
        this.adminKey = adminKey.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/api/admin/events")
    public ResponseEntity<?> latestEvents(@RequestHeader(value = "X-Admin-Key", required = false) String key,
                                          @RequestParam(defaultValue = "100") int limit) {
        if (key == null || !MessageDigest.isEqual(adminKey, key.getBytes(StandardCharsets.UTF_8)))
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        List<Map<String, Object>> result = events.latest(Math.max(1, Math.min(limit, 500)));
        return ResponseEntity.ok(result);
    }
}
