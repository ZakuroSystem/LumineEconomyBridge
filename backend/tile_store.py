import os
from typing import Dict, Iterable, List, Optional, Tuple

from tile_format import encode_tile


class TileStore:
    def __init__(self, base_dir: str) -> None:
        self.base_dir = base_dir
        os.makedirs(self.base_dir, exist_ok=True)
        self._regen_queue: List[Tuple[str, int, int]] = []

    def tile_path(self, world: str, tx: int, tz: int) -> str:
        fname = f"tile_{world}_{tx}_{tz}.tile.zlib"
        return os.path.join(self.base_dir, fname)

    def load_tile(self, world: str, tx: int, tz: int) -> Optional[bytes]:
        path = self.tile_path(world, tx, tz)
        if not os.path.exists(path):
            return None
        with open(path, "rb") as f:
            return f.read()

    def save_tile(self, world: str, tx: int, tz: int, indices: Iterable[int]) -> None:
        path = self.tile_path(world, tx, tz)
        data = encode_tile(tx, tz, indices)
        tmp = path + ".tmp"
        with open(tmp, "wb") as f:
            f.write(data)
        os.replace(tmp, path)

    def invalidate(self, world: str, tiles: List[Dict[str, int]]) -> None:
        for t in tiles:
            path = self.tile_path(world, t["tx"], t["tz"])
            if os.path.exists(path):
                os.remove(path)
            self._regen_queue.append((world, t["tx"], t["tz"]))

    def status(self) -> Dict[str, int]:
        files = [n for n in os.listdir(self.base_dir) if n.endswith(".tile.zlib")]
        return {"tiles": len(files), "regen_queue": len(self._regen_queue)}
