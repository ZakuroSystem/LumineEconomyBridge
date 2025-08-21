import os
import sys
import tempfile
import time
import base64

BACKEND_DIR = os.path.join(os.path.dirname(__file__), "..", "backend")
sys.path.append(BACKEND_DIR)

from fastapi.testclient import TestClient

# change cwd so main can read lang.yml
CWD = os.getcwd()
os.chdir(BACKEND_DIR)
import main
app = main.app
os.chdir(CWD)


def test_tile_websocket_push():
    with tempfile.TemporaryDirectory() as d:
        main.tile_store.base_dir = d
        main.tile_store._regen_queue.clear()
        main.tile_store._dirty.clear()
        headers = {"X-LE-Token": main.SHARED_TOKEN}
        with TestClient(app) as client:
            with client.websocket_connect("/ws/tiles") as ws:
                body = {
                    "world": "world",
                    "y_start": 250,
                    "chunks": [
                        {
                            "cx": 0,
                            "cz": 0,
                            "data": base64.b64encode(b"\x01" * 256).decode(),
                        }
                    ],
                    "ts": 0,
                }
                client.post("/plugin/chunk_snapshot", json=body, headers=headers)
                time.sleep(1.5)
                msg = ws.receive_json()
                assert msg["world"] == "world"
                assert msg["tx"] == 0 and msg["tz"] == 0
