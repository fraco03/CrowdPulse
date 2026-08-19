import logging
import math
import time
from typing import Dict, Optional
from fastapi import WebSocket, WebSocketDisconnect

from app.schemas import (
    LocationPayload,
    MessageType,
    PeerMessage,
    RoomInfo,
    SwitchP2PPayload,
)

logger = logging.getLogger("crowdpulse.manager")


def haversine_distance(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """
    Calculate the great-circle distance between two points on Earth
    using the Haversine formula.

    Returns:
        Distance in meters.
    """
    R = 6371000.0  # Earth's radius in meters

    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    delta_phi = math.radians(lat2 - lat1)
    delta_lambda = math.radians(lon2 - lon1)

    a = (
        math.sin(delta_phi / 2.0) ** 2
        + math.cos(phi1) * math.cos(phi2) * math.sin(delta_lambda / 2.0) ** 2
    )
    c = 2.0 * math.atan2(math.sqrt(a), math.sqrt(1.0 - a))

    return R * c


class ConnectionManager:
    """
    Manages WebSocket peer connections grouped into temporary rooms.
    Strictly enforces a maximum of 2 peers per room for pairwise localization.
    Tracks GPS coordinates, calculates geodetic distance, and triggers P2P handoff.
    """

    MAX_PEERS_PER_ROOM = 2
    P2P_THRESHOLD_METERS = 50.0
    P2P_HYSTERESIS_METERS = 55.0  # To prevent flapping near boundary

    def __init__(self):
        # room_code -> {peer_id: WebSocket}
        self.rooms: Dict[str, Dict[str, WebSocket]] = {}
        # room_code -> {peer_id: LocationPayload}
        self.peer_locations: Dict[str, Dict[str, LocationPayload]] = {}
        # room_code -> creation epoch timestamp
        self.room_created_at: Dict[str, float] = {}
        # room_code -> bool (whether P2P mode is currently active)
        self.p2p_active_state: Dict[str, bool] = {}

    async def connect(self, websocket: WebSocket, room_code: str, peer_id: str) -> bool:
        """
        Accepts WebSocket connection and joins peer to the room.
        Returns False if room is already full (>= 2 peers).
        """
        await websocket.accept()

        if room_code not in self.rooms:
            self.rooms[room_code] = {}
            self.peer_locations[room_code] = {}
            self.room_created_at[room_code] = time.time()
            self.p2p_active_state[room_code] = False

        current_peers = self.rooms[room_code]

        # Reject if already 2 peers and new peer is trying to join
        if len(current_peers) >= self.MAX_PEERS_PER_ROOM and peer_id not in current_peers:
            err_msg = PeerMessage(
                type=MessageType.ROOM_FULL,
                sender_id="server",
                room_code=room_code,
                payload={"error": f"Room '{room_code}' is full (max 2 peers)."},
            )
            await websocket.send_text(err_msg.model_dump_json())
            await websocket.close(code=4003, reason="Room full")
            logger.warning(f"Connection rejected: room '{room_code}' already has 2 peers.")
            return False

        # Store connection
        self.rooms[room_code][peer_id] = websocket
        logger.info(f"Peer '{peer_id}' joined room '{room_code}'. Total peers: {len(self.rooms[room_code])}")

        # Notify other peer in the room if present
        await self.broadcast_to_room(
            room_code=room_code,
            message=PeerMessage(
                type=MessageType.PEER_JOINED,
                sender_id=peer_id,
                room_code=room_code,
                payload={"peer_id": peer_id, "peer_count": len(self.rooms[room_code])},
            ),
            exclude_peer=peer_id,
        )

        return True

    async def disconnect(self, room_code: str, peer_id: str):
        """
        Removes peer from room and notifies remaining peer.
        Cleans up room data if empty.
        """
        if room_code in self.rooms:
            if peer_id in self.rooms[room_code]:
                del self.rooms[room_code][peer_id]
                logger.info(f"Peer '{peer_id}' left room '{room_code}'.")

            if room_code in self.peer_locations and peer_id in self.peer_locations[room_code]:
                del self.peer_locations[room_code][peer_id]

            if len(self.rooms[room_code]) > 0:
                # Notify the remaining peer
                await self.broadcast_to_room(
                    room_code=room_code,
                    message=PeerMessage(
                        type=MessageType.PEER_LEFT,
                        sender_id=peer_id,
                        room_code=room_code,
                        payload={"peer_id": peer_id},
                    ),
                )
            else:
                # Room is empty, clean up
                del self.rooms[room_code]
                if room_code in self.peer_locations:
                    del self.peer_locations[room_code]
                if room_code in self.room_created_at:
                    del self.room_created_at[room_code]
                if room_code in self.p2p_active_state:
                    del self.p2p_active_state[room_code]
                logger.info(f"Room '{room_code}' deleted as all peers disconnected.")

    async def process_location_update(
        self, room_code: str, sender_id: str, location_payload: dict
    ):
        """
        Stores latest GPS location, forwards it to the peer,
        calculates distance if both peers have reported, and triggers
        switch_p2p when distance <= 50m.
        """
        loc = LocationPayload(**location_payload)
        if room_code not in self.peer_locations:
            self.peer_locations[room_code] = {}
        self.peer_locations[room_code][sender_id] = loc

        # 1. Forward location to other peer in room
        await self.broadcast_to_room(
            room_code=room_code,
            message=PeerMessage(
                type=MessageType.PEER_LOCATION,
                sender_id=sender_id,
                room_code=room_code,
                payload=loc.model_dump(),
            ),
            exclude_peer=sender_id,
        )

        # 2. Check if both peers in room have locations
        room_locs = self.peer_locations.get(room_code, {})
        peer_ids = list(self.rooms.get(room_code, {}).keys())

        if len(peer_ids) == 2 and all(p in room_locs for p in peer_ids):
            peer_a, peer_b = peer_ids[0], peer_ids[1]
            loc_a = room_locs[peer_a]
            loc_b = room_locs[peer_b]

            distance = haversine_distance(
                loc_a.latitude, loc_a.longitude, loc_b.latitude, loc_b.longitude
            )

            is_p2p_active = self.p2p_active_state.get(room_code, False)

            # Check threshold <= 50m for P2P activation
            if distance <= self.P2P_THRESHOLD_METERS and not is_p2p_active:
                self.p2p_active_state[room_code] = True
                logger.info(
                    f"Threshold crossed in room '{room_code}': distance = {distance:.2f}m <= {self.P2P_THRESHOLD_METERS}m. Triggering SWITCH_P2P!"
                )

                # Deterministic role assignment: lower string ID publishes NAN, higher subscribes
                roles = (
                    ("publisher", "subscriber")
                    if peer_a < peer_b
                    else ("subscriber", "publisher")
                )

                # Send switch_p2p to peer A
                ws_a = self.rooms[room_code].get(peer_a)
                if ws_a:
                    msg_a = PeerMessage(
                        type=MessageType.SWITCH_P2P,
                        sender_id="server",
                        room_code=room_code,
                        payload=SwitchP2PPayload(
                            distance_meters=distance,
                            threshold_meters=self.P2P_THRESHOLD_METERS,
                            peer_id=peer_b,
                            peer_location=loc_b,
                            suggested_role=roles[0],
                        ).model_dump(),
                    )
                    await ws_a.send_text(msg_a.model_dump_json())

                # Send switch_p2p to peer B
                ws_b = self.rooms[room_code].get(peer_b)
                if ws_b:
                    msg_b = PeerMessage(
                        type=MessageType.SWITCH_P2P,
                        sender_id="server",
                        room_code=room_code,
                        payload=SwitchP2PPayload(
                            distance_meters=distance,
                            threshold_meters=self.P2P_THRESHOLD_METERS,
                            peer_id=peer_a,
                            peer_location=loc_a,
                            suggested_role=roles[1],
                        ).model_dump(),
                    )
                    await ws_b.send_text(msg_b.model_dump_json())

            # If peers move away > 55m (hysteresis), optionally notify return to macro
            elif distance > self.P2P_HYSTERESIS_METERS and is_p2p_active:
                self.p2p_active_state[room_code] = False
                logger.info(
                    f"Distance increased in room '{room_code}': {distance:.2f}m > {self.P2P_HYSTERESIS_METERS}m. Reverting to macro."
                )
                await self.broadcast_to_room(
                    room_code=room_code,
                    message=PeerMessage(
                        type=MessageType.SWITCH_MACRO,
                        sender_id="server",
                        room_code=room_code,
                        payload={"distance_meters": distance},
                    ),
                )

    async def broadcast_to_room(
        self, room_code: str, message: PeerMessage, exclude_peer: Optional[str] = None
    ):
        """Broadcasts a JSON-serialized PeerMessage to active WebSockets in the room."""
        if room_code not in self.rooms:
            return

        json_str = message.model_dump_json()
        for peer_id, ws in list(self.rooms[room_code].items()):
            if exclude_peer and peer_id == exclude_peer:
                continue
            try:
                await ws.send_text(json_str)
            except Exception as e:
                logger.error(f"Error sending message to peer '{peer_id}' in room '{room_code}': {e}")

    def get_room_info(self, room_code: str) -> Optional[RoomInfo]:
        """Returns metadata about a room if active."""
        if room_code not in self.rooms:
            return None
        peers = list(self.rooms[room_code].keys())
        return RoomInfo(
            room_code=room_code,
            peer_count=len(peers),
            peers=peers,
            created_at=self.room_created_at.get(room_code, 0.0),
            is_full=len(peers) >= self.MAX_PEERS_PER_ROOM,
        )


# Global singleton instance
manager = ConnectionManager()
