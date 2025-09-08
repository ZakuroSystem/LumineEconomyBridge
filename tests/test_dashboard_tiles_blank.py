import os
import sys
import tempfile

BACKEND_DIR = os.path.join(os.path.dirname(__file__), '..', 'backend')
sys.path.append(BACKEND_DIR)

from dashboard import app, tile_store


def test_blank_tile_served_from_dashboard():
    with tempfile.TemporaryDirectory() as d:
        tile_store.base_dir = d
        tile_store._regen_queue.clear()
        tile_store._dirty.clear()
        tile_store.save_tile('world', 0, 0, [0] * 4096)

        client = app.test_client()
        resp = client.get('/tiles/world/0/0')
        assert resp.status_code == 200
        assert len(resp.data) > 24
