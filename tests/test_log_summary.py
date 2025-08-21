import os
import tempfile
import sys

BACKEND_DIR = os.path.join(os.path.dirname(__file__), "..", "backend")
sys.path.append(BACKEND_DIR)

from fastapi.testclient import TestClient

# ensure main reads config
CWD = os.getcwd()
os.chdir(BACKEND_DIR)
import main
app = main.app
os.chdir(CWD)

def test_log_summary_endpoint():
    orig_path = main.LOG_PATH
    with tempfile.TemporaryDirectory() as d:
        main.LOG_PATH = os.path.join(d, "audit.log")
        main.tile_store.log_fn = main.append_log
        main.append_log({"type": "tile_merge", "ts": 1})
        main.append_log({"type": "tile_merge_error", "error": "boom", "ts": 2})
        with TestClient(app) as client:
            headers = {"X-LE-Token": main.SHARED_TOKEN}
            resp = client.get("/logs/summary", headers=headers)
            assert resp.status_code == 200
            data = resp.json()
            assert data["tile_merge"]["count"] == 1
            assert data["tile_merge_error"]["errors"] == 1
    main.LOG_PATH = orig_path
    main.tile_store.log_fn = main.append_log
