import os
import sys

BACKEND_DIR = os.path.join(os.path.dirname(__file__), "..", "backend")
sys.path.append(BACKEND_DIR)

from dashboard import app


def test_register_missing_fields_returns_200():
    client = app.test_client()
    with client.session_transaction() as sess:
        sess['_csrf_token'] = 'tok'
    resp = client.post('/register', data={'username': 'u', 'password': 'p', '_csrf_token': 'tok'})
    assert resp.status_code == 200
    assert b'All fields are required' in resp.data
