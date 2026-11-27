import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.file.Files;
import java.nio.file.Path;

public class FairPlay {
    private static final int PORT = 7777;
    private static final Map<Integer, Client> clients =
        new ConcurrentHashMap<>();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final ScheduledExecutorService coinScheduler =
        Executors.newSingleThreadScheduledExecutor();

    private static final AtomicInteger nextCoinId =
        new AtomicInteger(1);

    // 현재 존재하는 코인
    private static final Map<Integer, String> activeCoins =
        new ConcurrentHashMap<>();

    // 생성 지점별 코인 관리
    private static final Map<String, Integer> occupiedSpawns =
        new ConcurrentHashMap<>();

    private static final Map<Integer, double[]> playerPositions =
        new ConcurrentHashMap<>();

    private static final Map<Integer, int[]> playerCurrencies =
        new ConcurrentHashMap<>();

    private static final double PICKUP_DISTANCE = 73.0;

    private static final int MATCH_DURATION = 180;

    private static volatile boolean matchRunning = false;
    private static volatile int remainingSeconds = MATCH_DURATION;

    private static final int MAGAZINE_SIZE = 12;
    private static final long FIRE_INTERVAL_NS = 200_000_000L;
    private static final long RELOAD_DURATION_MS = 1500L;

    private static final double MUZZLE_OFFSET_X = 32.98;
    private static final double MUZZLE_OFFSET_Y = 9.0;

    private static final Map<Integer, WeaponState> weapons =
        new ConcurrentHashMap<>();

    // 코인 생성·획득·스포너 상태를 보호할 잠금
    private static final Object coinLock = new Object();

    private static final List<SpawnerConfig> spawners =
        new ArrayList<>();

    private static final Random random = new Random();

    private static final int MAX_HP = 100;
    private static final int BULLET_DAMAGE = 20;
    private static final double BULLET_SPEED = 700.0;
    private static final double HIT_RADIUS = 22.0;
    private static final double BULLET_LIFETIME = 1.5;
    private static final long RESPAWN_DELAY_MS = 3000;

    private static final Map<Integer, CombatState> combatStates =
        new ConcurrentHashMap<>();

    private static final List<ServerBullet> serverBullets =
        new ArrayList<>();

    private static final ScheduledExecutorService combatScheduler =
        Executors.newSingleThreadScheduledExecutor();

    private static final ScheduledExecutorService respawnScheduler =
        Executors.newSingleThreadScheduledExecutor();

    public static void main(String[] args) throws IOException {
        startMatchTimer();
        loadSpawnConfig();
        for (SpawnerConfig config : spawners) {
            coinScheduler.scheduleAtFixedRate(
                () -> spawnRandomCoin(config),
                0,
                config.intervalMs,
                TimeUnit.MILLISECONDS
            );
        }
        try (ServerSocket server = new ServerSocket(PORT)) {
            System.out.println("FairPlay 서버 시작: " + PORT);
            combatScheduler.scheduleAtFixedRate(
                FairPlay::updateCombat,
                0,
                1000 / 60,
                TimeUnit.MILLISECONDS
            );

            while (true) {
                Socket socket = server.accept();
                int id;

                synchronized (clients) {
                    id = !clients.containsKey(1) ? 1 :
                        !clients.containsKey(2) ? 2 : 0;

                    if (id == 0) {
                        socket.close();
                        continue;
                    }

                    Client client = new Client(id, socket);
                    clients.put(id, client);
                    client.start();
                }
            }
        }
    }

    private static final Object combatLock = new Object();

    private static class CombatState {
        int hp = MAX_HP;
        boolean dead = false;
        double x;
        double y;
        double rotation;
    }

    static void spawnCoin(
        String spawnKey,
        String coinType,
        double x,
        double y
    ){
        if (!matchRunning) {
            return;
        }
        // 해당 위치에 이미 코인이 있으면 생성하지 않음
        synchronized (coinLock) {
            if (occupiedSpawns.containsKey(spawnKey)) {
                return;
            }

            int coinId = nextCoinId.getAndIncrement();

            String message = MAPPER.createObjectNode()
                .put("type", "coin_spawn")
                .put("coin_id", coinId)
                .put("coin_type", coinType)
                .put("amount", 1)
                .put("x", x)
                .put("y", y)
                .toString();

            occupiedSpawns.put(spawnKey, coinId);
            activeCoins.put(coinId, message);

            broadcast(message, 0);

            System.out.println(
                "코인 생성 ID=" + coinId
                    + ", 종류=" + coinType
                    + ", 위치=(" + x + ", " + y + ")"
            );
        }
    }

    static void collectCoin(Client client, int coinId) {
        synchronized (coinLock) {

            if (!matchRunning) {
                client.send(MAPPER.createObjectNode()
                    .put("type", "coin_rejected")
                    .put("coin_id", coinId)
                    .put("reason", "match_not_running")
                    .toString());
                return;
            }

            String coinMessage = activeCoins.get(coinId);

            if (coinMessage == null) {
                client.send(MAPPER.createObjectNode()
                    .put("type", "coin_rejected")
                    .put("coin_id", coinId)
                    .put("reason", "not_found")
                    .toString());
                return;
            }

            try {
                JsonNode coin = MAPPER.readTree(coinMessage);

                double coinX = coin.path("x").asDouble();
                double coinY = coin.path("y").asDouble();

                double[] playerPos = playerPositions.get(client.id);

                if (playerPos == null) {
                    client.send(MAPPER.createObjectNode()
                        .put("type", "coin_rejected")
                        .put("coin_id", coinId)
                        .put("reason", "position_unknown")
                        .toString());
                    return;
                }

                double distance = Math.hypot(
                    playerPos[0] - coinX,
                    playerPos[1] - coinY
                );

                if (distance > PICKUP_DISTANCE) {
                    System.out.printf(
                        "[REJECT] Player%d Coin%d | "
                            + "Player=(%.1f, %.1f) "
                            + "Coin=(%.1f, %.1f) "
                            + "Distance=%.1f "
                            + "Limit=%.1f%n",
                        client.id,
                        coinId,
                        playerPos[0],
                        playerPos[1],
                        coinX,
                        coinY,
                        distance,
                        PICKUP_DISTANCE
                    );

                    client.send(MAPPER.createObjectNode()
                        .put("type", "coin_rejected")
                        .put("coin_id", coinId)
                        .put("reason", "too_far")
                        .toString());
                    return;
                }

                String coinType = coin.path("coin_type").asText();
                int amount = coin.path("amount").asInt();

                if ((!coinType.equals("silver")
                    && !coinType.equals("diamond"))
                    || amount <= 0) {
                    return;
                }

                // 검증 완료: 서버에서 코인 제거
                activeCoins.remove(coinId);

                // 해당 스포너의 생성 지점 비우기
                occupiedSpawns.entrySet().removeIf(
                    entry -> entry.getValue().equals(coinId)
                );

                // 서버가 보유 재화를 계산
                int[] currency = playerCurrencies.computeIfAbsent(
                    client.id, key -> new int[2]
                );

                if (coinType.equals("silver")) {
                    currency[0] += amount;
                } else {
                    currency[1] += amount;
                }

                // 양쪽 클라이언트에 코인 삭제 통보
                String removeMessage = MAPPER.createObjectNode()
                    .put("type", "coin_remove")
                    .put("coin_id", coinId)
                    .toString();

                broadcast(removeMessage, 0);

                // 획득한 플레이어에게만 보유량 통보
                client.send(MAPPER.createObjectNode()
                    .put("type", "currency_update")
                    .put("silver", currency[0])
                    .put("diamond", currency[1])
                    .toString());

                System.out.println(
                    "[COLLECT] Player" + client.id
                        + " " + coinType
                        + " +" + amount
                        + " (ID=" + coinId + ")"
                );

            } catch (IOException e) {
                System.out.println("코인 데이터 처리 실패: " + e.getMessage());
            }
        }
    }

    private static int determineWinner(
        int p1Diamond, int p1Silver,
        int p2Diamond, int p2Silver) {

        // 1순위: 다이아 개수 비교
        if (p1Diamond > p2Diamond) {
            return 1;
        }

        if (p1Diamond < p2Diamond) {
            return 2;
        }

        // 2순위: 은화 개수 비교
        if (p1Silver > p2Silver) {
            return 1;
        }

        if (p1Silver < p2Silver) {
            return 2;
        }

        // 무승부
        return 0;
    }
    static void loadSpawnConfig() throws IOException {
        String content = Files.readString(
            Path.of("spawn_points.json"),
            StandardCharsets.UTF_8
        );

        JsonNode root = MAPPER.readTree(content);

        if (!root.isArray()) {
            throw new IOException("스포너 설정은 JSON 배열이어야 합니다.");
        }

        spawners.clear();

        Set<String> names = new HashSet<>();

        for (JsonNode node : root) {
            String name = node.path("name").asText("");
            String coinType = node.path("coin_type").asText("");
            double interval = node.path("interval").asDouble(0);
            int maxCoins = node.path("max_coins").asInt(0);

            if (name.isBlank() || !names.add(name)) {
                throw new IOException("스포너 이름이 비어 있거나 중복됨: " + name);
            }

            if (!coinType.equals("silver")
                && !coinType.equals("diamond")) {
                throw new IOException("잘못된 코인 종류: " + coinType);
            }

            if (!Double.isFinite(interval) || interval < 0.1
                || maxCoins < 1) {
                throw new IOException("잘못된 생성 설정: " + name);
            }

            JsonNode pointNodes = node.path("points");

            if (!pointNodes.isArray() || pointNodes.isEmpty()) {
                throw new IOException("생성 지점 없음: " + name);
            }

            List<SpawnPoint> points = new ArrayList<>();

            for (int i = 0; i < pointNodes.size(); i++) {
                JsonNode point = pointNodes.get(i);

                if (!point.path("x").isNumber()
                    || !point.path("y").isNumber()) {
                    throw new IOException("잘못된 좌표: " + name);
                }

                double x = point.path("x").asDouble();
                double y = point.path("y").asDouble();

                if (!Double.isFinite(x) || !Double.isFinite(y)) {
                    throw new IOException("유효하지 않은 좌표: " + name);
                }

                points.add(new SpawnPoint(
                    name + "_" + i, x, y
                ));
            }

            spawners.add(new SpawnerConfig(
                name,
                coinType,
                Math.max(100L, Math.round(interval * 1000)),
                Math.min(maxCoins, points.size()),
                points
            ));

            System.out.println(
                "[SPAWNER LOADED] " + name
                    + " type=" + coinType
                    + " points=" + points.size()
                    + " max=" + Math.min(maxCoins, points.size())
            );
        }
    }

    static void spawnRandomCoin(SpawnerConfig config) {
        synchronized (coinLock) {
            if (!matchRunning) {
                return;
            }

            int activeCount = 0;
            List<SpawnPoint> available = new ArrayList<>();

            for (SpawnPoint point : config.points) {
                if (occupiedSpawns.containsKey(point.key)) {
                    activeCount++;
                } else {
                    available.add(point);
                }
            }

            if (activeCount >= config.maxCoins
                || available.isEmpty()) {
                return;
            }

            SpawnPoint selected = available.get(
                random.nextInt(available.size())
            );

            spawnCoin(
                selected.key,
                config.coinType,
                selected.x,
                selected.y
            );
        }
    }

    static void startMatchTimer() {
        matchRunning = true;
        remainingSeconds = MATCH_DURATION;

        coinScheduler.scheduleAtFixedRate(() -> {
            if (!matchRunning) {
                return;
            }

            remainingSeconds--;

            broadcast(MAPPER.createObjectNode()
                .put("type", "match_timer")
                .put("remaining", remainingSeconds)
                .toString(), 0);

            if (remainingSeconds <= 0) {
                matchRunning = false;

                sendMatchResult();

                System.out.println("경기 종료!");
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    static void handleShoot(Client client, JsonNode json) {
        WeaponState weapon = weapons.computeIfAbsent(
            client.id, key -> new WeaponState()
        );

        synchronized (weapon) {
            if (!matchRunning) {
                System.out.println("[SHOOT REJECT] match not running");
                client.send("{\"type\":\"shoot_rejected\"}");
                return;
            }

            if (weapon.reloading) {
                System.out.println("[SHOOT REJECT] reloading");
                client.send("{\"type\":\"shoot_rejected\"}");
                return;
            }

            if (weapon.ammo <= 0) {
                System.out.println("[SHOOT REJECT] no ammo");
                client.send("{\"type\":\"shoot_rejected\"}");
                return;
            }

            long now = System.nanoTime();

            if (weapon.lastShotNs != 0
                && now - weapon.lastShotNs < FIRE_INTERVAL_NS) {
                client.send("{\"type\":\"shoot_rejected\"}");
                return;
            }

            JsonNode rotationNode = json.path("rotation");

            if (!rotationNode.isNumber()) {
                return;
            }

            double rotation = rotationNode.asDouble();

            if (!Double.isFinite(rotation)) {
                return;
            }

            double[] pos = playerPositions.get(client.id);

            if (pos == null) {
                return;
            }

            synchronized (combatLock) {
                CombatState state = combatStates.get(client.id);
                if (state == null || state.dead) {
                    return;
                }
            }

            weapon.ammo--;
            weapon.lastShotNs = now;

            double cos = Math.cos(rotation);
            double sin = Math.sin(rotation);

            double muzzleX = pos[0]
                + MUZZLE_OFFSET_X * cos
                - MUZZLE_OFFSET_Y * sin;

            double muzzleY = pos[1]
                + MUZZLE_OFFSET_X * sin
                + MUZZLE_OFFSET_Y * cos;

            synchronized (combatLock) {
                CombatState state = combatStates.get(client.id);

                if (state != null && !state.dead) {
                    serverBullets.add(new ServerBullet(
                        client.id,
                        muzzleX,
                        muzzleY,
                        rotation
                    ));
                }
            }

            String bulletMessage = MAPPER.createObjectNode()
                .put("type", "bullet_spawn")
                .put("shooter_id", client.id)
                .put("x", muzzleX)
                .put("y", muzzleY)
                .put("rotation", rotation)
                .toString();

            broadcast(bulletMessage, 0);

            client.send(MAPPER.createObjectNode()
                .put("type", "ammo_update")
                .put("ammo", weapon.ammo)
                .put("reloading", false)
                .toString());

            System.out.println(
                "[SHOOT] Player" + client.id
                    + " ammo=" + weapon.ammo
            );
        }

    }

    static void handleReload(Client client) {
        WeaponState weapon = weapons.computeIfAbsent(
            client.id, key -> new WeaponState()
        );

        synchronized (weapon) {
            if (!matchRunning || weapon.reloading
                || weapon.ammo == MAGAZINE_SIZE) {
                return;
            }

            weapon.reloading = true;

            client.send(MAPPER.createObjectNode()
                .put("type", "ammo_update")
                .put("ammo", weapon.ammo)
                .put("reloading", true)
                .toString());
        }

        coinScheduler.schedule(() -> {
            synchronized (weapon) {
                // 이전 연결에서 시작한 재장전이 새 연결에 영향 주지 않도록
                if (weapons.get(client.id) != weapon) {
                    return;
                }

                weapon.reloading = false;

                if (!matchRunning) {
                    return;
                }

                weapon.ammo = MAGAZINE_SIZE;

                client.send(MAPPER.createObjectNode()
                    .put("type", "ammo_update")
                    .put("ammo", weapon.ammo)
                    .put("reloading", false)
                    .toString());
            }
        }, RELOAD_DURATION_MS, TimeUnit.MILLISECONDS);
    }

    private static void broadcastHp(int playerId, CombatState state) {
        Map<String, Object> message = new HashMap<>();
        message.put("type", "hp_update");
        message.put("player_id", playerId);
        message.put("hp", state.hp);
        message.put("max_hp", MAX_HP);

        broadcastJson(message);
    }

    private static void killPlayer(int playerId, CombatState state) {
        state.dead = true;

        System.out.println("[DEATH] Player" + playerId);

        Map<String, Object> message = new HashMap<>();
        message.put("type", "player_dead");
        message.put("player_id", playerId);

        broadcastJson(message);

        respawnScheduler.schedule(() -> {
            System.out.println("[RESPAWN START] Player" + playerId);

            try {
                synchronized (combatLock) {
                    CombatState current = combatStates.get(playerId);

                    if (current != state || !current.dead) {
                        System.out.println(
                            "[RESPAWN CANCELLED] Player" + playerId
                        );
                        return;
                    }

                    current.dead = false;
                    current.hp = MAX_HP;

                    current.x = playerId == 1 ? 116 : 4250;
                    current.y = playerId == 1 ? 122 : 141;

                    playerPositions.put(playerId,
                        new double[]{current.x, current.y});

                    Map<String, Object> respawn = new HashMap<>();
                    respawn.put("type", "player_respawn");
                    respawn.put("player_id", playerId);
                    respawn.put("x", current.x);
                    respawn.put("y", current.y);
                    respawn.put("hp", current.hp);

                    broadcastJson(respawn);

                    System.out.println(
                        "[RESPAWN COMPLETE] Player" + playerId
                    );
                }
            } catch (Exception e) {
                System.err.println(
                    "[RESPAWN ERROR] Player" + playerId
                );
                e.printStackTrace();
            }
        }, RESPAWN_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private static void broadcastJson(Map<String, Object> message) {
        try {
            broadcast(MAPPER.writeValueAsString(message), -1);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void updateCombat() {
        final double dt = 1.0 / 60.0;

        synchronized (combatLock) {
            Iterator<ServerBullet> iterator = serverBullets.iterator();

            while (iterator.hasNext()) {
                ServerBullet bullet = iterator.next();

                double oldX = bullet.x;
                double oldY = bullet.y;

                bullet.x += bullet.vx * dt;
                bullet.y += bullet.vy * dt;
                bullet.age += dt;

                if (bullet.age >= BULLET_LIFETIME) {
                    iterator.remove();
                    continue;
                }

                for (Map.Entry<Integer, CombatState> entry :
                    combatStates.entrySet()) {

                    int targetId = entry.getKey();
                    CombatState target = entry.getValue();

                    if (targetId == bullet.ownerId || target.dead) {
                        continue;
                    }

                    // 총알이 한 프레임 동안 지나간 선분과 플레이어 사이 거리
                    double dx = bullet.x - oldX;
                    double dy = bullet.y - oldY;

                    double lengthSq = dx * dx + dy * dy;
                    double t = 0;

                    if (lengthSq > 0) {
                        t = ((target.x - oldX) * dx
                            + (target.y - oldY) * dy) / lengthSq;
                        t = Math.max(0, Math.min(1, t));
                    }

                    double closestX = oldX + dx * t;
                    double closestY = oldY + dy * t;

                    double distance = Math.hypot(
                        target.x - closestX,
                        target.y - closestY
                    );

                    if (distance <= HIT_RADIUS) {
                        iterator.remove();

                        target.hp = Math.max(0, target.hp - BULLET_DAMAGE);

                        broadcastHp(targetId, target);

                        if (target.hp == 0) {
                            killPlayer(targetId, target);
                        }

                        break;
                    }
                }
            }
        }
    }

    static void broadcast(String message, int exceptId) {
        for (Client client : clients.values()) {
            if (client.id != exceptId) {
                client.send(message);
            }
        }
    }

    private static void sendMatchResult() {
        synchronized (coinLock) {
            matchRunning = false;

            // 플레이어별 재화 가져오기
            int[] p1 = playerCurrencies.getOrDefault(1, new int[]{0, 0});
            int[] p2 = playerCurrencies.getOrDefault(2, new int[]{0, 0});

            int p1Silver = p1[0];
            int p1Diamond = p1[1];

            int p2Silver = p2[0];
            int p2Diamond = p2[1];

            // 승패 판정
            int winner = determineWinner(
                p1Diamond, p1Silver,
                p2Diamond, p2Silver
            );

            // 경기 결과 메시지 생성
            Map<String, Object> message = new HashMap<>();

            message.put("type", "match_end");
            message.put("winner", winner);

            message.put("p1_diamond", p1Diamond);
            message.put("p1_silver", p1Silver);

            message.put("p2_diamond", p2Diamond);
            message.put("p2_silver", p2Silver);

            broadcastJson(message);

            System.out.println("========== MATCH END ==========");
            System.out.println("Player1 Diamond: " + p1Diamond
                + " Silver: " + p1Silver);
            System.out.println("Player2 Diamond: " + p2Diamond
                + " Silver: " + p2Silver);

            System.out.println(
                winner == 0 ? "DRAW!" : "Player" + winner + " WINS!"
            );
        }
    }

    static class Client extends Thread {

        final int id;
        final Socket socket;
        final BufferedReader reader;
        final PrintWriter writer;

        Client(int id, Socket socket) throws IOException {
            this.id = id;
            this.socket = socket;
            this.reader = new BufferedReader(new InputStreamReader(
                socket.getInputStream(), StandardCharsets.UTF_8));
            this.writer = new PrintWriter(new OutputStreamWriter(
                socket.getOutputStream(), StandardCharsets.UTF_8), true);
        }

        synchronized void send(String message) {
            writer.println(message);
        }

        @Override
        public void run() {
            System.out.println("Player" + id + " 접속");

            send("{\"type\":\"welcome\",\"id\":" + id + "}");
            int[] currency = playerCurrencies.computeIfAbsent(
                id, key -> new int[2]
            );

            synchronized (combatLock) {
                CombatState state = new CombatState();

                state.x = id == 1 ? 116 : 4250;
                state.y = id == 1 ? 122 : 141;

                combatStates.put(id, state);
                playerPositions.put(id, new double[]{state.x, state.y});
            }

            send(MAPPER.createObjectNode()
                .put("type", "currency_update")
                .put("silver", currency[0])
                .put("diamond", currency[1])
                .toString());
            WeaponState weapon = new WeaponState();
            weapons.put(id, weapon);

            send(MAPPER.createObjectNode()
                .put("type", "ammo_update")
                .put("ammo", weapon.ammo)
                .put("reloading", false)
                .toString());
            for (String coinMessage : activeCoins.values()) {
                send(coinMessage);
            }

            try {
                String line;
                while ((line = reader.readLine()) != null) {

                    JsonNode json;

                    try {
                        json = MAPPER.readTree(line);
                    } catch (Exception e) {
                        System.out.println("잘못된 JSON: " + line);
                        continue;
                    }

                    if (json == null || !json.isObject()) {
                        continue;
                    }

                    String type = json.path("type").asText("");

                    if ("position".equals(type)) {

                        synchronized (combatLock) {
                            CombatState state = combatStates.get(id);
                            if (state == null || state.dead) {
                                continue;
                            }
                        }

                        JsonNode xNode = json.path("x");
                        JsonNode yNode = json.path("y");
                        JsonNode rotationNode = json.path("rotation");

                        if (!xNode.isNumber()
                            || !yNode.isNumber()
                            || !rotationNode.isNumber()) {
                            continue;
                        }

                        double x = xNode.asDouble();
                        double y = yNode.asDouble();
                        double rotation = rotationNode.asDouble();

                        if (!Double.isFinite(x)
                            || !Double.isFinite(y)
                            || !Double.isFinite(rotation)) {
                            continue;
                        }

                        playerPositions.put(id, new double[]{x, y});

                        synchronized (combatLock) {
                            CombatState state = combatStates.get(id);

                            if (state != null && !state.dead) {
                                state.x = x;
                                state.y = y;
                                state.rotation = rotation;
                            }
                        }

                        String message = MAPPER.createObjectNode()
                            .put("type", "position")
                            .put("id", id)
                            .put("x", x)
                            .put("y", y)
                            .put("rotation", rotation)
                            .toString();

                        broadcast(message, id);
                    }
                    else if ("collect_coin".equals(type)) {
                        JsonNode coinIdNode = json.path("coin_id");

                        if (!coinIdNode.isIntegralNumber()
                            || !coinIdNode.canConvertToInt()) {
                            continue;
                        }

                        int coinId = coinIdNode.asInt();
                        collectCoin(this, coinId);
                    }
                    else if ("shoot_request".equals(type)) {
                        handleShoot(this, json);
                    }
                    else if ("reload_request".equals(type)) {
                        handleReload(this);
                    }
                }
            } catch (IOException e) {
                System.err.println(
                    "[NETWORK ERROR] Player" + id + ": " + e.getMessage()
                );
                e.printStackTrace();
            } finally {
                System.out.println("[CLIENT FINALLY] Player" + id);
                synchronized (combatLock) {
                    combatStates.remove(id);
                    serverBullets.removeIf(b -> b.ownerId == id);
                }
                playerPositions.remove(id);
                clients.remove(id, this);
                weapons.remove(id, weapon);
                broadcast("{\"type\":\"leave\",\"id\":" + id + "}", id);

                try {
                    socket.close();
                } catch (IOException ignored) {
                }

                System.out.println("Player" + id + " 퇴장");
            }
        }
    }

    private static class ServerBullet {
        final int ownerId;
        double x;
        double y;
        final double vx;
        final double vy;
        double age = 0;

        ServerBullet(int ownerId, double x, double y, double rotation) {
            this.ownerId = ownerId;
            this.x = x;
            this.y = y;
            this.vx = Math.cos(rotation) * BULLET_SPEED;
            this.vy = Math.sin(rotation) * BULLET_SPEED;
        }
    }

    static class WeaponState {
        int ammo = MAGAZINE_SIZE;
        long lastShotNs = 0;
        boolean reloading = false;
    }

    static class SpawnPoint {
        final String key;
        final double x;
        final double y;

        SpawnPoint(String key, double x, double y) {
            this.key = key;
            this.x = x;
            this.y = y;
        }
    }

    static class SpawnerConfig {
        final String name;
        final String coinType;
        final long intervalMs;
        final int maxCoins;
        final List<SpawnPoint> points;

        SpawnerConfig(
            String name,
            String coinType,
            long intervalMs,
            int maxCoins,
            List<SpawnPoint> points) {

            this.name = name;
            this.coinType = coinType;
            this.intervalMs = intervalMs;
            this.maxCoins = maxCoins;
            this.points = points;
        }
    }
}
