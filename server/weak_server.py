from __future__ import annotations

import asyncio
import json
import os
from datetime import datetime, timezone

from websockets.asyncio.server import ServerConnection, serve

HOST = os.getenv("FAIR_FPS_HOST", "127.0.0.1")
PORT = int(os.getenv("FAIR_FPS_WEAK_WS_PORT", "7779"))
MAX_MESSAGE = 8192
MAX_AMMO = 24


class WeakGameServer:
    def __init__(self) -> None:
        self.clients: dict[int, ServerConnection] = {}
        self.players: dict[int, dict] = {}

    async def handle(self, ws: ServerConnection) -> None:
        if ws.request.path != "/ws":
            await ws.close(code=1008, reason="use /ws")
            return
        player_id = next((i for i in (1, 2) if i not in self.clients), None)
        if player_id is None:
            await ws.send(json.dumps({"type": "full"}))
            await ws.close(code=1008, reason="room full")
            return

        self.clients[player_id] = ws
        self.players[player_id] = {
            "id": player_id,
            "x": 116.0 if player_id == 1 else 350.0,
            "y": 122.0 if player_id == 1 else 141.0,
            "hp": 100,
            "ammo": MAX_AMMO,
            "alive": True,
        }
        self.log(player_id, "join", "connected")
        await ws.send(json.dumps({"type": "welcome", "id": player_id, "mode": "weak"}))
        await self.broadcast()

        try:
            async for raw in ws:
                if len(raw.encode("utf-8")) > MAX_MESSAGE:
                    self.log(player_id, "oversized_message", "message ignored")
                    continue
                try:
                    request = json.loads(raw)
                except (json.JSONDecodeError, TypeError):
                    self.log(player_id, "malformed_request", "invalid JSON ignored")
                    continue
                if not isinstance(request, dict):
                    continue
                await self.handle_request(player_id, request)
                await self.broadcast()
        finally:
            self.clients.pop(player_id, None)
            self.players.pop(player_id, None)
            self.log(player_id, "leave", "disconnected")
            await self.broadcast()

    async def handle_request(self, player_id: int, request: dict) -> None:
        player = self.players.get(player_id)
        if player is None:
            return
        request_type = request.get("type")

        if request_type == "move":
            self.copy_number(request, player, "x")
            self.copy_number(request, player, "y")
        elif request_type == "state":
            for key in ("x", "y", "hp", "ammo", "alive"):
                if key in request and isinstance(request[key], (int, float, bool)):
                    player[key] = request[key]
            self.log(player_id, "client_state_accepted", "client supplied game state")
        elif request_type == "shoot":
            target_id = next((i for i in self.players if i != player_id), None)
            if target_id is None:
                return
            target = self.players[target_id]
            # Intentionally weak: no ammo, cooldown, aim, range, or damage validation.
            damage = request.get("damage", 10)
            if isinstance(damage, (int, float)) and not isinstance(damage, bool):
                target["hp"] = max(0, target["hp"] - int(damage))
                target["alive"] = target["hp"] > 0
            player["ammo"] = max(0, player["ammo"] - 1)
            self.log(player_id, "shot_accepted", f"target={target_id}, damage={damage}")
        elif request_type == "reload":
            player["ammo"] = MAX_AMMO
            self.log(player_id, "reload_accepted", "ammo restored")

    @staticmethod
    def copy_number(request: dict, player: dict, key: str) -> None:
        value = request.get(key)
        if isinstance(value, (int, float)) and not isinstance(value, bool):
            player[key] = value

    async def broadcast(self) -> None:
        if not self.clients:
            return
        # Deliberately exposes every player's state, including ammo, for the weak-server demo.
        payload = json.dumps({"type": "state", "players": list(self.players.values())}, allow_nan=True)
        for ws in list(self.clients.values()):
            try:
                await ws.send(payload)
            except Exception:
                pass

    @staticmethod
    def log(player_id: int, event: str, detail: str) -> None:
        timestamp = datetime.now(timezone.utc).isoformat(timespec="seconds")
        print(f"{timestamp} mode=weak player={player_id} event={event} detail={detail}", flush=True)


async def main() -> None:
    server = WeakGameServer()
    async with serve(server.handle, HOST, PORT, max_size=MAX_MESSAGE):
        print(f"FairFPS weak WebSocket server listening on ws://{HOST}:{PORT}/ws", flush=True)
        await asyncio.Future()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("Server stopped")


