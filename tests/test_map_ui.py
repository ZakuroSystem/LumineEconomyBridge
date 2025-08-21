import os
import sys

BACKEND_DIR = os.path.join(os.path.dirname(__file__), "..", "backend")
sys.path.append(BACKEND_DIR)

from dashboard import app


def test_map_page_renders():
    client = app.test_client()
    with client.session_transaction() as sess:
        sess['user'] = 'admin'
        sess['admin_mode'] = True
    resp = client.get('/map')
    assert resp.status_code == 200
    assert b'World Map' in resp.data
