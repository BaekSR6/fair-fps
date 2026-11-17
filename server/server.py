from __future__ import annotations

import json
import math
import os
import socketserver
import threading

HOST = os.getenv("FAIR_FPS_HOST", "0.0.0.0")
PORT = int(os.getenv("FAIR_FPS_PORT", "7777"))
MAX_LINE = 8192


class GameServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

    def __init__(self, address):
        super().__init__(address, Client)
        self.lock = threading.Lock()
        self.clients: dict[int, object] = {}

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


class Client(socketserver.StreamRequestHandler):
    def handle(self) -> None:
        server: GameServer = self.server
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

                x, y, rotation = (message.get(key) for key in ("x", "y", "rotation"))
                if any(type(value) not in (int, float) or not math.isfinite(value)
                       for value in (x, y, rotation)):
                    continue
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
    with GameServer((HOST, PORT)) as game_server:
        print(f"FairFPS server listening on {HOST}:{PORT}")
        try:
            game_server.serve_forever()
        except KeyboardInterrupt:
            print("\nServer stopped")
