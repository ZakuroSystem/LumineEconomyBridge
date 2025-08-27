import os
import sys

BACKEND_DIR = os.path.join(os.path.dirname(__file__), '..', 'backend')
sys.path.append(BACKEND_DIR)

from fastapi.testclient import TestClient

# ensure main can read lang.yml
CWD = os.getcwd()
os.chdir(BACKEND_DIR)
import main
app = main.app
os.chdir(CWD)


def test_cash_note_endpoint():
    with main.cash_conn:
        main.cash_conn.execute("DELETE FROM cash_events")
        main.cash_conn.execute("DELETE FROM notes")
    headers = {"X-LE-Token": main.SHARED_TOKEN}
    payload = {
        "note_id": "n1",
        "player_uuid": "u1",
        "action": "store",
        "currency": "thy",
        "amount": 5,
        "location": "world,1,64,2",
    }
    with TestClient(app) as client:
        resp = client.post("/api/cash/event", json=payload, headers=headers)
        assert resp.status_code == 200
        resp = client.get("/api/cash/note/n1", headers=headers)
        assert resp.status_code == 200
        data = resp.json()
        assert data["note"]["world"] == "world"
        assert data["note"]["x"] == 1
        assert data["note"]["y"] == 64
        assert data["note"]["z"] == 2
        assert data["history"][0]["action"] == "store"


def test_cash_event_drop_and_move_update_location():
    with main.cash_conn:
        main.cash_conn.execute("DELETE FROM cash_events")
        main.cash_conn.execute("DELETE FROM notes")
    headers = {"X-LE-Token": main.SHARED_TOKEN}
    # initial store to create note
    payload = {
        "note_id": "n2",
        "player_uuid": "u2",
        "action": "store",
        "currency": "thy",
        "amount": 7,
        "location": "world,0,60,0",
    }
    with TestClient(app) as client:
        resp = client.post("/api/cash/event", json=payload, headers=headers)
        assert resp.status_code == 200
        # drop at new location
        drop_payload = dict(payload)
        drop_payload.update({"action": "drop", "location": "world,5,60,5"})
        resp = client.post("/api/cash/event", json=drop_payload, headers=headers)
        assert resp.status_code == 200
        # move to another location
        move_payload = dict(payload)
        move_payload.update({"action": "move", "location": "world,6,60,6"})
        resp = client.post("/api/cash/event", json=move_payload, headers=headers)
        assert resp.status_code == 200
        resp = client.get("/api/cash/note/n2", headers=headers)
        assert resp.status_code == 200
        data = resp.json()
        assert data["note"]["world"] == "world"
        assert data["note"]["x"] == 6
        assert data["note"]["y"] == 60
        assert data["note"]["z"] == 6
        actions = [h["action"] for h in data["history"]]
        assert actions == ["store", "drop", "move"]
