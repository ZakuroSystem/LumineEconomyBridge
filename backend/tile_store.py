"""Persistence layer for tile files and incoming chunk snapshots.

The store handles storing individual 16×16 chunk top arrays sent from the
Java plugin, marking tiles dirty, and regenerating full 64×64 tiles once the
debounce interval has elapsed. A very small in-memory queue is used to track
tiles awaiting merge which is exposed through the status endpoint.
"""

import base64
import json
import os
import time
import hashlib
from typing import Callable, Dict, Iterable, List, Optional, Tuple

from tile_format import PIXEL_COUNT, TILE_SIZE, encode_tile


class TileStore:
    """Store tile files on disk and track invalidation/merge queues."""

    def __init__(self, base_dir: str, log_fn: Callable[[Dict], None] | None = None) -> None:
        self.base_dir = base_dir
        os.makedirs(self.base_dir, exist_ok=True)
        self._regen_queue: List[Tuple[str, int, int]] = []
        self._dirty: Dict[Tuple[str, int, int], float] = {}
        self.log_fn = log_fn
        self._merge_times: List[float] = []

    def tile_path(self, world: str, tx: int, tz: int) -> str:
        fname = f"tile_{world}_{tx}_{tz}.tile.zlib"
        return os.path.join(self.base_dir, fname)

    def load_tile(self, world: str, tx: int, tz: int) -> Optional[bytes]:
        """Load a tile's raw bytes from disk if present."""

        path = self.tile_path(world, tx, tz)
        if not os.path.exists(path):
            return None
        with open(path, "rb") as f:
            return f.read()

    def tile_meta(self, world: str, tx: int, tz: int) -> Dict:
        """Return metadata dictionary for a tile if available."""

        meta_path = self.tile_path(world, tx, tz) + ".meta.json"
        if os.path.exists(meta_path):
            try:
                with open(meta_path, "r", encoding="utf-8") as mf:
                    return json.load(mf)
            except Exception:
                pass
        return {}

    def save_tile(
        self, world: str, tx: int, tz: int, indices: Iterable[int]
    ) -> None:
        """Persist a tile to disk atomically."""

        path = self.tile_path(world, tx, tz)
        data = encode_tile(tx, tz, list(indices))
        tmp = path + ".tmp"
        with open(tmp, "wb") as f:
            f.write(data)
        os.replace(tmp, path)

        now_ms = int(time.time() * 1000)
        meta = {
            "last_updated": now_ms,
            "last_seen": now_ms,
            "size": len(data),
            "sha1": hashlib.sha1(data).hexdigest(),
        }
        with open(path + ".meta.json", "w", encoding="utf-8") as mf:
            json.dump(meta, mf)

    def invalidate(self, world: str, tiles: List[Dict[str, int]]) -> None:
        """Remove tiles and queue them for regeneration."""

        for t in tiles:
            path = self.tile_path(world, t["tx"], t["tz"])
            if os.path.exists(path):
                os.remove(path)
            meta_path = path + ".meta.json"
            if os.path.exists(meta_path):
                os.remove(meta_path)
            self._regen_queue.append((world, t["tx"], t["tz"]))

    def touch_tile(self, world: str, tx: int, tz: int) -> None:
        """Update a tile's last-seen timestamp if metadata exists."""

        meta_path = self.tile_path(world, tx, tz) + ".meta.json"
        if not os.path.exists(meta_path):
            return
        try:
            with open(meta_path, "r", encoding="utf-8") as mf:
                meta = json.load(mf)
        except Exception:
            return
        meta["last_seen"] = int(time.time() * 1000)
        with open(meta_path, "w", encoding="utf-8") as mf:
            json.dump(meta, mf)

    # chunk snapshot handling -------------------------------------------------

    def save_chunk(self, world: str, cx: int, cz: int, data_b64: str) -> None:
        """Persist a 16×16 top-block snapshot for a chunk.

        The ``data_b64`` argument must decode to exactly 256 bytes.
        """

        raw = base64.b64decode(data_b64)
        if len(raw) != 256:
            raise ValueError("chunk snapshot must be 256 bytes")
        tx, tz = cx // 4, cz // 4
        dir_path = os.path.join(
            self.base_dir,
            "tmp_chunks",
            world,
            f"tile_{tx}_{tz}",
        )
        os.makedirs(dir_path, exist_ok=True)
        with open(os.path.join(dir_path, f"c_{cx}_{cz}.bin"), "wb") as f:
            f.write(raw)
        self._dirty[(world, tx, tz)] = time.time()

    def process_dirty(self, debounce_ms: int = 1000) -> None:
        """Move old dirty tiles to the regeneration queue."""

        now = time.time()
        ready = [
            (w, tx, tz)
            for (w, tx, tz), ts in self._dirty.items()
            if (now - ts) * 1000 >= debounce_ms
        ]
        for key in ready:
            self._regen_queue.append(key)
            del self._dirty[key]

    def _merge_tile(self, world: str, tx: int, tz: int) -> None:
        """Merge stored chunk snapshots into a tile file."""

        start = time.time()
        dir_path = os.path.join(self.base_dir, "tmp_chunks", world, f"tile_{tx}_{tz}")
        indices = [0] * PIXEL_COUNT
        if os.path.isdir(dir_path):
            for fname in os.listdir(dir_path):
                if not fname.startswith("c_") or not fname.endswith(".bin"):
                    continue
                parts = fname[2:-4].split("_")
                if len(parts) != 2:
                    continue
                cx, cz = map(int, parts)
                local_x = cx - tx * 4
                local_z = cz - tz * 4
                with open(os.path.join(dir_path, fname), "rb") as f:
                    data = f.read()
                if len(data) != 256:
                    continue
                for lx in range(16):
                    for lz in range(16):
                        idx = data[lz * 16 + lx]
                        px = local_x * 16 + lx
                        pz = local_z * 16 + lz
                        indices[pz * TILE_SIZE + px] = idx
            # cleanup
            for fname in os.listdir(dir_path):
                os.remove(os.path.join(dir_path, fname))
            os.rmdir(dir_path)
        self.save_tile(world, tx, tz, indices)
        duration = (time.time() - start) * 1000
        self._merge_times.append(duration)
        if len(self._merge_times) > 100:
            self._merge_times.pop(0)
        if self.log_fn:
            self.log_fn(
                {
                    "type": "tile_merge",
                    "world": world,
                    "tx": tx,
                    "tz": tz,
                    "merge_ms": int(duration),
                }
            )

    def process_queue(self) -> None:
        """Process all queued tile merges."""

        while self._regen_queue:
            world, tx, tz = self._regen_queue.pop(0)
            try:
                self._merge_tile(world, tx, tz)
            except Exception as exc:  # pragma: no cover - defensive
                if self.log_fn:
                    self.log_fn(
                        {
                            "type": "tile_merge_error",
                            "world": world,
                            "tx": tx,
                            "tz": tz,
                            "error": str(exc),
                        }
                    )

    def status(self) -> Dict[str, int]:
        """Return simple metrics about stored tiles."""

        files = [n for n in os.listdir(self.base_dir) if n.endswith(".tile.zlib")]
        last_updated = 0
        for fname in files:
            meta_path = os.path.join(self.base_dir, fname + ".meta.json")
            if os.path.exists(meta_path):
                try:
                    with open(meta_path, "r", encoding="utf-8") as mf:
                        last_updated = max(
                            last_updated, json.load(mf).get("last_updated", 0)
                        )
                except Exception:
                    pass
        return {
            "queue_len": len(self._regen_queue) + len(self._dirty),
            "tile_count": len(files),
            "last_updated": last_updated,
        }

    # metrics and maintenance -------------------------------------------------

    def metrics(self) -> Dict[str, float]:
        """Return Prometheus-style metrics."""

        files = [n for n in os.listdir(self.base_dir) if n.endswith(".tile.zlib")]
        total_bytes = 0
        for fname in files:
            try:
                total_bytes += os.path.getsize(os.path.join(self.base_dir, fname))
            except OSError:
                pass
        avg_merge = sum(self._merge_times) / len(self._merge_times) if self._merge_times else 0.0
        return {
            "tile_queue_len": len(self._regen_queue) + len(self._dirty),
            "tile_avg_merge_ms": avg_merge,
            "tile_disk_bytes": total_bytes,
        }

    def gc(
        self,
        max_age_ms: Optional[int] = None,
        border: Optional[Tuple[int, int, int, int]] = None,
        world: Optional[str] = None,
    ) -> int:
        """Garbage-collect tiles older than ``max_age_ms`` or outside ``border``.

        Returns number of tiles removed.
        """

        now_ms = int(time.time() * 1000)
        removed = 0
        for fname in list(os.listdir(self.base_dir)):
            if not fname.endswith(".tile.zlib"):
                continue
            parts = fname[:-10].split("_")
            if len(parts) < 4:
                continue
            _, w, tx_s, tz_s = parts
            if world and w != world:
                continue
            try:
                tx, tz = int(tx_s), int(tz_s)
            except ValueError:
                continue
            meta = self.tile_meta(w, tx, tz)
            last_seen = meta.get("last_seen", meta.get("last_updated", 0))
            remove = False
            if max_age_ms is not None and now_ms - last_seen > max_age_ms:
                remove = True
            if border is not None:
                min_tx, max_tx, min_tz, max_tz = border
                if tx < min_tx or tx > max_tx or tz < min_tz or tz > max_tz:
                    remove = True
            if not remove:
                continue
            path = os.path.join(self.base_dir, fname)
            try:
                os.remove(path)
            except OSError:
                pass
            meta_path = path + ".meta.json"
            if os.path.exists(meta_path):
                try:
                    os.remove(meta_path)
                except OSError:
                    pass
            removed += 1
        return removed
