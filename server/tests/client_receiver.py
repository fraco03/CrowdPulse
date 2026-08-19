"""
Client Receiver / Peer B Simulation Script for CrowdPulse.
Simulates a static or slowly moving user waiting for Peer A.
"""

import asyncio
import json
import logging
import sys
import websockets

logging.basicConfig(level=logging.INFO, format="%(asctime)s [Peer B] %(message)s")
logger = logging.getLogger("PeerB")

SERVER_WS_URL = "ws://127.0.0.1:8000/ws"
ROOM_CODE = "FEST24"
PEER_ID = "peer_bob"

# Static coordinate (Main Stage area)
BOB_LAT = 45.46420
BOB_LON = 9.19000


async def run_receiver():
    uri = f"{SERVER_WS_URL}/{ROOM_CODE}/{PEER_ID}"
    logger.info(f"Connecting to {uri}...")

    async with websockets.connect(uri) as websocket:
        logger.info(f"Connected to room '{ROOM_CODE}' as '{PEER_ID}'")

        # Periodically publish Bob's location
        async def publish_location():
            while True:
                payload = {
                    "type": "location",
                    "payload": {
                        "latitude": BOB_LAT,
                        "longitude": BOB_LON,
                        "accuracy": 4.0,
                        "heading": 0.0,
                        "speed": 0.0,
                    },
                }
                await websocket.send(json.dumps(payload))
                logger.info(f"Sent static location ({BOB_LAT:.5f}, {BOB_LON:.5f})")
                await asyncio.sleep(2.0)

        pub_task = asyncio.create_task(publish_location())

        try:
            async for message in websocket:
                data = json.loads(message)
                msg_type = data.get("type")
                if msg_type == "switch_p2p":
                    dist = data["payload"]["distance_meters"]
                    role = data["payload"]["suggested_role"]
                    logger.warning(
                        f"⚡ [HANDOFF TRIGGERED] Distance = {dist:.2f}m <= 50m! Switch to Wi-Fi Aware NAN ({role})"
                    )
                elif msg_type == "peer_location":
                    loc = data.get("payload", {})
                    logger.info(f"Peer Alice location: lat={loc.get('latitude')}, lon={loc.get('longitude')}")
                elif msg_type == "peer_joined":
                    logger.info("Peer Alice joined the room!")
                elif msg_type == "peer_left":
                    logger.warning("Peer Alice left the room.")
                else:
                    logger.info(f"Received message: {msg_type}")
        except websockets.exceptions.ConnectionClosed:
            logger.info("Connection closed by server.")
        finally:
            pub_task.cancel()


if __name__ == "__main__":
    try:
        asyncio.run(run_receiver())
    except KeyboardInterrupt:
        logger.info("Terminated by user.")
