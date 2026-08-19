import json
import logging
import random
import string
from fastapi import FastAPI, HTTPException, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse

from app.manager import manager
from app.schemas import MessageType, PeerMessage, RoomInfo

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger("crowdpulse.main")

app = FastAPI(
    title="CrowdPulse WebSocket Localization Hub",
    description="Backend for pairwise GPS tracking & automated P2P Wi-Fi handoff in dense environments.",
    version="0.1.0",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


from pathlib import Path
from fastapi.responses import HTMLResponse, FileResponse

STATIC_INDEX = Path(__file__).parent / "static" / "index.html"


@app.get("/", tags=["Demo"])
async def mobile_web_client():
    """Serves the mobile testing interface directly from verified static HTML."""
    if STATIC_INDEX.exists():
        return FileResponse(STATIC_INDEX, media_type="text/html")
    return HTMLResponse("<h1>CrowdPulse Server Running</h1>")



@app.get("/health", tags=["System"])
async def health_check():
    """Health check endpoint."""
    return {"status": "ok", "service": "crowdpulse-server", "active_rooms": len(manager.rooms)}


@app.post("/rooms/create", response_model=RoomInfo, tags=["Rooms"])
async def create_room():
    """Generates a random 6-character alphanumeric room code."""
    chars = string.ascii_uppercase + string.digits
    room_code = "".join(random.choices(chars, k=6))
    return RoomInfo(
        room_code=room_code,
        peer_count=0,
        peers=[],
        created_at=0.0,
        is_full=False,
    )


@app.get("/rooms/{room_code}", response_model=RoomInfo, tags=["Rooms"])
async def get_room(room_code: str):
    """Retrieves room status and peer count."""
    info = manager.get_room_info(room_code)
    if not info:
        raise HTTPException(status_code=404, detail=f"Room '{room_code}' not found or inactive")
    return info


@app.websocket("/ws/{room_code}/{peer_id}")
async def websocket_endpoint(websocket: WebSocket, room_code: str, peer_id: str):
    """
    WebSocket endpoint for pairwise localization.
    Accepts max 2 peers per room_code.
    """
    connected = await manager.connect(websocket=websocket, room_code=room_code, peer_id=peer_id)
    if not connected:
        return

    try:
        while True:
            raw_data = await websocket.receive_text()
            try:
                msg_dict = json.loads(raw_data)
                msg_type = msg_dict.get("type")
                payload = msg_dict.get("payload", {})

                if msg_type == MessageType.LOCATION.value or msg_type == "location":
                    await manager.process_location_update(
                        room_code=room_code,
                        sender_id=peer_id,
                        location_payload=payload,
                    )
                elif msg_type in [
                    MessageType.WEBRTC_OFFER.value,
                    MessageType.WEBRTC_ANSWER.value,
                    MessageType.WEBRTC_ICE.value,
                    "webrtc_offer",
                    "webrtc_answer",
                    "webrtc_ice",
                ]:
                    # Forward WebRTC P2P signaling directly to the other peer in room
                    await manager.broadcast_to_room(
                        room_code=room_code,
                        message=PeerMessage(
                            type=MessageType(msg_type),
                            sender_id=peer_id,
                            room_code=room_code,
                            payload=payload,
                        ),
                        exclude_peer=peer_id,
                    )
                elif msg_type == MessageType.PING.value or msg_type == "ping":
                    pong_msg = PeerMessage(
                        type=MessageType.PONG,
                        sender_id="server",
                        room_code=room_code,
                        payload={"reply_to": payload.get("seq")},
                    )
                    await websocket.send_text(pong_msg.model_dump_json())
                else:
                    logger.debug(f"Unhandled message type '{msg_type}' from peer '{peer_id}'")

            except json.JSONDecodeError:
                logger.warning(f"Invalid JSON received from peer '{peer_id}': {raw_data}")
            except Exception as e:
                logger.error(f"Error processing message from peer '{peer_id}': {e}", exc_info=True)

    except WebSocketDisconnect:
        await manager.disconnect(room_code=room_code, peer_id=peer_id)
    except Exception as e:
        logger.error(f"Unexpected error on WS for peer '{peer_id}': {e}")
        await manager.disconnect(room_code=room_code, peer_id=peer_id)

