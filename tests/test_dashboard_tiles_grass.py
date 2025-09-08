import os
import sys
import tempfile
import re

BACKEND_DIR = os.path.join(os.path.dirname(__file__), '..', 'backend')
sys.path.append(BACKEND_DIR)

from dashboard import generate_world_tiles, PaletteClient, tile_store
from tile_format import decode_tile
import anvil


def test_generate_tile_has_grass():
    with tempfile.TemporaryDirectory() as d:
        region_dir = os.path.join(d, 'region')
        os.makedirs(region_dir)
        reg = anvil.EmptyRegion(0, 0)
        reg.set_block(anvil.Block('minecraft', 'grass_block'), 0, 255, 0)
        reg.save(os.path.join(region_dir, 'r.0.0.mca'))

        old_base = tile_store.base_dir
        tile_store.base_dir = os.path.join(d, 'tiles')
        os.makedirs(tile_store.base_dir, exist_ok=True)
        tile_store._regen_queue.clear()
        tile_store._dirty.clear()

        orig_from_file = anvil.Region.from_file

        class RegionWithCoords(anvil.Region):
            __slots__ = ("x", "z")

            def __init__(self, data: bytes, x: int, z: int):
                super().__init__(data)
                self.x = x
                self.z = z

        def from_file_with_coords(path):
            base = orig_from_file(path)
            m = re.match(r"r\.(-?\d+)\.(-?\d+)\.mca", os.path.basename(path))
            x = int(m.group(1)) if m else 0
            z = int(m.group(2)) if m else 0
            return RegionWithCoords(base.data, x, z)

        anvil.Region.from_file = from_file_with_coords
        try:
            generate_world_tiles('world', region_dir, PaletteClient(), tile_store)
            raw = tile_store.load_tile('world', 0, 0)
            assert raw is not None
            _, indices = decode_tile(raw)
            assert any(i != 0 for i in indices)
        finally:
            anvil.Region.from_file = orig_from_file
            tile_store.base_dir = old_base
