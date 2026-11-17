from __future__ import annotations

import json
import math
import os
import socketserver
import threading
import time

HOST = os.getenv("FAIR_FPS_HOST", "0.0.0.0")
PORT = int(os.getenv("FAIR_FPS_PORT", "7777"))
MAX_LINE = 8192
MAX_SPEED = 250.0
MOVEMENT_TOLERANCE = 25.0
MAX_VIOLATIONS = 3
SPAWNS = {1: (116.0, 122.0), 2: (350.0, 141.0)}


class SecureGameServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

    def __init__(self, address):
        super().__init__(address, Client)
        self.lock = threading.Lock()
        self.clients: dict[int, object] = {}
        self.positions = {player_id: spawn for player_id, spawn in SPAWNS.items()}
        self.updated_at = {player_id: time.monotonic() for player_id in SPAWNS}
        self.violations = {player_id: 0 for player_id in SPAWNS}

    def send(self, client, message: dict) -> None:
        client.sendall((json.dumps(message, separators=(",", ":")) + "\n").encode())

    def broadcast(self, message: dict, exclude=None) -> None:
        payload = (json.dumps(message, separators=(",", ":")) + "\n").encode()
        with self.lock:
            for peer in list(self.clients.values()):
                if peer is exclude:
                    continue
                try:
                    peer.sendall(payload)
                except OSError:
                    pass

    def accept_position(self, player_id: int, x: float, y: float) -> bool:
        with self.lock:
            now = time.monotonic()
            previous = self.positions[player_id]
            elapsed = now - self.updated_at[player_id]
            allowed_distance = MAX_SPEED * elapsed + MOVEMENT_TOLERANCE
            distance = math.hypot(x - previous[0], y - previous[1])

            if distance > allowed_distance:
                self.violations[player_id] += 1
                print(f"[DETECTED] player={player_id} invalid movement "
                      f"({distance:.1f}px > {allowed_distance:.1f}px), "
                      f"count={self.violations[player_id]}")
                return self.violations[player_id] < MAX_VIOLATIONS

            self.positions[player_id] = (x, y)
            self.updated_at[player_id] = now
            return True


class Client(socketserver.StreamRequestHandler):
    def handle(self) -> None:
        server: SecureGameServer = self.server
        self.request.settimeout(60)

        with server.lock:
            player_id = next((i for i in (1, 2) if i not in server.clients), None)
            if player_id is None:
                server.send(self.request, {"type": "full"})
                return
            server.clients[player_id] = self.request

        try:
            server.send(self.request, {"type": "welcome", "id": player_id})
            while True:
                line = self.rfile.readline(MAX_LINE + 1)
                if not line:
                    break
                if len(line) > MAX_LINE or not line.endswith(b"\n"):
                    break
                try:
                    message = json.loads(line)
                except (json.JSONDecodeError, UnicodeDecodeError):
                    continue
                if not isinstance(message, dict) or message.get("type") != "position":
                    continue

                values = (message.get(key) for key in ("x", "y", "rotation"))
                try:
                    if any(type(value) not in (int, float) for value in values):
                        continue
                    x, y, rotation = (float(message[key]) for key in ("x", "y", "rotation"))
                except (OverflowError, TypeError, ValueError):
                    continue
                if not all(math.isfinite(value) for value in (x, y, rotation)):
                    continue
                if not server.accept_position(player_id, x, y):
                    break
                server.broadcast({
                    "type": "position", "id": player_id,
                    "x": x, "y": y, "rotation": rotation,
                }, exclude=self.request)
        except (OSError, TimeoutError):
            pass
        finally:
            with server.lock:
                if server.clients.get(player_id) is self.request:
                    del server.clients[player_id]
            server.broadcast({"type": "leave", "id": player_id}, exclude=self.request)


if __name__ == "__main__":
    with SecureGameServer((HOST, PORT)) as game_server:
        print(f"FairFPS secure server listening on {HOST}:{PORT}")
        try:
            game_server.serve_forever()
        except KeyboardInterrupt:
            print("\nServer stopped")

