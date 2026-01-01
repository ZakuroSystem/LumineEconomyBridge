import base64
import hashlib
import json
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


def test_recommended_shops_includes_location_and_prices():
    with main.conn:
        main.conn.execute("DELETE FROM shop_prices")
        main.conn.execute("DELETE FROM shop_stock")
        main.conn.execute("DELETE FROM shop_items")
        main.conn.execute("DELETE FROM shop_locations")
        main.conn.execute("DELETE FROM shops")
        now = int(time.time())
        main.conn.execute(
            "INSERT INTO shops(shop_id, owner_uuid, status, created_at, last_activity_at) VALUES(?,?,?,?,?)",
            ("rec-shop", "owner-rec", "active", now, now),
        )
        main.conn.execute(
            "INSERT INTO shop_locations(shop_id, world, x, y, z) VALUES(?,?,?,?,?)",
            ("rec-shop", "overworld", 123.4, 65.0, -87.6),
        )
        item_key = "rec-item"
        main.conn.execute(
            "INSERT INTO shop_items(item_key, material, display_name, nbt_blob) VALUES(?,?,?,?)",
            (item_key, "DIAMOND", "Diamond", b"nbt"),
        )
        main.conn.execute(
            "INSERT INTO shop_stock(shop_id, item_key, sale_name, stock, updated_at) VALUES(?,?,?,?,?)",
            ("rec-shop", item_key, "Diamond", 10, now),
        )
        main.conn.execute(
            "INSERT INTO shop_prices(shop_id, item_key, currency, price, buy_price) VALUES(?,?,?,?,?)",
            ("rec-shop", item_key, "coin", 150, 75),
        )
    headers = {"X-LE-Token": main.SHARED_TOKEN}
    with TestClient(app) as client:
        resp = client.get("/api/shops/recommended", headers=headers, params={"limit": 3})
        assert resp.status_code == 200
        data = resp.json()
        shops = data.get("shops", [])
        assert shops
        entry = shops[0]
        assert entry["shop_id"] == "rec-shop"
        assert entry["trade_mode"] == "both"
        expected_location = {
            "world": "overworld",
            "x": 123.4,
            "y": 65.0,
            "z": -87.6,
        }
        assert entry["location"] == expected_location
        assert entry["locations"] == [expected_location]
        listings = entry["listings"]
        assert listings and listings[0]["name"] == "Diamond"
        sell_prices = listings[0]["sell"]
        buy_prices = listings[0]["buy"]
        assert sell_prices == [{"currency": "coin", "amount": 150}]
        assert buy_prices == [{"currency": "coin", "amount": 75}]


def test_recommended_shops_reports_multiple_locations():
    with main.conn:
        main.conn.execute("DELETE FROM shop_prices")
        main.conn.execute("DELETE FROM shop_stock")
        main.conn.execute("DELETE FROM shop_items")
        main.conn.execute("DELETE FROM shop_locations")
        main.conn.execute("DELETE FROM shops")
        now = int(time.time())
        main.conn.execute(
            "INSERT INTO shops(shop_id, owner_uuid, status, created_at, last_activity_at) VALUES(?,?,?,?,?)",
            ("multi-shop", "owner-multi", "active", now, now),
        )
        locations = [
            ("multi-shop", "overworld", 10.0, 64.0, -5.0),
            ("multi-shop", "nether", 20.0, 65.0, 30.0),
        ]
        for record in locations:
            main.conn.execute(
                "INSERT INTO shop_locations(shop_id, world, x, y, z) VALUES(?,?,?,?,?)",
                record,
            )
    headers = {"X-LE-Token": main.SHARED_TOKEN}
    with TestClient(app) as client:
        resp = client.get("/api/shops/recommended", headers=headers, params={"limit": 5})
        assert resp.status_code == 200
        data = resp.json()
        shops = data.get("shops", [])
        assert shops
        located = None
        for entry in shops:
            if entry.get("shop_id") == "multi-shop":
                located = entry
                break
        assert located is not None
        assert located["location"] == {
            "world": "overworld",
            "x": 10.0,
            "y": 64.0,
            "z": -5.0,
        }
        assert located["locations"] == [
            {
                "world": "overworld",
                "x": 10.0,
                "y": 64.0,
                "z": -5.0,
            },
            {
                "world": "nether",
                "x": 20.0,
                "y": 65.0,
                "z": 30.0,
            },
        ]


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
        assert data["listed"] is True
        assert "account_uuid" in data


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


def test_suspended_shop_without_owner_row_can_be_reclaimed():
    with main.conn:
        main.conn.execute("DELETE FROM shop_locations")
        main.conn.execute("DELETE FROM shop_owners")
        main.conn.execute("DELETE FROM shops")
        now = int(time.time())
        main.conn.execute(
            "INSERT INTO shops(shop_id, owner_uuid, status, created_at, last_activity_at) VALUES(?,?,?,?,?)",
            ("s4", "owner-s4", "suspended", now, now),
        )
    with TestClient(app) as client:
        resp = client.get("/api/shop/items", params={"shop_id": "s4"})
        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "suspended"
        assert data["owner_uuid"] == "owner-s4"
        assert data["owners"] == ["owner-s4"]
        assert data["listed"] is True
        assert "account_uuid" in data
    with main.conn:
        owners = main.conn.execute(
            "SELECT owner_uuid FROM shop_owners WHERE shop_id=?",
            ("s4",),
        ).fetchall()
        assert [row["owner_uuid"] for row in owners] == ["owner-s4"]
    payload = {
        "shop_id": "s4",
        "owner_uuid": "owner-s4",
        "placer_uuid": "owner-s4",
        "world": "world",
        "x": 1,
        "y": 64,
        "z": 2,
        "timestamp": int(time.time()),
    }
    headers = {"X-LE-Token": main.SHARED_TOKEN}
    with TestClient(app) as client:
        resp = client.post("/api/shop/place", json=payload, headers=headers)
        assert resp.status_code == 200
        assert resp.json()["status"] == "ok"
    with main.conn:
        loc = main.conn.execute(
            "SELECT world, x, y, z FROM shop_locations WHERE shop_id=?",
            ("s4",),
        ).fetchone()
        assert loc is not None
        assert loc["world"] == "world"
        assert loc["x"] == 1
        assert loc["y"] == 64
        assert loc["z"] == 2


def test_shop_add_owner_requires_token_and_valid_owner():
    with main.conn:
        main.conn.execute("DELETE FROM shop_owners")
        main.conn.execute("DELETE FROM shops")
        now = int(time.time())
        main.conn.execute(
            "INSERT INTO shops(shop_id, owner_uuid, status, created_at, last_activity_at) VALUES(?,?,?,?,?)",
            ("s5", "owner-s5", "active", now, now),
        )
        main.conn.execute(
            "INSERT INTO shop_owners(shop_id, owner_uuid) VALUES(?,?)",
            ("s5", "owner-s5"),
        )
    payload = {
        "owner_uuid": "owner-s5",
        "shop_id": "s5",
        "target_uuid": "partner-s5",
    }
    with TestClient(app) as client:
        resp = client.post("/api/shop/add_owner", json=payload)
        assert resp.status_code == 422
        resp = client.post(
            "/api/shop/add_owner",
            json=payload,
            headers={"X-LE-Token": "bad"},
        )
        assert resp.status_code == 401
        bad_payload = dict(payload)
        bad_payload["owner_uuid"] = "intruder"
        resp = client.post(
            "/api/shop/add_owner",
            json=bad_payload,
            headers={"X-LE-Token": main.SHARED_TOKEN},
        )
        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "error"
        assert data["reason"] == "not_owner"
        resp = client.post(
            "/api/shop/add_owner",
            json=payload,
            headers={"X-LE-Token": main.SHARED_TOKEN},
        )
        assert resp.status_code == 200
        assert resp.json()["status"] == "success"
    with main.conn:
        owners = main.conn.execute(
            "SELECT owner_uuid FROM shop_owners WHERE shop_id=? ORDER BY owner_uuid",
            ("s5",),
        ).fetchall()
        assert [row["owner_uuid"] for row in owners] == ["owner-s5", "partner-s5"]


def test_shop_take_stock_uses_first_available_item_key():
    with main.conn:
        main.conn.execute("DELETE FROM shop_stock")
        main.conn.execute("DELETE FROM shop_items")
        main.conn.execute("DELETE FROM shop_prices")
        main.conn.execute("DELETE FROM shop_owners")
        main.conn.execute("DELETE FROM shops")
        now = int(time.time())
        main.conn.execute(
            "INSERT INTO shops(shop_id, owner_uuid, status, created_at, last_activity_at) VALUES(?,?,?,?,?)",
            ("hopper-shop", "owner-hop", "active", now, now),
        )
        main.conn.execute(
            "INSERT INTO shop_owners(shop_id, owner_uuid) VALUES(?,?)",
            ("hopper-shop", "owner-hop"),
        )
        blob_one = b"hopper-one"
        blob_two = b"hopper-two"
        key_one = hashlib.sha256(blob_one).hexdigest()
        key_two = hashlib.sha256(blob_two).hexdigest()
        main.conn.execute(
            "INSERT INTO shop_items(item_key, material, display_name, nbt_blob) VALUES(?,?,?,?)",
            (key_one, "STONE", "One", blob_one),
        )
        main.conn.execute(
            "INSERT INTO shop_items(item_key, material, display_name, nbt_blob) VALUES(?,?,?,?)",
            (key_two, "STONE", "Two", blob_two),
        )
        main.conn.execute(
            "INSERT INTO shop_stock(shop_id, item_key, sale_name, stock, updated_at) VALUES(?,?,?,?,?)",
            ("hopper-shop", key_one, "first", 0, now),
        )
        main.conn.execute(
            "INSERT INTO shop_stock(shop_id, item_key, sale_name, stock, updated_at) VALUES(?,?,?,?,?)",
            ("hopper-shop", key_two, "second", 3, now),
        )
    headers = {"X-LE-Token": main.SHARED_TOKEN}
    payload = {
        "owner_uuid": "owner-hop",
        "shop_id": "hopper-shop",
        "item_keys": [key_one, key_two],
        "qty": 1,
    }
    with TestClient(app) as client:
        resp = client.post("/api/shop/take_stock", json=payload, headers=headers)
        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "success"
        assert data["grant"]
        assert data["grant"][0]["item_key"] == key_two
        resp = client.post(
            "/api/shop/take_stock",
            json={
                "owner_uuid": "owner-hop",
                "shop_id": "hopper-shop",
                "item_keys": [key_one],
                "qty": 1,
            },
            headers=headers,
        )
        assert resp.status_code == 200
        failure = resp.json()
        assert failure["status"] == "error"
        assert failure["reason"] == "insufficient_stock"
    with main.conn:
        remaining = main.conn.execute(
            "SELECT stock FROM shop_stock WHERE shop_id=? AND item_key=?",
            ("hopper-shop", key_two),
        ).fetchone()
        assert remaining and remaining["stock"] == 2


def test_shop_buy_can_deplete_stock_to_zero():
    buyer = "buyer-min-stock"
    owner = "owner-min-stock"
    item_blob = b"min-stock-item"
    item_key = hashlib.sha256(item_blob).hexdigest()
    with main.conn:
        for table in [
            "shop_tx",
            "shop_stock",
            "shop_prices",
            "shop_items",
            "shop_locations",
            "shop_owners",
            "shops",
            "accounts",
        ]:
            main.conn.execute(f"DELETE FROM {table}")
        now = int(time.time())
        main.conn.execute(
            "INSERT INTO shops(shop_id, owner_uuid, status, created_at, last_activity_at) VALUES(?,?,?,?,?)",
            ("min-stock-shop", owner, "active", now, now),
        )
        main.conn.execute(
            "INSERT INTO shop_owners(shop_id, owner_uuid) VALUES(?,?)",
            ("min-stock-shop", owner),
        )
        main.conn.execute(
            "INSERT INTO shop_locations(shop_id, world, x, y, z) VALUES(?,?,?,?,?)",
            ("min-stock-shop", "world", 10.0, 64.0, 10.0),
        )
        main.conn.execute(
            "INSERT INTO shop_items(item_key, material, display_name, nbt_blob) VALUES(?,?,?,?)",
            (item_key, "STONE", "Min Stock", item_blob),
        )
        main.conn.execute(
            "INSERT INTO shop_stock(shop_id, item_key, sale_name, stock, updated_at) VALUES(?,?,?,?,?)",
            ("min-stock-shop", item_key, "min", 2, now),
        )
        main.conn.execute(
            "INSERT INTO shop_prices(shop_id, item_key, currency, price) VALUES(?,?,?,?)",
            ("min-stock-shop", item_key, "thy", 50),
        )
        main.conn.execute(
            "INSERT INTO accounts(uuid, currency, balance, frozen) VALUES(?,?,?,0)",
            (buyer, "thy", 200),
        )
        main.conn.execute(
            "INSERT INTO accounts(uuid, currency, balance, frozen) VALUES(?,?,?,0)",
            (owner, "thy", 0),
        )
    base_payload = {
        "player_uuid": buyer,
        "shop_id": "min-stock-shop",
        "item_key": item_key,
        "qty": 1,
        "currency": "thy",
        "timestamp": int(time.time()),
        "client_tx_id": "min-stock-tx-1",
    }
    with TestClient(app) as client:
        resp = client.post("/api/shop/buy", json=base_payload)
        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "success"
    with main.conn:
        remaining = main.conn.execute(
            "SELECT stock FROM shop_stock WHERE shop_id=? AND item_key=?",
            ("min-stock-shop", item_key),
        ).fetchone()
        assert remaining and remaining["stock"] == 1
    base_payload["client_tx_id"] = "min-stock-tx-2"
    with TestClient(app) as client:
        resp = client.post("/api/shop/buy", json=base_payload)
        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "success"
    with main.conn:
        zero_stock = main.conn.execute(
            "SELECT stock FROM shop_stock WHERE shop_id=? AND item_key=?",
            ("min-stock-shop", item_key),
        ).fetchone()
        assert zero_stock and zero_stock["stock"] == 0
    base_payload["client_tx_id"] = "min-stock-tx-3"
    with TestClient(app) as client:
        resp = client.post("/api/shop/buy", json=base_payload)
        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "error"
        assert data["reason"] == "insufficient_stock"


def test_take_stock_keeps_listing_when_empty_by_default():
    owner = "owner-empty"
    item_blob = b"empty-item"
    item_key = hashlib.sha256(item_blob).hexdigest()
    with main.conn:
        for table in [
            "shop_tx",
            "shop_stock",
            "shop_prices",
            "shop_items",
            "shop_locations",
            "shop_owners",
            "shops",
        ]:
            main.conn.execute(f"DELETE FROM {table}")
        now = int(time.time())
        main.conn.execute(
            "INSERT INTO shops(shop_id, owner_uuid, status, created_at, last_activity_at) VALUES(?,?,?,?,?)",
            ("empty-shop", owner, "active", now, now),
        )
        main.conn.execute(
            "INSERT INTO shop_owners(shop_id, owner_uuid) VALUES(?,?)",
            ("empty-shop", owner),
        )
        main.conn.execute(
            "INSERT INTO shop_items(item_key, material, display_name, nbt_blob) VALUES(?,?,?,?)",
            (item_key, "STONE", "Empty", item_blob),
        )
        main.conn.execute(
            "INSERT INTO shop_stock(shop_id, item_key, sale_name, stock, updated_at) VALUES(?,?,?,?,?)",
            ("empty-shop", item_key, "empty", 1, now),
        )
        main.conn.execute(
            "INSERT INTO shop_prices(shop_id, item_key, currency, price) VALUES(?,?,?,?)",
            ("empty-shop", item_key, "thy", 75),
        )
    headers = {"X-LE-Token": main.SHARED_TOKEN}
    payload = {
        "owner_uuid": owner,
        "shop_id": "empty-shop",
        "item_key": item_key,
        "qty": 1,
    }
    with TestClient(app) as client:
        resp = client.post("/api/shop/take_stock", json=payload, headers=headers)
        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "success"
        assert data["grant"] and data["grant"][0]["item_key"] == item_key
    with main.conn:
        stock_row = main.conn.execute(
            "SELECT stock FROM shop_stock WHERE shop_id=? AND item_key=?",
            ("empty-shop", item_key),
        ).fetchone()
        assert stock_row and stock_row["stock"] == 0
        price_row = main.conn.execute(
            "SELECT price FROM shop_prices WHERE shop_id=? AND item_key=?",
            ("empty-shop", item_key),
        ).fetchone()
        assert price_row and price_row["price"] == 75


def test_take_stock_can_remove_listing_in_freemarket_mode():
    owner = "owner-flea"
    item_blob = b"flea-item"
    item_key = hashlib.sha256(item_blob).hexdigest()
    with main.conn:
        for table in [
            "shop_tx",
            "shop_stock",
            "shop_prices",
            "shop_items",
            "shop_locations",
            "shop_owners",
            "shops",
        ]:
            main.conn.execute(f"DELETE FROM {table}")
        now = int(time.time())
        main.conn.execute(
            "INSERT INTO shops(shop_id, owner_uuid, status, created_at, last_activity_at) VALUES(?,?,?,?,?)",
            ("flea-shop", owner, "active", now, now),
        )
        main.conn.execute(
            "INSERT INTO shop_owners(shop_id, owner_uuid) VALUES(?,?)",
            ("flea-shop", owner),
        )
        main.conn.execute(
            "INSERT INTO shop_items(item_key, material, display_name, nbt_blob) VALUES(?,?,?,?)",
            (item_key, "STONE", "Flea", item_blob),
        )
        main.conn.execute(
            "INSERT INTO shop_stock(shop_id, item_key, sale_name, stock, updated_at) VALUES(?,?,?,?,?)",
            ("flea-shop", item_key, "flea", 1, now),
        )
        main.conn.execute(
            "INSERT INTO shop_prices(shop_id, item_key, currency, price) VALUES(?,?,?,?)",
            ("flea-shop", item_key, "thy", 75),
        )
    headers = {"X-LE-Token": main.SHARED_TOKEN}
    payload = {
        "owner_uuid": owner,
        "shop_id": "flea-shop",
        "item_key": item_key,
        "qty": 1,
        "delete_if_empty": True,
    }
    with TestClient(app) as client:
        resp = client.post("/api/shop/take_stock", json=payload, headers=headers)
        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "success"
    with main.conn:
        stock_row = main.conn.execute(
            "SELECT 1 FROM shop_stock WHERE shop_id=? AND item_key=?",
            ("flea-shop", item_key),
        ).fetchone()
        assert stock_row is None
        price_row = main.conn.execute(
            "SELECT 1 FROM shop_prices WHERE shop_id=? AND item_key=?",
            ("flea-shop", item_key),
        ).fetchone()
        assert price_row is None


def test_shop_add_stock_rejects_non_local_client():
    with main.conn:
        main.conn.execute("DELETE FROM shop_stock")
        main.conn.execute("DELETE FROM shop_prices")
        main.conn.execute("DELETE FROM shop_items")
        main.conn.execute("DELETE FROM shop_owners")
        main.conn.execute("DELETE FROM shops")
        now = int(time.time())
        main.conn.execute(
            "INSERT INTO shops(shop_id, owner_uuid, status, created_at, last_activity_at) VALUES(?,?,?,?,?)",
            ("remote-shop", "owner-remote", "active", now, now),
        )
        main.conn.execute(
            "INSERT INTO shop_owners(shop_id, owner_uuid) VALUES(?,?)",
            ("remote-shop", "owner-remote"),
        )
    payload = {
        "owner_uuid": "owner-remote",
        "shop_id": "remote-shop",
        "nbt_blob": base64.b64encode(b"demo-item").decode("ascii"),
        "material": "STONE",
        "display_name": "Demo",
        "qty": 1,
        "price": 250,
        "sale_name": "demo",
        "currency": "thy",
    }
    with TestClient(app) as client:
        original_hosts = main.ALLOWED_PLUGIN_HOSTS.copy()
        try:
            main.ALLOWED_PLUGIN_HOSTS = {"127.0.0.1", "::1", "localhost"}
            resp = client.post(
                "/api/shop/add_stock",
                json=payload,
                headers={"X-LE-Token": main.SHARED_TOKEN},
            )
            assert resp.status_code == 403
        finally:
            main.ALLOWED_PLUGIN_HOSTS = original_hosts


def test_shop_remove_requires_owner_and_token():
    with main.conn:
        main.conn.execute("DELETE FROM shop_stock")
        main.conn.execute("DELETE FROM shop_locations")
        main.conn.execute("DELETE FROM shop_owners")
        main.conn.execute("DELETE FROM shops")
        now = int(time.time())
        main.conn.execute(
            "INSERT INTO shops(shop_id, owner_uuid, status, created_at, last_activity_at) VALUES(?,?,?,?,?)",
            ("s6", "owner-s6", "active", now, now),
        )
        main.conn.execute(
            "INSERT INTO shop_owners(shop_id, owner_uuid) VALUES(?,?)",
            ("s6", "owner-s6"),
        )
        main.conn.execute(
            "INSERT INTO shop_locations(shop_id, world, x, y, z) VALUES(?,?,?,?,?)",
            ("s6", "world", 3.0, 64.0, 5.0),
        )
    with TestClient(app) as client:
        resp = client.post("/api/shop/remove", json={"shop_id": "s6"})
        assert resp.status_code == 422
        resp = client.post(
            "/api/shop/remove",
            json={"owner_uuid": "owner-s6", "shop_id": "s6"},
            headers={"X-LE-Token": "bad"},
        )
        assert resp.status_code == 401
        resp = client.post(
            "/api/shop/remove",
            json={"owner_uuid": "intruder", "shop_id": "s6"},
            headers={"X-LE-Token": main.SHARED_TOKEN},
        )
        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "error"
        assert data["reason"] == "not_owner"
        resp = client.post(
            "/api/shop/remove",
            json={"owner_uuid": "owner-s6", "shop_id": "s6"},
            headers={"X-LE-Token": main.SHARED_TOKEN},
        )
        assert resp.status_code == 200
        assert resp.json()["status"] == "success"
    with main.conn:
        row = main.conn.execute(
            "SELECT status FROM shops WHERE shop_id=?",
            ("s6",),
        ).fetchone()
        assert row and row["status"] == "suspended"
        owners = main.conn.execute(
            "SELECT owner_uuid FROM shop_owners WHERE shop_id=?",
            ("s6",),
        ).fetchall()
        assert owners == []
        loc = main.conn.execute(
            "SELECT 1 FROM shop_locations WHERE shop_id=?",
            ("s6",),
        ).fetchone()
        assert loc is None


def test_shop_buy_awards_quest_once():
    buyer = "buyer-quest"
    owner = "owner-quest"
    item_blob = b"quest-item"
    item_key = hashlib.sha256(item_blob).hexdigest()
    with main.conn:
        for table in [
            "player_quests",
            "pending_messages",
            "shop_tx",
            "shop_stock",
            "shop_prices",
            "shop_items",
            "shop_locations",
            "shop_owners",
            "shops",
            "accounts",
        ]:
            main.conn.execute(f"DELETE FROM {table}")
        now = int(time.time())
        main.conn.execute(
            "INSERT INTO shops(shop_id, owner_uuid, status, created_at, last_activity_at) VALUES(?,?,?,?,?)",
            ("quest-shop", owner, "active", now, now),
        )
        main.conn.execute(
            "INSERT INTO shop_owners(shop_id, owner_uuid) VALUES(?,?)",
            ("quest-shop", owner),
        )
        main.conn.execute(
            "INSERT INTO shop_locations(shop_id, world, x, y, z) VALUES(?,?,?,?,?)",
            ("quest-shop", "world", 0.0, 64.0, 0.0),
        )
        main.conn.execute(
            "INSERT INTO shop_items(item_key, material, display_name, nbt_blob) VALUES(?,?,?,?)",
            (item_key, "STONE", "Quest Stone", item_blob),
        )
        main.conn.execute(
            "INSERT INTO shop_stock(shop_id, item_key, sale_name, stock, updated_at) VALUES(?,?,?,?,?)",
            ("quest-shop", item_key, "stone", 5, now),
        )
        main.conn.execute(
            "INSERT INTO shop_prices(shop_id, item_key, currency, price) VALUES(?,?,?,?)",
            ("quest-shop", item_key, "thy", 200),
        )
        main.conn.execute(
            "INSERT INTO accounts(uuid, currency, balance, frozen) VALUES(?,?,?,0)",
            (buyer, "thy", 500),
        )
        main.conn.execute(
            "INSERT INTO accounts(uuid, currency, balance, frozen) VALUES(?,?,?,0)",
            (owner, "thy", 0),
        )
    payload = {
        "player_uuid": buyer,
        "shop_id": "quest-shop",
        "item_key": item_key,
        "qty": 1,
        "currency": "thy",
        "timestamp": int(time.time()),
        "client_tx_id": "quest-buy-tx",
    }
    with TestClient(app) as client:
        resp = client.post("/api/shop/buy", json=payload)
        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "success"
        texts = [m.get("text", "") for m in data.get("messages", []) if m.get("player") == buyer]
        assert any("Quest complete" in text for text in texts)
        assert data["scoreboards"][buyer]["thy"] == 10300
    with main.conn:
        row = main.conn.execute(
            "SELECT 1 FROM player_quests WHERE player_uuid=? AND quest_id=?",
            (buyer, "shop_buy"),
        ).fetchone()
        assert row
        bal = main.conn.execute(
            "SELECT balance FROM accounts WHERE uuid=? AND currency=?",
            (buyer, "thy"),
        ).fetchone()
        assert bal and bal["balance"] == 10300
    payload["client_tx_id"] = "quest-buy-tx-2"
    with TestClient(app) as client:
        resp = client.post("/api/shop/buy", json=payload)
        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "success"
    with main.conn:
        count = main.conn.execute(
            "SELECT COUNT(*) AS c FROM player_quests WHERE player_uuid=? AND quest_id=?",
            (buyer, "shop_buy"),
        ).fetchone()
        assert count["c"] == 1
        bal = main.conn.execute(
            "SELECT balance FROM accounts WHERE uuid=? AND currency=?",
            (buyer, "thy"),
        ).fetchone()
        assert bal and bal["balance"] == 10100


def test_shop_place_queues_creation_quest_message():
    owner = "owner-create"
    with main.conn:
        for table in [
            "player_quests",
            "pending_messages",
            "shop_locations",
            "shop_owners",
            "shops",
        ]:
            main.conn.execute(f"DELETE FROM {table}")
    payload = {
        "shop_id": "create-quest-shop",
        "owner_uuid": owner,
        "placer_uuid": owner,
        "world": "world",
        "x": 10,
        "y": 65,
        "z": 10,
        "timestamp": int(time.time()),
    }
    headers = {"X-LE-Token": main.SHARED_TOKEN}
    with TestClient(app) as client:
        resp = client.post("/api/shop/place", json=payload, headers=headers)
        assert resp.status_code == 200
        assert resp.json()["status"] == "ok"
    with main.conn:
        row = main.conn.execute(
            "SELECT 1 FROM player_quests WHERE player_uuid=? AND quest_id=?",
            (owner, "shop_create"),
        ).fetchone()
        assert row
        queued = main.conn.execute(
            "SELECT payload FROM pending_messages WHERE uuid=?",
            (owner,),
        ).fetchall()
        assert queued
        payloads = [json.loads(entry["payload"]) for entry in queued]
        assert any("クエスト" in msg.get("text", "") or "Quest" in msg.get("text", "") for msg in payloads)


def test_shop_sell_awards_quest_and_points():
    seller = "seller-quest"
    owner = "owner-quest-sell"
    item_blob = b"quest-item-sell"
    item_key = hashlib.sha256(item_blob).hexdigest()
    with main.conn:
        for table in [
            "player_quests",
            "pending_messages",
            "shop_tx",
            "shop_stock",
            "shop_prices",
            "shop_items",
            "shop_locations",
            "shop_owners",
            "shops",
            "accounts",
        ]:
            main.conn.execute(f"DELETE FROM {table}")
        now = int(time.time())
        main.conn.execute(
            "INSERT INTO shops(shop_id, owner_uuid, status, created_at, last_activity_at) VALUES(?,?,?,?,?)",
            ("sell-quest-shop", owner, "active", now, now),
        )
        main.conn.execute(
            "INSERT INTO shop_owners(shop_id, owner_uuid) VALUES(?,?)",
            ("sell-quest-shop", owner),
        )
        main.conn.execute(
            "INSERT INTO shop_locations(shop_id, world, x, y, z) VALUES(?,?,?,?,?)",
            ("sell-quest-shop", "world", 0.0, 64.0, 0.0),
        )
        main.conn.execute(
            "INSERT INTO shop_items(item_key, material, display_name, nbt_blob) VALUES(?,?,?,?)",
            (item_key, "COBBLESTONE", "Quest Cobble", item_blob),
        )
        main.conn.execute(
            "INSERT INTO shop_prices(shop_id, item_key, currency, price) VALUES(?,?,?,?)",
            ("sell-quest-shop", item_key, "thy", 150),
        )
        main.conn.execute(
            "INSERT INTO accounts(uuid, currency, balance, frozen) VALUES(?,?,?,0)",
            (owner, "thy", 1000),
        )
        main.conn.execute(
            "INSERT INTO accounts(uuid, currency, balance, frozen) VALUES(?,?,?,0)",
            (seller, "thy", 0),
        )
    payload = {
        "player_uuid": seller,
        "shop_id": "sell-quest-shop",
        "item_key": item_key,
        "qty": 1,
        "currency": "thy",
        "timestamp": int(time.time()),
        "client_tx_id": "quest-sell-tx",
    }
    with TestClient(app) as client:
        resp = client.post("/api/shop/sell", json=payload)
        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "success"
        texts = [m.get("text", "") for m in data.get("messages", []) if m.get("player") == seller]
        assert any("Quest" in text for text in texts)
        assert data["scoreboards"][seller]["thy"] == 10150
    with main.conn:
        row = main.conn.execute(
            "SELECT 1 FROM player_quests WHERE player_uuid=? AND quest_id=?",
            (seller, "shop_sell"),
        ).fetchone()
        assert row
        bal = main.conn.execute(
            "SELECT balance FROM accounts WHERE uuid=? AND currency=?",
            (seller, "thy"),
        ).fetchone()
        assert bal and bal["balance"] == 10150


def test_recommended_quests_filters_completed():
    player = "player-recommended"
    now = int(time.time())
    with main.conn:
        main.conn.execute(
            "DELETE FROM player_quests WHERE player_uuid=?",
            (player,),
        )
        main.conn.execute(
            "INSERT INTO player_quests(player_uuid, quest_id, completed_at) VALUES(?,?,?)",
            (player, "shop_buy", now),
        )
    headers = {"X-LE-Token": main.SHARED_TOKEN}
    with TestClient(app) as client:
        resp = client.get(
            "/api/quests/recommended",
            params={"player_uuid": player},
            headers=headers,
        )
        assert resp.status_code == 200
        data = resp.json()
        assert "quests" in data
        assert "shop_buy" not in data["quests"]
        assert "shop_create" in data["quests"]
    with main.conn:
        main.conn.execute(
            "DELETE FROM player_quests WHERE player_uuid=?",
            (player,),
        )
        for quest_id in main.QUEST_DEFINITIONS.keys():
            main.conn.execute(
                "INSERT INTO player_quests(player_uuid, quest_id, completed_at) VALUES(?,?,?)",
                (player, quest_id, now),
            )
    with TestClient(app) as client:
        resp = client.get(
            "/api/quests/recommended",
            params={"player_uuid": player},
            headers=headers,
        )
        assert resp.status_code == 200
        data = resp.json()
        assert data["quests"] == []


def test_shop_account_requires_delegate_or_admin():
    with main.conn:
        main.conn.execute("DELETE FROM shop_locations")
        main.conn.execute("DELETE FROM shop_owners")
        main.conn.execute("DELETE FROM shops")
        main.conn.execute("DELETE FROM system_accounts")
        main.conn.execute("DELETE FROM account_links")
        main.conn.execute("DELETE FROM admin_users WHERE name IN ('owneradmin')")
        main.conn.execute("DELETE FROM name_index WHERE name IN ('corpco', 'corpadmin', 'owneradmin')")
        now = int(time.time())
        main.conn.execute(
            "INSERT INTO shops(shop_id, owner_uuid, status, created_at, last_activity_at) VALUES(?,?,?,?,?)",
            ("acct-shop", "owner-uuid", "active", now, now),
        )
        main.conn.execute(
            "INSERT INTO shop_owners(shop_id, owner_uuid) VALUES(?,?)",
            ("acct-shop", "owner-uuid"),
        )
        main.conn.execute("INSERT INTO system_accounts(uuid) VALUES(?)", ("corpco",))
        main.conn.execute(
            "INSERT OR REPLACE INTO name_index(name, uuid) VALUES(?,?)",
            ("corpco", "corpco"),
        )
    payload = {"owner_uuid": "owner-uuid", "shop_id": "acct-shop", "account_id": "corpco"}
    headers = {"X-LE-Token": main.SHARED_TOKEN}
    with TestClient(app) as client:
        resp = client.post("/api/shop/account", json=payload, headers=headers)
        assert resp.status_code == 200
        body = resp.json()
        assert body["status"] == "error"
        assert body["reason"] == "no_access"
    with main.conn:
        main.conn.execute(
            "INSERT OR REPLACE INTO account_links(user_uuid, system_uuid) VALUES(?,?)",
            ("owner-uuid", "corpco"),
        )
    with TestClient(app) as client:
        resp = client.post("/api/shop/account", json=payload, headers=headers)
        assert resp.status_code == 200
        body = resp.json()
        assert body["status"] == "success"
    with main.conn:
        row = main.conn.execute(
            "SELECT account_uuid FROM shops WHERE shop_id=?",
            ("acct-shop",),
        ).fetchone()
        assert row and row["account_uuid"] == "corpco"

    with main.conn:
        main.conn.execute("DELETE FROM shop_locations")
        main.conn.execute("DELETE FROM shop_owners")
        main.conn.execute("DELETE FROM shops")
        main.conn.execute("DELETE FROM account_links WHERE user_uuid='admin-uuid'")
        now = int(time.time())
        main.conn.execute(
            "INSERT INTO shops(shop_id, owner_uuid, status, created_at, last_activity_at) VALUES(?,?,?,?,?)",
            ("admin-shop", "admin-uuid", "active", now, now),
        )
        main.conn.execute(
            "INSERT INTO shop_owners(shop_id, owner_uuid) VALUES(?,?)",
            ("admin-shop", "admin-uuid"),
        )
        main.conn.execute("INSERT OR IGNORE INTO system_accounts(uuid) VALUES(?)", ("corpadmin",))
        main.conn.execute(
            "INSERT OR REPLACE INTO name_index(name, uuid) VALUES(?,?)",
            ("corpadmin", "corpadmin"),
        )
        main.conn.execute(
            "INSERT OR REPLACE INTO name_index(name, uuid) VALUES(?,?)",
            ("owneradmin", "admin-uuid"),
        )
        main.conn.execute("INSERT OR IGNORE INTO admin_users(name) VALUES(?)", ("owneradmin",))
    admin_payload = {"owner_uuid": "admin-uuid", "shop_id": "admin-shop", "account_id": "corpadmin"}
    with TestClient(app) as client:
        resp = client.post("/api/shop/account", json=admin_payload, headers=headers)
        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "success"
    with main.conn:
        row = main.conn.execute(
            "SELECT account_uuid FROM shops WHERE shop_id=?",
            ("admin-shop",),
        ).fetchone()
        assert row and row["account_uuid"] == "corpadmin"


def test_decimal_rescale_updates_shop_values():
    with main.conn:
        main.conn.execute("DELETE FROM transactions")
        main.conn.execute("DELETE FROM accounts")
        main.conn.execute("DELETE FROM shop_tx")
        main.conn.execute("DELETE FROM shop_autoprice")
        main.conn.execute("DELETE FROM shop_prices")
        main.conn.execute(
            "INSERT OR REPLACE INTO settings(key,value) VALUES(?, ?)",
            (main.DECIMAL_PLACES_KEY, "3"),
        )
        main.conn.execute(
            "INSERT INTO accounts(uuid,currency,balance) VALUES(?,?,?)",
            ("player", "coin", 123450),
        )
        main.conn.execute(
            "INSERT INTO shop_prices(shop_id,item_key,currency,price,buy_price) VALUES(?,?,?,?,?)",
            ("shop", "item", "coin", 100000, 50000),
        )
        main.conn.execute(
            "INSERT INTO shop_prices(shop_id,item_key,currency,price,buy_price) VALUES(?,?,?,?,?)",
            ("shop", "cheap", "coin", 1, 1),
        )
        main.conn.execute(
            """
            INSERT INTO shop_autoprice(shop_id,item_key,currency,lower_threshold,upper_threshold,high_price,low_price)
            VALUES(?,?,?,?,?,?,?)
            """,
            ("shop", "item", "coin", 5, 10, 20000, 10000),
        )
    try:
        main._update_decimal_places(2)
        with main.conn:
            balance = main.conn.execute(
                "SELECT balance FROM accounts WHERE uuid=? AND currency=?",
                ("player", "coin"),
            ).fetchone()["balance"]
            assert balance == 12345
            price_row = main.conn.execute(
                "SELECT price,buy_price FROM shop_prices WHERE shop_id=? AND item_key=?",
                ("shop", "item"),
            ).fetchone()
            assert price_row["price"] == 10000
            assert price_row["buy_price"] == 5000
            cheap_row = main.conn.execute(
                "SELECT price FROM shop_prices WHERE shop_id=? AND item_key=?",
                ("shop", "cheap"),
            ).fetchone()
            assert cheap_row["price"] == 1
            auto_row = main.conn.execute(
                "SELECT high_price,low_price FROM shop_autoprice WHERE shop_id=? AND item_key=?",
                ("shop", "item"),
            ).fetchone()
            assert auto_row["high_price"] == 2000
            assert auto_row["low_price"] == 1000
    finally:
        main._update_decimal_places(main.DEFAULT_DECIMAL_PLACES)
        with main.conn:
            main.conn.execute("DELETE FROM shop_autoprice")
            main.conn.execute("DELETE FROM shop_prices")
            main.conn.execute("DELETE FROM shop_tx")
            main.conn.execute("DELETE FROM accounts")
            main.conn.execute("DELETE FROM transactions")
            main.conn.execute(
                "INSERT OR REPLACE INTO settings(key,value) VALUES(?, ?)",
                (main.DECIMAL_PLACES_KEY, str(main.DEFAULT_DECIMAL_PLACES)),
            )


def test_shop_autoprice_updates_price_and_buy_price():
    with main.conn:
        for table in [
            "shop_autoprice",
            "shop_prices",
            "shop_stock",
            "shop_items",
            "shop_locations",
            "shop_owners",
            "shops",
            "currencies",
        ]:
            main.conn.execute(f"DELETE FROM {table}")
        main.conn.execute(
            "INSERT OR IGNORE INTO currencies(name, symbol) VALUES(?, ?)",
            ("coin", "c"),
        )
        main.conn.execute(
            "INSERT OR REPLACE INTO settings(key, value) VALUES('default_currency', ?)",
            ("coin",),
        )
        now = int(time.time())
        main.conn.execute(
            "INSERT INTO shops(shop_id, owner_uuid, status, created_at, last_activity_at) VALUES(?,?,?,?,?)",
            ("auto-shop", "owner-auto", "active", now, now),
        )
        main.conn.execute(
            "INSERT INTO shop_owners(shop_id, owner_uuid) VALUES(?,?)",
            ("auto-shop", "owner-auto"),
        )
        main.conn.execute(
            "INSERT INTO shop_locations(shop_id, world, x, y, z) VALUES(?,?,?,?,?)",
            ("auto-shop", "overworld", 0.0, 64.0, 0.0),
        )
        main.conn.execute(
            "INSERT INTO shop_items(item_key, material, display_name, nbt_blob) VALUES(?,?,?,?)",
            ("auto-item", "DIAMOND", "Diamond", b"blob"),
        )
        main.conn.execute(
            "INSERT INTO shop_stock(shop_id, item_key, sale_name, stock, updated_at) VALUES(?,?,?,?,?)",
            ("auto-shop", "auto-item", "diamond", 250, now),
        )
        main.conn.execute(
            "INSERT INTO shop_prices(shop_id, item_key, currency, price, buy_price) VALUES(?,?,?,?,?)",
            ("auto-shop", "auto-item", "coin", 20, 20),
        )
    payload = {
        "owner_uuid": "owner-auto",
        "shop_id": "auto-shop",
        "sale_name": "diamond",
        "lower_threshold": 5,
        "high_price": 50,
        "upper_threshold": 505,
        "low_price": 10,
    }
    headers = {"X-LE-Token": main.SHARED_TOKEN}
    with TestClient(app) as client:
        resp = client.post("/api/shop/autoprice", headers=headers, json=payload)
        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "success"
        assert data["price"] == 30
        assert data["buy_price"] == 30
        assert data["currency"] == "coin"
    with main.conn:
        row = main.conn.execute(
            "SELECT price, buy_price FROM shop_prices WHERE shop_id=? AND item_key=? AND currency=?",
            ("auto-shop", "auto-item", "coin"),
        ).fetchone()
        assert row["price"] == 30
        assert row["buy_price"] == 30


def test_shop_buy_uses_autoprice_totals():
    with main.conn:
        for table in [
            "shop_tx",
            "shop_autoprice",
            "shop_prices",
            "shop_stock",
            "shop_items",
            "shop_locations",
            "shop_owners",
            "shops",
            "accounts",
            "currencies",
        ]:
            main.conn.execute(f"DELETE FROM {table}")
        main.conn.execute(
            "INSERT OR IGNORE INTO currencies(name, symbol) VALUES(?, ?)",
            ("coin", "c"),
        )
        main.conn.execute(
            "INSERT OR REPLACE INTO settings(key, value) VALUES('default_currency', ?)",
            ("coin",),
        )
        now = int(time.time())
        main.conn.execute(
            "INSERT INTO shops(shop_id, owner_uuid, status, created_at, last_activity_at) VALUES(?,?,?,?,?)",
            ("auto-buy-shop", "owner-buy", "active", now, now),
        )
        main.conn.execute(
            "INSERT INTO shop_owners(shop_id, owner_uuid) VALUES(?,?)",
            ("auto-buy-shop", "owner-buy"),
        )
        main.conn.execute(
            "INSERT INTO shop_locations(shop_id, world, x, y, z) VALUES(?,?,?,?,?)",
            ("auto-buy-shop", "overworld", 0.0, 64.0, 0.0),
        )
        main.conn.execute(
            "INSERT INTO shop_items(item_key, material, display_name, nbt_blob) VALUES(?,?,?,?)",
            ("auto-buy-item", "DIAMOND", "Diamond", b"blob"),
        )
        main.conn.execute(
            "INSERT INTO shop_stock(shop_id, item_key, sale_name, stock, updated_at) VALUES(?,?,?,?,?)",
            ("auto-buy-shop", "auto-buy-item", "diamond", 505, now),
        )
        main.conn.execute(
            "INSERT INTO shop_prices(shop_id, item_key, currency, price, buy_price) VALUES(?,?,?,?,?)",
            ("auto-buy-shop", "auto-buy-item", "coin", 20, 20),
        )
        main.conn.execute(
            "INSERT INTO accounts(uuid, currency, balance, frozen) VALUES(?,?,?,0)",
            ("buyer-uuid", "coin", 1000),
        )
        main.conn.execute(
            "INSERT INTO accounts(uuid, currency, balance, frozen) VALUES(?,?,?,0)",
            ("owner-buy", "coin", 0),
        )
    payload = {
        "owner_uuid": "owner-buy",
        "shop_id": "auto-buy-shop",
        "sale_name": "diamond",
        "lower_threshold": 5,
        "high_price": 50,
        "upper_threshold": 505,
        "low_price": 10,
    }
    headers = {"X-LE-Token": main.SHARED_TOKEN}
    with TestClient(app) as client:
        resp = client.post("/api/shop/autoprice", headers=headers, json=payload)
        assert resp.status_code == 200
        buy_payload = {
            "player_uuid": "buyer-uuid",
            "shop_id": "auto-buy-shop",
            "item_key": "auto-buy-item",
            "qty": 10,
            "currency": "coin",
            "timestamp": int(time.time()),
            "client_tx_id": "auto-buy-tx",
        }
        buy_resp = client.post("/api/shop/buy", headers=headers, json=buy_payload)
        assert buy_resp.status_code == 200
        buy_data = buy_resp.json()
        assert buy_data["status"] == "success"
    with main.conn:
        tx = main.conn.execute(
            "SELECT total_price FROM shop_tx WHERE client_tx_id=?",
            ("auto-buy-tx",),
        ).fetchone()
        assert tx is not None
        assert tx["total_price"] == 103
        price_row = main.conn.execute(
            "SELECT price FROM shop_prices WHERE shop_id=? AND item_key=? AND currency=?",
            ("auto-buy-shop", "auto-buy-item", "coin"),
        ).fetchone()
        assert price_row["price"] == 11


def test_shop_sell_uses_autoprice_totals():
    with main.conn:
        for table in [
            "shop_tx",
            "shop_autoprice",
            "shop_prices",
            "shop_stock",
            "shop_items",
            "shop_locations",
            "shop_owners",
            "shops",
            "accounts",
            "currencies",
        ]:
            main.conn.execute(f"DELETE FROM {table}")
        main.conn.execute(
            "INSERT OR IGNORE INTO currencies(name, symbol) VALUES(?, ?)",
            ("coin", "c"),
        )
        main.conn.execute(
            "INSERT OR REPLACE INTO settings(key, value) VALUES('default_currency', ?)",
            ("coin",),
        )
        now = int(time.time())
        main.conn.execute(
            "INSERT INTO shops(shop_id, owner_uuid, status, created_at, last_activity_at) VALUES(?,?,?,?,?)",
            ("auto-sell-shop", "owner-sell", "active", now, now),
        )
        main.conn.execute(
            "INSERT INTO shop_owners(shop_id, owner_uuid) VALUES(?,?)",
            ("auto-sell-shop", "owner-sell"),
        )
        main.conn.execute(
            "INSERT INTO shop_locations(shop_id, world, x, y, z) VALUES(?,?,?,?,?)",
            ("auto-sell-shop", "overworld", 0.0, 64.0, 0.0),
        )
        main.conn.execute(
            "INSERT INTO shop_items(item_key, material, display_name, nbt_blob) VALUES(?,?,?,?)",
            ("auto-sell-item", "DIAMOND", "Diamond", b"blob"),
        )
        main.conn.execute(
            "INSERT INTO shop_stock(shop_id, item_key, sale_name, stock, updated_at) VALUES(?,?,?,?,?)",
            ("auto-sell-shop", "auto-sell-item", "diamond", 250, now),
        )
        main.conn.execute(
            "INSERT INTO shop_prices(shop_id, item_key, currency, price, buy_price) VALUES(?,?,?,?,?)",
            ("auto-sell-shop", "auto-sell-item", "coin", 20, 20),
        )
        main.conn.execute(
            "INSERT INTO accounts(uuid, currency, balance, frozen) VALUES(?,?,?,0)",
            ("owner-sell", "coin", 1000),
        )
        main.conn.execute(
            "INSERT INTO accounts(uuid, currency, balance, frozen) VALUES(?,?,?,0)",
            ("seller-uuid", "coin", 0),
        )
    payload = {
        "owner_uuid": "owner-sell",
        "shop_id": "auto-sell-shop",
        "sale_name": "diamond",
        "lower_threshold": 5,
        "high_price": 50,
        "upper_threshold": 505,
        "low_price": 10,
    }
    headers = {"X-LE-Token": main.SHARED_TOKEN}
    with TestClient(app) as client:
        resp = client.post("/api/shop/autoprice", headers=headers, json=payload)
        assert resp.status_code == 200
        sell_payload = {
            "player_uuid": "seller-uuid",
            "shop_id": "auto-sell-shop",
            "item_key": "auto-sell-item",
            "qty": 5,
            "currency": "coin",
            "timestamp": int(time.time()),
            "client_tx_id": "auto-sell-tx",
        }
        sell_resp = client.post("/api/shop/sell", headers=headers, json=sell_payload)
        assert sell_resp.status_code == 200
        sell_data = sell_resp.json()
        assert sell_data["status"] == "success"
    with main.conn:
        tx = main.conn.execute(
            "SELECT total_price FROM shop_tx WHERE client_tx_id=?",
            ("auto-sell-tx",),
        ).fetchone()
        assert tx is not None
        assert tx["total_price"] == 150
        price_row = main.conn.execute(
            "SELECT price FROM shop_prices WHERE shop_id=? AND item_key=? AND currency=?",
            ("auto-sell-shop", "auto-sell-item", "coin"),
        ).fetchone()
        assert price_row["price"] == 30


def test_account_ensure_creates_default_balance():
    player_uuid = "ensure-player-uuid"
    with main.conn:
        main.conn.execute("DELETE FROM accounts WHERE uuid=?", (player_uuid,))
    headers = {"X-LE-Token": main.SHARED_TOKEN}
    with TestClient(app) as client:
        resp = client.post(
            "/api/account/ensure",
            headers=headers,
            json={"player_uuid": player_uuid},
        )
        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "ok"
        assert data["created"]
        second = client.post(
            "/api/account/ensure",
            headers=headers,
            json={"player_uuid": player_uuid},
        )
        assert second.status_code == 200
        assert second.json()["created"] == []
    with main.conn:
        rows = main.conn.execute(
            "SELECT currency, balance FROM accounts WHERE uuid=?",
            (player_uuid,),
        ).fetchall()
        assert rows
        for row in rows:
            assert row["balance"] == 0

def test_shop_sort_modes_order_items():
    with main.conn:
        main.conn.execute("DELETE FROM shop_prices")
        main.conn.execute("DELETE FROM shop_stock")
        main.conn.execute("DELETE FROM shop_items")
        main.conn.execute("DELETE FROM shop_owners")
        main.conn.execute("DELETE FROM shops")
        now = int(time.time())
        main.conn.execute(
            "INSERT INTO shops(shop_id, owner_uuid, status, created_at, last_activity_at) VALUES(?,?,?,?,?)",
            ("sort-shop", "owner-sort", "active", now, now),
        )
        main.conn.execute(
            "INSERT INTO shop_owners(shop_id, owner_uuid) VALUES(?,?)",
            ("sort-shop", "owner-sort"),
        )
        items = [
            ("k1", "DIAMOND", "Banana", b"1"),
            ("k2", "DIAMOND", "Apple", b"2"),
            ("k3", "DIAMOND", "Carrot", b"3"),
        ]
        for key, material, name, blob in items:
            main.conn.execute(
                "INSERT INTO shop_items(item_key, material, display_name, nbt_blob) VALUES(?,?,?,?)",
                (key, material, name, blob),
            )
        stocks = [
            ("sort-shop", "k1", "Banana", 5, now),
            ("sort-shop", "k2", "Apple", 10, now),
            ("sort-shop", "k3", "Carrot", 2, now),
        ]
        for stock in stocks:
            main.conn.execute(
                "INSERT INTO shop_stock(shop_id, item_key, sale_name, stock, updated_at) VALUES(?,?,?,?,?)",
                stock,
            )
        prices = [
            ("sort-shop", "k1", "coin", 300, 300),
            ("sort-shop", "k2", "coin", 100, 100),
            ("sort-shop", "k3", "coin", 200, 200),
        ]
        for price in prices:
            main.conn.execute(
                "INSERT INTO shop_prices(shop_id, item_key, currency, price, buy_price) VALUES(?,?,?,?,?)",
                price,
            )
    headers = {"X-LE-Token": main.SHARED_TOKEN}
    with TestClient(app) as client:
        resp = client.get("/api/shop/items", params={"shop_id": "sort-shop"})
        assert resp.status_code == 200
        data = resp.json()
        names = [item["sale_name"] for item in data.get("items", [])]
        assert names == ["Banana", "Apple", "Carrot"]

        resp = client.post(
            "/api/shop/sort",
            headers=headers,
            json={"owner_uuid": "owner-sort", "shop_id": "sort-shop", "sort_mode": "name"},
        )
        assert resp.status_code == 200
        resp = client.get("/api/shop/items", params={"shop_id": "sort-shop"})
        names = [item["sale_name"] for item in resp.json().get("items", [])]
        assert names == ["Apple", "Banana", "Carrot"]

        client.post(
            "/api/shop/sort",
            headers=headers,
            json={"owner_uuid": "owner-sort", "shop_id": "sort-shop", "sort_mode": "price"},
        )
        resp = client.get("/api/shop/items", params={"shop_id": "sort-shop"})
        names = [item["sale_name"] for item in resp.json().get("items", [])]
        assert names == ["Apple", "Carrot", "Banana"]

        client.post(
            "/api/shop/sort",
            headers=headers,
            json={"owner_uuid": "owner-sort", "shop_id": "sort-shop", "sort_mode": "stock"},
        )
        resp = client.get("/api/shop/items", params={"shop_id": "sort-shop"})
        names = [item["sale_name"] for item in resp.json().get("items", [])]
        assert names == ["Apple", "Banana", "Carrot"]
