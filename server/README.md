# FairFPS server variants

The folder now has three server scripts:

- `server.py`: original TCP position relay.
- `secure_server.py`: TCP movement-validation prototype.
- `weak_server.py`: separate, local-only vulnerable WebSocket game server.

## Start the WebSocket weak server

```powershell
cd C:\fair-fps\fair-fps
python -m pip install -r server\requirements.txt
python server\weak_server.py
```

It listens at `ws://127.0.0.1:7779/ws` by default. Set `FAIR_FPS_WEAK_WS_PORT` to change the port. Keep it local: this server intentionally trusts client-supplied positions and game state, allows attacks without ammo/cooldown/aim checks, and broadcasts all players' ammo. Console output records accepted events for the demonstration.

The server accepts `move` with `x/y`, `state` with `x/y/hp/ammo/alive`, `shoot` with optional `damage`, and `reload` messages. It assigns up to two player IDs and sends `welcome` and `state` messages. The existing Godot client still uses TCP and cannot connect to this WebSocket server until its network manager is migrated.


