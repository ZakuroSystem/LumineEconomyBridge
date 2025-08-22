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


def test_buy_price_qty():
    with main.conn:
        main.conn.execute("DELETE FROM shop_locations")
        main.conn.execute("DELETE FROM shops")
        main.conn.execute("DELETE FROM shop_items")
        main.conn.execute("DELETE FROM shop_stock")
        main.conn.execute("DELETE FROM shop_prices")
        main.conn.execute("DELETE FROM accounts")
        now = int(time.time())
        main.conn.execute(
            "INSERT INTO shops(shop_id, owner_uuid, status, created_at, last_activity_at) VALUES(?,?,?,?,?)",
            ("s1", "owner", "active", now, now),
        )
        main.conn.execute(
            "INSERT INTO shop_locations(shop_id, world, x, y, z) VALUES(?,?,?,?,?)",
            ("s1", "world", 0, 0, 0),
        )
        main.conn.execute(
            "INSERT INTO shop_items(item_key, material, display_name, nbt_blob) VALUES(?,?,?,?)",
            ("it1", "STONE", "stone", b""),
        )
        main.conn.execute(
            "INSERT INTO shop_stock(shop_id, item_key, sale_name, stock, updated_at) VALUES(?,?,?,?,?)",
            ("s1", "it1", "stone", 128, now),
        )
        main.conn.execute(
            "INSERT INTO shop_prices(shop_id, item_key, currency, price, qty) VALUES(?,?,?,?,?)",
            ("s1", "it1", "c", 1, 64),
        )
        main.conn.execute(
            "INSERT INTO accounts(uuid, currency, balance) VALUES(?,?,?)",
            ("buyer", "c", 10),
        )
        main.conn.execute(
            "INSERT INTO accounts(uuid, currency, balance) VALUES(?,?,?)",
            ("owner", "c", 0),
        )
    headers = {"X-LE-Token": main.SHARED_TOKEN}
    payload = {
        "player_uuid": "buyer",
        "shop_id": "s1",
        "item_key": "it1",
        "qty": 32,
        "currency": "c",
        "timestamp": int(time.time()),
        "client_tx_id": "tx1",
    }
    with TestClient(app) as client:
        resp = client.post("/api/shop/buy", json=payload, headers=headers)
        assert resp.status_code == 200
        assert resp.json()["status"] == "error"
        payload["qty"] = 64
        payload["client_tx_id"] = "tx2"
        resp2 = client.post("/api/shop/buy", json=payload, headers=headers)
        assert resp2.status_code == 200
        assert resp2.json()["status"] == "success"
