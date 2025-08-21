import os
import sys
import tempfile
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


def test_metrics_and_gc():
    with tempfile.TemporaryDirectory() as d:
        main.tile_store.base_dir = d
        main.tile_store._regen_queue.clear()
        main.tile_store._dirty.clear()

        indices = [0] * 4096
        main.tile_store.save_tile("world", 0, 0, indices)
        # make tile appear old
        meta_path = main.tile_store.tile_path("world", 0, 0) + ".meta.json"
        meta = main.tile_store.tile_meta("world", 0, 0)
        meta["last_seen"] = int((time.time() - 10 * 86400) * 1000)
        with open(meta_path, "w", encoding="utf-8") as mf:
            import json
            json.dump(meta, mf)

        main.tile_store.save_tile("world", 1, 0, indices)

        removed = main.tile_store.gc(max_age_ms=7 * 86400 * 1000)
        assert removed == 1
        assert os.path.exists(main.tile_store.tile_path("world", 1, 0))

        with TestClient(app) as client:
            metrics = client.get("/metrics")
            assert metrics.status_code == 200
            body = metrics.text
            assert "tile_queue_len" in body
            assert "tile_disk_bytes" in body
