import os
import sys
import threading
import json
from http.server import BaseHTTPRequestHandler, HTTPServer

BACKEND_DIR = os.path.join(os.path.dirname(__file__), "..", "backend")
sys.path.append(BACKEND_DIR)
from mca_import import PaletteClient

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == '/plugin/mapcolor/palette':
            body = json.dumps({
                'palette': [[i, i, i] for i in range(64)],
                'world_info': [{'name': 'world', 'minY': 0, 'maxY': 256, 'border': 29999984}],
                'server_version': 'test'
            }).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_response(404)
            self.end_headers()
    def do_POST(self):
        if self.path == '/plugin/mapcolor/resolve':
            length = int(self.headers.get('Content-Length', 0))
            body = self.rfile.read(length)
            data = json.loads(body)
            blocks = data.get('blocks', [])
            mapping = {'minecraft:grass_block':1}
            indices = [mapping.get(b,0) for b in blocks]
            resp = json.dumps({'indices': indices}).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(resp)))
            self.end_headers()
            self.wfile.write(resp)
        else:
            self.send_response(404)
            self.end_headers()


def test_palette_client():
    server = HTTPServer(('127.0.0.1', 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    base = f'http://127.0.0.1:{server.server_port}'
    client = PaletteClient(base, 'token')
    data = client.palette()
    assert len(data['palette']) == 64
    assert data['world_info'][0]['name'] == 'world'
    indices = list(client.resolve(['minecraft:grass_block', 'minecraft:stone']))
    assert indices == [1,0]
    server.shutdown()
    thread.join()
