import pytest
import math
from fastapi.testclient import TestClient
from app.main import app
from app.manager import haversine_distance, manager
from app.schemas import MessageType


def test_haversine_distance():
    # Same point -> 0 meters
    assert haversine_distance(45.0, 9.0, 45.0, 9.0) == 0.0

    # 1 degree latitude is approx 111.139 km
    dist = haversine_distance(45.0, 9.0, 46.0, 9.0)
    assert 111000 <= dist <= 112000

    # Small displacement (~111 meters)
    dist_small = haversine_distance(45.00000, 9.00000, 45.00100, 9.00000)
    assert 110.0 <= dist_small <= 112.5


def test_health_check():
    client = TestClient(app)
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json()["status"] == "ok"


def test_websocket_room_flow_and_switch_p2p():
    client = TestClient(app)
    room_code = "SYNC99"
    peer_a = "alice"
    peer_b = "bob"

    with client.websocket_connect(f"/ws/{room_code}/{peer_a}") as ws_a:
        # Peer A joins, room has 1 peer
        room_info = manager.get_room_info(room_code)
        assert room_info is not None
        assert room_info.peer_count == 1
        assert peer_a in room_info.peers

        with client.websocket_connect(f"/ws/{room_code}/{peer_b}") as ws_b:
            # Peer A should receive PEER_JOINED for Peer B
            msg_a = ws_a.receive_json()
            assert msg_a["type"] == MessageType.PEER_JOINED.value
            assert msg_a["payload"]["peer_id"] == peer_b

            # Try to connect Peer C (should be rejected as room is full with 2 peers)
            try:
                with client.websocket_connect(f"/ws/{room_code}/charlie") as ws_c:
                    msg_c = ws_c.receive_json()
                    assert msg_c["type"] == MessageType.ROOM_FULL.value
            except Exception:
                # WebSocket disconnect / rejection is expected
                pass

            # Alice sends location far away (> 50m, e.g. ~200m away)
            # Lat difference of ~0.002 deg is approx 222 meters
            ws_a.send_json({
                "type": "location",
                "payload": {
                    "latitude": 45.46420,
                    "longitude": 9.19000,
                    "accuracy": 5.0,
                    "timestamp": 1700000000.0
                }
            })

            # Bob receives peer_location
            msg_b_loc = ws_b.receive_json()
            assert msg_b_loc["type"] == MessageType.PEER_LOCATION.value
            assert msg_b_loc["payload"]["latitude"] == 45.46420

            # Bob sends location close to Alice (~20m away: 0.00018 deg lat diff is ~20m)
            ws_b.send_json({
                "type": "location",
                "payload": {
                    "latitude": 45.46435,
                    "longitude": 9.19000,
                    "accuracy": 4.0,
                    "timestamp": 1700000005.0
                }
            })

            # Alice receives Bob's location first
            msg_a_loc = ws_a.receive_json()
            assert msg_a_loc["type"] == MessageType.PEER_LOCATION.value

            # Now both Alice and Bob must receive SWITCH_P2P because distance <= 50m!
            msg_a_p2p = ws_a.receive_json()
            assert msg_a_p2p["type"] == MessageType.SWITCH_P2P.value
            assert msg_a_p2p["payload"]["distance_meters"] <= 50.0
            assert msg_a_p2p["payload"]["suggested_role"] in ["publisher", "subscriber"]

            msg_b_p2p = ws_b.receive_json()
            assert msg_b_p2p["type"] == MessageType.SWITCH_P2P.value
            assert msg_b_p2p["payload"]["distance_meters"] <= 50.0
            assert msg_b_p2p["payload"]["suggested_role"] != msg_a_p2p["payload"]["suggested_role"]
