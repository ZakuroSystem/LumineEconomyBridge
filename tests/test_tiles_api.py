import os
import tempfile
import sys

BACKEND_DIR = os.path.join(os.path.dirname(__file__), "..", "backend")
sys.path.append(BACKEND_DIR)

from fastapi.testclient import TestClient

# change cwd so main can read lang.yml
CWD = os.getcwd()
os.chdir(BACKEND_DIR)
import main
app = main.app
os.chdir(CWD)

def test_tile_endpoints_with_token():
    with tempfile.TemporaryDirectory() as d:
        main.tile_store.base_dir = d
        main.tile_store._regen_queue.clear()
        indices = [0] * 4096
        main.tile_store.save_tile("world", 0, 0, indices)

        client = TestClient(app)
        headers = {"X-LE-Token": main.SHARED_TOKEN}
        resp = client.get("/tiles/world/0/0", headers=headers)
        assert resp.status_code == 200
        assert len(resp.content) > 24

        status = client.get("/tiles/status", headers=headers)
        assert status.status_code == 200
        assert status.json()["tile_count"] == 1

        bad = client.get("/tiles/world/0/0", headers={"X-LE-Token": "bad"})
        assert bad.status_code == 401
