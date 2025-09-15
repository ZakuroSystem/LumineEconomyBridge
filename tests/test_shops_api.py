import base64
import hashlib
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


def test_shop_items_includes_owners_when_suspended():
    with main.conn:
        main.conn.execute("DELETE FROM shop_locations")
        main.conn.execute("DELETE FROM shop_owners")
        main.conn.execute("DELETE FROM shops")
        now = int(time.time())
        main.conn.execute(
            "INSERT INTO shops(shop_id, owner_uuid, status, created_at, last_activity_at) VALUES(?,?,?,?,?)",
            ("s2", "u2", "suspended", now, now),
        )
        main.conn.execute(
            "INSERT INTO shop_owners(shop_id, owner_uuid) VALUES(?,?)",
            ("s2", "u2"),
        )
    with TestClient(app) as client:
        resp = client.get("/api/shop/items", params={"shop_id": "s2"})
        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "suspended"
        assert data["owners"] == ["u2"]


def test_shop_add_stock_requires_token():
    with main.conn:
        main.conn.execute("DELETE FROM shop_stock")
        main.conn.execute("DELETE FROM shop_prices")
        main.conn.execute("DELETE FROM shop_items")
        main.conn.execute("DELETE FROM shop_owners")
        main.conn.execute("DELETE FROM shops")
        now = int(time.time())
        main.conn.execute(
            "INSERT INTO shops(shop_id, owner_uuid, status, created_at, last_activity_at) VALUES(?,?,?,?,?)",
            ("s3", "owner-uuid", "active", now, now),
        )
        main.conn.execute(
            "INSERT INTO shop_owners(shop_id, owner_uuid) VALUES(?,?)",
            ("s3", "owner-uuid"),
        )
    blob_bytes = b"demo-item"
    blob = base64.b64encode(blob_bytes).decode("ascii")
    payload = {
        "owner_uuid": "owner-uuid",
        "shop_id": "s3",
        "nbt_blob": blob,
        "material": "STONE",
        "display_name": "Demo",
        "qty": 1,
        "price": 100,
        "sale_name": "demo",
        "currency": "thy",
    }
    with TestClient(app) as client:
        resp = client.post(
            "/api/shop/add_stock",
            json=payload,
            headers={"X-LE-Token": "invalid"},
        )
        assert resp.status_code == 401
        resp = client.post(
            "/api/shop/add_stock",
            json=payload,
            headers={"X-LE-Token": main.SHARED_TOKEN},
        )
        assert resp.status_code == 200
        assert resp.json()["status"] == "success"
    item_key = hashlib.sha256(blob_bytes).hexdigest()
    with main.conn:
        stock_row = main.conn.execute(
            "SELECT stock FROM shop_stock WHERE shop_id=? AND item_key=?",
            ("s3", item_key),
        ).fetchone()
        assert stock_row and stock_row["stock"] == 1
