package com.fairfps.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class GameWebSocketHandler extends TextWebSocketHandler {
    private static final int MAX_MESSAGE = 8192;
    private static final int MAX_AMMO = 24;
    private static final int MAX_HP = 100;
    private static final double MOVE_SPEED = 250.0;
    private static final long FIRE_INTERVAL_MS = 500;
    private static final long RELOAD_INTERVAL_MS = 1500;
    private static final double RANGE = 700.0;
    private final ObjectMapper json = new ObjectMapper();
    private final GameEventLog events;
    private final String mode;
    private final Object lock = new Object();
    private final Map<Integer, Player> players = new HashMap<>();
    private final Map<String, Integer> sessionPlayers = new HashMap<>();

    public GameWebSocketHandler(GameEventLog events, @Value("${fairfps.mode:secure}") String mode) {
        this.events = events;
        this.mode = mode;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        synchronized (lock) {
            int id = players.containsKey(1) ? 2 : 1;
            if (players.containsKey(1) && players.containsKey(2)) {
                session.sendMessage(new TextMessage("{\"type\":\"full\"}"));
                session.close(CloseStatus.POLICY_VIOLATION);
                return;
            }
            Player player = new Player(id, id == 1 ? 116 : 350, id == 1 ? 122 : 141);
            players.put(id, player);
            sessionPlayers.put(session.getId(), id);
            sessions.put(session.getId(), session);
            session.getAttributes().put("playerId", id);
            send(session, Map.of("type", "welcome", "id", id, "mode", mode));
            events.record(mode, Integer.toString(id), "join", "player connected");
            broadcastState();
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        int id = (int) session.getAttributes().get("playerId");
        synchronized (lock) {
            if (message.getPayloadLength() > MAX_MESSAGE) {
                violation(session, id, "oversized_message", "message exceeds 8192 bytes");
                return;
            }
            JsonNode request;
            try {
                request = json.readTree(message.getPayload());
            } catch (Exception e) {
                violation(session, id, "malformed_request", "invalid JSON");
                return;
            }
            if (!request.isObject()) {
                violation(session, id, "malformed_request", "expected JSON object");
                return;
            }
            Player player = players.get(id);
            if (player == null) return;
            if (mode.equals("secure") && !rememberRequest(session, player, request)) return;

            String type = request.path("type").asText("");
            switch (type) {
                case "move" -> move(session, player, request);
                case "shoot" -> shoot(session, player, request);
                case "reload" -> reload(session, player);
                case "state" -> {
                    if (mode.equals("vulnerable")) acceptClientState(player, request);
                    else violation(session, id, "client_state_rejected", "clients cannot set server state");
                }
                default -> violation(session, id, "unknown_request", type);
            }
            broadcastState();
        }
    }

    private boolean rememberRequest(WebSocketSession session, Player player, JsonNode request) throws IOException {
        String requestId = request.path("request_id").asText("");
        if (requestId.isBlank() || requestId.length() > 80 || !player.requestIds.add(requestId)) {
            violation(session, player.id, "duplicate_or_missing_request", "request_id must be unique");
            return false;
        }
        if (player.requestIds.size() > 256) player.requestIds.remove(player.requestIds.iterator().next());
        return true;
    }

    private void move(WebSocketSession session, Player player, JsonNode request) throws IOException {
        if (mode.equals("secure") && !player.alive) {
            violation(session, player.id, "dead_player_move", "defeated players cannot move");
            return;
        }
        if (mode.equals("vulnerable")) {
            double x = number(request, "x"), y = number(request, "y");
            if (!Double.isFinite(x) || !Double.isFinite(y)) {
                violation(session, player.id, "invalid_movement", "non-finite coordinates");
                return;
            }
            player.x = x;
            player.y = y;
            return;
        }
        double dx = number(request, "dx"), dy = number(request, "dy");
        if (!Double.isFinite(dx) || !Double.isFinite(dy) || Math.abs(dx) > 1 || Math.abs(dy) > 1) {
            violation(session, player.id, "invalid_movement", "direction must be between -1 and 1");
            return;
        }
        long now = System.nanoTime();
        double seconds = Math.min((now - player.lastMoveNanos) / 1_000_000_000.0, 0.1);
        double length = Math.hypot(dx, dy);
        if (length > 1) { dx /= length; dy /= length; }
        player.x += dx * MOVE_SPEED * seconds;
        player.y += dy * MOVE_SPEED * seconds;
        player.lastMoveNanos = now;
    }
    private void shoot(WebSocketSession session, Player player, JsonNode request) throws IOException {
        long now = System.currentTimeMillis();
        if (!player.alive) return;
        double angle = number(request, "angle");
        if (mode.equals("secure") && !Double.isFinite(angle)) {
            violation(session, player.id, "invalid_angle", "angle must be finite");
            return;
        }
        if (mode.equals("secure")) {
            if (player.ammo <= 0) {
                violation(session, player.id, "empty_ammo", "shot rejected");
                return;
            }
            if (now - player.lastShotMillis < FIRE_INTERVAL_MS) {
                violation(session, player.id, "fire_rate", "minimum interval is 500ms");
                return;
            }
        }
        player.ammo = Math.max(0, player.ammo - 1);
        player.lastShotMillis = now;
        Player target = players.values().stream().filter(p -> p.id != player.id && p.alive).findFirst().orElse(null);
        if (target == null) return;
        double aimX = Math.cos(angle), aimY = Math.sin(angle);
        double toX = target.x - player.x, toY = target.y - player.y;
        double distance = Math.hypot(toX, toY);
        double alignment = distance == 0 ? 1 : (aimX * toX + aimY * toY) / distance;
        boolean hit = distance <= RANGE && alignment >= 0.97;
        if (mode.equals("vulnerable")) {
            int damage = request.path("damage").asInt(10);
            target.hp = Math.max(0, target.hp - Math.max(0, damage));
        } else if (hit) {
            target.hp = Math.max(0, target.hp - 10);
        }
        target.alive = target.hp > 0;
        events.record(mode, Integer.toString(player.id), hit ? "shot_hit" : "shot", "target=" + target.id + ", ammo=" + player.ammo);
        if (!target.alive) events.record(mode, Integer.toString(target.id), "defeat", "health reached zero");
    }
    private void reload(WebSocketSession session, Player player) throws IOException {
        long now = System.currentTimeMillis();
        if (mode.equals("secure") && !player.alive) {
            violation(session, player.id, "dead_player_reload", "defeated players cannot reload");
            return;
        }
        if (mode.equals("secure") && (player.ammo == MAX_AMMO || now - player.lastReloadMillis < RELOAD_INTERVAL_MS)) {
            violation(session, player.id, "invalid_reload", "reload rejected");
            return;
        }
        player.ammo = MAX_AMMO;
        player.lastReloadMillis = now;
        events.record(mode, Integer.toString(player.id), "reload", "ammo restored");
    }
    private void acceptClientState(Player player, JsonNode request) {
        player.x = number(request, "x");
        player.y = number(request, "y");
        player.hp = request.path("hp").asInt(player.hp);
        player.ammo = request.path("ammo").asInt(player.ammo);
        player.alive = player.hp > 0;
        events.record(mode, Integer.toString(player.id), "client_state_accepted", "client supplied state");
    }

    private void violation(WebSocketSession session, int id, String type, String detail) throws IOException {
        events.record(mode, Integer.toString(id), type, detail);
        send(session, Map.of("type", "rejected", "reason", type));
        if (mode.equals("secure")) {
            Player p = players.get(id);
            if (p != null && ++p.violations >= 3) {
                events.record(mode, Integer.toString(id), "disconnect", "three invalid requests");
                session.close(CloseStatus.POLICY_VIOLATION);
            }
        }
    }

    private void broadcastState() throws IOException {
        for (Map.Entry<String, Integer> entry : sessionPlayers.entrySet()) {
            WebSocketSession session = findSession(entry.getKey());
            if (session != null && session.isOpen()) send(session, snapshot(entry.getValue()));
        }
    }

    private WebSocketSession findSession(String id) {
        return sessions.get(id);
    }

    private final Map<String, WebSocketSession> sessions = new HashMap<>();

    private Map<String, Object> snapshot(int viewerId) {
        List<Map<String, Object>> visible = new ArrayList<>();
        for (Player p : players.values()) {
            Map<String, Object> state = new HashMap<>();
            state.put("id", p.id); state.put("x", p.x); state.put("y", p.y);
            state.put("hp", p.hp); state.put("alive", p.alive);
            if (mode.equals("vulnerable") || p.id == viewerId) state.put("ammo", p.ammo);
            visible.add(state);
        }
        return Map.of("type", "state", "players", visible);
    }

    private void send(WebSocketSession session, Object payload) throws IOException {
        session.sendMessage(new TextMessage(json.writeValueAsString(payload)));
    }

    private static double number(JsonNode node, String key) {
        JsonNode value = node.get(key);
        return value != null && value.isNumber() ? value.doubleValue() : Double.NaN;
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        synchronized (lock) {
            Integer id = sessionPlayers.remove(session.getId());
            sessions.remove(session.getId());
            if (id != null) {
                players.remove(id);
                events.record(mode, Integer.toString(id), "leave", "connection closed");
                try { broadcastState(); } catch (IOException ignored) { }
            }
        }
    }

    private static final class Player {
        final int id;
        double x, y;
        int hp = MAX_HP, ammo = MAX_AMMO, violations;
        boolean alive = true;
        long lastMoveNanos = System.nanoTime(), lastShotMillis, lastReloadMillis;
        final Set<String> requestIds = new LinkedHashSet<>();
        Player(int id, double x, double y) { this.id = id; this.x = x; this.y = y; }
    }
}



