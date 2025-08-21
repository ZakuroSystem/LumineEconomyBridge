"""Initial full rendering of world tiles from .mca region files.

This module provides a job that scans all region files for a given world and
creates 64x64 tiles using the :class:`TileStore`. It relies on a
``PaletteClient`` implementation to resolve block names to 0-63 colour
indices.
"""

from __future__ import annotations

import os
from collections import defaultdict
from typing import Callable, Dict, Iterable, Tuple

import anvil

from tile_format import PIXEL_COUNT, TILE_SIZE
from tile_store import TileStore


class PaletteClient:
    """HTTP client for the Java mapcolor plugin."""

    def __init__(self, base_url: str, token: str) -> None:
        import requests  # local import to keep optional during tests

        self._session = requests.Session()
        self._session.headers.update({"X-LE-Token": token})
        self.base_url = base_url.rstrip("/")

    def palette(self) -> Dict:
        resp = self._session.get(f"{self.base_url}/plugin/mapcolor/palette", timeout=10)
        resp.raise_for_status()
        return resp.json()

    def resolve(self, blocks: Iterable[str]) -> Iterable[int]:
        resp = self._session.post(
            f"{self.base_url}/plugin/mapcolor/resolve",
            json={"blocks": list(blocks)},
            timeout=10,
        )
        resp.raise_for_status()
        return resp.json().get("indices", [])


def _top_index(chunk: "anvil.Chunk", x: int, z: int, resolver: Callable[[str], int]) -> int:
    """Return the colour index for the column at (x,z)."""

    for y in range(250, -64, -1):
        block = chunk.get_block(x, y, z)
        name = getattr(block, "id", "minecraft:air")
        if name != "minecraft:air":
            return resolver(name)
    return 0


def generate_world_tiles(
    world: str,
    region_dir: str,
    client: PaletteClient,
    store: TileStore,
    log_fn: Callable[[Dict], None] | None = None,
) -> None:
    """Process all region files under ``region_dir`` and output tiles."""

    client.palette()  # ensure palette sync
    cache: Dict[str, int] = {}
    tiles: Dict[Tuple[int, int], list[int]] = defaultdict(lambda: [0] * PIXEL_COUNT)

    for fname in os.listdir(region_dir):
        if not fname.endswith(".mca"):
            continue
        r = anvil.Region.from_file(os.path.join(region_dir, fname))
        for cx in range(32):
            for cz in range(32):
                if not r.chunk_data_exists(cx, cz):
                    continue
                try:
                    chunk = r.get_chunk(cx, cz)
                except Exception:
                    continue
                global_cx = r.x * 32 + cx
                global_cz = r.z * 32 + cz
                tx, tz = global_cx // 4, global_cz // 4
                tile = tiles[tx, tz]
                for lx in range(16):
                    for lz in range(16):
                        def resolve(name: str) -> int:
                            if name not in cache:
                                cache[name] = next(iter(client.resolve([name]) or [0]))
                            return cache[name]

                        idx = _top_index(chunk, lx, lz, resolve)
                        px = (global_cx % 4) * 16 + lx
                        pz = (global_cz % 4) * 16 + lz
                        tile[pz * TILE_SIZE + px] = idx

    for (tx, tz), indices in tiles.items():
        store.save_tile(world, tx, tz, indices)
        if log_fn:
            log_fn(
                {
                    "type": "tile_generation",
                    "world": world,
                    "tx": tx,
                    "tz": tz,
                }
            )


def _print_log(entry: Dict) -> None:
    """Default logger for CLI usage."""

    import json

    print(json.dumps(entry))


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="Full world tile generation")
    parser.add_argument("world", help="World identifier")
    parser.add_argument("region_dir", help="Path to world/region directory")
    parser.add_argument("--api", default="http://127.0.0.1:8765", help="MapColor service base URL")
    parser.add_argument("--token", default="", help="Shared authentication token")
    args = parser.parse_args()

    client = PaletteClient(args.api, args.token)
    store = TileStore("tiles")
    generate_world_tiles(args.world, args.region_dir, client, store, log_fn=_print_log)
