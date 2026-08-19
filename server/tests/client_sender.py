"""
Smart Client Sender / Peer Alice Simulation Script for CrowdPulse.
Dynamically locks onto the real phone's GPS position and spawns
at ~90 meters distance, walking step-by-step towards the phone
to test the 50m P2P trigger in real-time.
"""

import asyncio
import json
import logging
import math
import sys
import websockets

logging.basicConfig(level=logging.INFO, format="%(asctime)s [Peer Alice] %(message)s")
logger = logging.getLogger("PeerAlice")

SERVER_WS_URL = "wss://127.0.0.1:8443/ws"
# Fallback non-SSL url
FALLBACK_WS_URL = "ws://127.0.0.1:8000/ws"
ROOM_CODE = "FEST24"
PEER_ID = "zzz_alice"  # Must sort AFTER phone_user so phone gets publisher (initiator) role


def haversine(lat1, lon1, lat2, lon2):
    R = 6371000.0
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlam = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(dlam / 2) ** 2
    return R * 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))


async def run_sender():
    import ssl
    ssl_context = ssl.create_default_context()
    ssl_context.check_hostname = False
    ssl_context.verify_mode = ssl.CERT_NONE

    uri = f"{SERVER_WS_URL}/{ROOM_CODE}/{PEER_ID}"
    logger.info(f"Connecting to {uri}...")

    try:
        ws_conn = websockets.connect(uri, ssl=ssl_context)
        websocket = await ws_conn
    except Exception as e:
        logger.warning(f"SSL connect failed ({e}), falling back to {FALLBACK_WS_URL}...")
        uri = f"{FALLBACK_WS_URL}/{ROOM_CODE}/{PEER_ID}"
        ws_conn = websockets.connect(uri)
        websocket = await ws_conn

    logger.info(f"Connected to room '{ROOM_CODE}' as '{PEER_ID}'!")
    logger.info("⏳ Waiting for the real phone to send its first GPS coordinate...")

    phone_lat = None
    phone_lon = None
    gps_event = asyncio.Event()

    async def listen():
        nonlocal phone_lat, phone_lon
        try:
            async for message in websocket:
                data = json.loads(message)
                msg_type = data.get("type")
                if msg_type == "peer_location":
                    payload = data.get("payload", {})
                    phone_lat = payload.get("latitude")
                    phone_lon = payload.get("longitude")
                    if not gps_event.is_set():
                        logger.info(f"🎯 Target phone located at GPS ({phone_lat:.5f}, {phone_lon:.5f})!")
                        gps_event.set()
                elif msg_type == "switch_p2p":
                    dist = data["payload"]["distance_meters"]
                    role = data["payload"]["suggested_role"]
                    logger.warning(
                        f"⚡ [P2P TRIGGER RECEIVED] Distance = {dist:.2f}m <= 50m! Switch to P2P ({role})"
                    )
                elif msg_type == "peer_joined":
                    logger.info(f"Peer '{data.get('payload', {}).get('peer_id')}' joined the room.")
                else:
                    logger.debug(f"Received: {msg_type}")
        except websockets.exceptions.ConnectionClosed:
            logger.info("Connection closed by server.")

    listener_task = asyncio.create_task(listen())

    # Wait for phone's GPS with a timeout of 120 seconds
    try:
        await asyncio.wait_for(gps_event.wait(), timeout=120.0)
    except asyncio.TimeoutError:
        logger.warning("Timeout waiting for real phone GPS. Using default coordinates.")
        phone_lat = 45.46420
        phone_lon = 9.19000

    # Place Alice 90 meters North of the user's actual phone position
    # 1 deg lat is approx 111,139 meters => 0.0008 deg is ~88.9 meters
    start_lat = phone_lat + 0.00080
    start_lon = phone_lon

    initial_dist = haversine(start_lat, start_lon, phone_lat, phone_lon)
    logger.info(f"🚶 Starting walk towards phone! Initial distance: {initial_dist:.1f} meters")

    steps = 15
    for i in range(steps + 1):
        fraction = i / float(steps)
        # Walk towards phone, ending 5 meters away
        cur_lat = start_lat + fraction * ((phone_lat + 0.00005) - start_lat)
        cur_lon = start_lon + fraction * (phone_lon - start_lon)

        dist_to_phone = haversine(cur_lat, cur_lon, phone_lat, phone_lon)

        payload = {
            "type": "location",
            "payload": {
                "latitude": cur_lat,
                "longitude": cur_lon,
                "accuracy": 3.0,
                "heading": 180.0,
                "speed": 1.2,
                "timestamp": asyncio.get_event_loop().time(),
            },
        }
        await websocket.send(json.dumps(payload))
        logger.info(
            f"Step {i:02d}/{steps}: Sent GPS position. Distance to phone: {dist_to_phone:.1f} m"
        )
        await asyncio.sleep(1.2)

    logger.info("Walk complete. Maintaining position 5 meters from phone for 30 seconds...")
    await asyncio.sleep(30.0)
    listener_task.cancel()
    await websocket.close()


if __name__ == "__main__":
    try:
        asyncio.run(run_sender())
    except KeyboardInterrupt:
        logger.info("Simulation terminated by user.")
