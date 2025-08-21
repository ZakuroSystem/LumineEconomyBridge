import os
import sys
import base64
import tempfile

BACKEND_DIR = os.path.join(os.path.dirname(__file__), "..", "backend")
sys.path.append(BACKEND_DIR)

from fastapi.testclient import TestClient

# change cwd so main can read lang.yml
CWD = os.getcwd()
os.chdir(BACKEND_DIR)
import main
from tile_format import decode_tile
app = main.app
os.chdir(CWD)


def test_chunk_snapshot_merge():
    with tempfile.TemporaryDirectory() as d:
        main.tile_store.base_dir = d
        main.tile_store._regen_queue.clear()
        main.tile_store._dirty.clear()

        data = base64.b64encode(bytes([5] * 256)).decode()
        chunks = []
        for cx in range(4):
            for cz in range(4):
                chunks.append({"cx": cx, "cz": cz, "data": data})
        payload = {"world": "world", "y_start": 250, "chunks": chunks, "ts": 0}

        client = TestClient(app)
        headers = {"X-LE-Token": main.SHARED_TOKEN}
        resp = client.post("/plugin/chunk_snapshot", json=payload, headers=headers)
        assert resp.status_code == 200

        main.tile_store.process_dirty(0)
        main.tile_store.process_queue()

        raw = main.tile_store.load_tile("world", 0, 0)
        assert raw is not None
        _, indices = decode_tile(raw)
        assert all(i == 5 for i in indices)

        status = client.get("/tiles/status", headers=headers)
    assert status.json()["queue_len"] == 0


def test_chunk_snapshot_rate_limit():
    with tempfile.TemporaryDirectory() as d:
        main.tile_store.base_dir = d
        main.tile_store._regen_queue.clear()
        main.tile_store._dirty.clear()
        main.RATE_LIMIT.clear()

        data = base64.b64encode(bytes([1] * 256)).decode()
        payload = {"world": "w", "y_start": 250, "chunks": [{"cx": 0, "cz": 0, "data": data}], "ts": 0}

        client = TestClient(app)
        headers = {"X-LE-Token": main.SHARED_TOKEN}
        for _ in range(10):
            r = client.post("/plugin/chunk_snapshot", json=payload, headers=headers)
            assert r.status_code == 200
        resp = client.post("/plugin/chunk_snapshot", json=payload, headers=headers)
        assert resp.status_code == 429
