import os
import sys
import time

BACKEND_DIR = os.path.join(os.path.dirname(__file__), "..", "backend")
sys.path.append(BACKEND_DIR)

from fastapi.testclient import TestClient

# change cwd so main can read lang.yml
CWD = os.getcwd()
os.chdir(BACKEND_DIR)
import main
app = main.app
os.chdir(CWD)


def test_shops_endpoint():
    with main.conn:
        main.conn.execute("DELETE FROM shop_locations")
        main.conn.execute("DELETE FROM shops")
        now = int(time.time())
        main.conn.execute(
            "INSERT INTO shops(shop_id, owner_uuid, status, created_at, last_activity_at) VALUES(?,?,?,?,?)",
            ("s1", "u1", "active", now, now),
        )
        main.conn.execute(
            "INSERT INTO shop_locations(shop_id, world, x, y, z) VALUES(?,?,?,?,?)",
            ("s1", "world", 1.0, 64.0, 2.0),
        )
    headers = {"X-LE-Token": main.SHARED_TOKEN}
    with TestClient(app) as client:
        resp = client.get("/shops", headers=headers)
        assert resp.status_code == 200
        data = resp.json()
        assert data and data[0]["shop_id"] == "s1"
        assert data[0]["world"] == "world"


def test_shop_place_owner_check():
    with main.conn:
        main.conn.execute("DELETE FROM shop_locations")
        main.conn.execute("DELETE FROM shops")
        now = int(time.time())
        main.conn.execute(
            "INSERT INTO shops(shop_id, owner_uuid, status, created_at, last_activity_at) VALUES(?,?,?,?,?)",
            ("s1", "u1", "active", now, now),
        )
    headers = {"X-LE-Token": main.SHARED_TOKEN}
    payload = {
        "shop_id": "s1",
        "owner_uuid": "u2",
        "world": "world",
        "x": 0,
        "y": 64,
        "z": 0,
        "timestamp": int(time.time()),
    }
    with TestClient(app) as client:
        resp = client.post("/api/shop/place", json=payload, headers=headers)
        assert resp.status_code == 403

    payload["owner_uuid"] = "u1"
    with TestClient(app) as client:
        resp = client.post("/api/shop/place", json=payload, headers=headers)
        assert resp.status_code == 200
