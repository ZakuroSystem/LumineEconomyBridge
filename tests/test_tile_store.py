import os
import tempfile
import sys

sys.path.append(os.path.join(os.path.dirname(__file__), "..", "backend"))

from tile_format import PIXEL_COUNT
from tile_store import TileStore


def test_save_tile_creates_meta():
    with tempfile.TemporaryDirectory() as d:
        store = TileStore(d)
        indices = [0] * PIXEL_COUNT
        store.save_tile("world", 1, -2, indices)
        path = store.tile_path("world", 1, -2)
        assert os.path.exists(path)
        meta_path = path + ".meta.json"
        assert os.path.exists(meta_path)
        import json

        with open(meta_path, "r", encoding="utf-8") as f:
            meta = json.load(f)
        assert meta["size"] > 0
        assert meta["sha1"]
        assert meta["last_updated"] > 0

        # invalidate should remove both files and queue the tile
        store.invalidate("world", [{"tx": 1, "tz": -2}])
        assert not os.path.exists(path)
        assert not os.path.exists(meta_path)
        status = store.status()
        assert status["regen_queue"] == 1
