from enum import Enum
import time
from typing import Any, Dict, List, Optional
from pydantic import BaseModel, Field


class MessageType(str, Enum):
    # Lifecycle & Handshake
    JOIN = "join"
    PEER_JOINED = "peer_joined"
    PEER_LEFT = "peer_left"
    ROOM_FULL = "room_full"
    
    # Macro-Localization (Stage 1)
    LOCATION = "location"
    PEER_LOCATION = "peer_location"
    
    # State Transitions
    SWITCH_P2P = "switch_p2p"  # Emitted when distance <= 50m
    SWITCH_MACRO = "switch_macro"  # Emitted if distance goes back > 50m or P2P lost
    
    # WebRTC P2P Signaling
    WEBRTC_OFFER = "webrtc_offer"
    WEBRTC_ANSWER = "webrtc_answer"
    WEBRTC_ICE = "webrtc_ice"
    
    # Control & Heartbeat
    PING = "ping"
    PONG = "pong"
    ERROR = "error"


class LocationPayload(BaseModel):
    latitude: float = Field(..., ge=-90.0, le=90.0, description="Latitude in decimal degrees")
    longitude: float = Field(..., ge=-180.0, le=180.0, description="Longitude in decimal degrees")
    accuracy: Optional[float] = Field(None, ge=0.0, description="GPS/Fused accuracy in meters")
    altitude: Optional[float] = Field(None, description="Altitude in meters")
    heading: Optional[float] = Field(None, ge=0.0, le=360.0, description="Bearing/heading in degrees")
    speed: Optional[float] = Field(None, ge=0.0, description="Speed in m/s")
    timestamp: float = Field(default_factory=time.time, description="Timestamp in epoch seconds")


class SwitchP2PPayload(BaseModel):
    distance_meters: float = Field(..., description="Geodesic distance between peers in meters")
    threshold_meters: float = Field(default=50.0, description="Trigger threshold in meters")
    peer_id: str = Field(..., description="The ID of the other peer")
    peer_location: Optional[LocationPayload] = Field(None, description="Last known location of peer")
    suggested_role: Optional[str] = Field(
        None, description="Suggested Wi-Fi Aware NAN role: 'publisher' or 'subscriber'"
    )


class PeerMessage(BaseModel):
    type: MessageType
    sender_id: str
    room_code: str
    timestamp: float = Field(default_factory=time.time)
    payload: Optional[Dict[str, Any]] = None


class RoomInfo(BaseModel):
    room_code: str
    peer_count: int
    peers: List[str]
    created_at: float
    is_full: bool
