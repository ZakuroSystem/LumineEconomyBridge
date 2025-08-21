import struct
import time
import zlib
from dataclasses import dataclass
from typing import Iterable, List

MAGIC = b"MTIL"
PALETTE_LEN = 64
RESERVED = 0
TILE_SIZE = 64
PIXEL_COUNT = TILE_SIZE * TILE_SIZE
PACKED_SIZE = PIXEL_COUNT * 6 // 8  # 3072 bytes


@dataclass
class TileHeader:
    tx: int
    tz: int
    timestamp_ms: int

    def encode(self) -> bytes:
        return struct.pack(
            ">4sBBHiiQ",
            MAGIC,
            PALETTE_LEN,
            RESERVED,
            TILE_SIZE,
            self.tx,
            self.tz,
            self.timestamp_ms,
        )


def pack_indices(indices: Iterable[int]) -> bytes:
    bits = 0
    bit_len = 0
    out = bytearray()
    for idx in indices:
        bits |= (idx & 0x3F) << bit_len
        bit_len += 6
        while bit_len >= 8:
            out.append(bits & 0xFF)
            bits >>= 8
            bit_len -= 8
    if bit_len:
        out.append(bits & 0xFF)
    return bytes(out)


def unpack_indices(data: bytes) -> List[int]:
    indices: List[int] = []
    bits = 0
    bit_len = 0
    for b in data:
        bits |= b << bit_len
        bit_len += 8
        while bit_len >= 6:
            indices.append(bits & 0x3F)
            bits >>= 6
            bit_len -= 6
    return indices


def encode_tile(tx: int, tz: int, indices: Iterable[int]) -> bytes:
    header = TileHeader(tx, tz, int(time.time() * 1000)).encode()
    packed = pack_indices(indices)
    compressed = zlib.compress(packed)
    return header + compressed


def decode_tile(data: bytes) -> List[int]:
    body = data[24:]  # header is fixed 24 bytes
    packed = zlib.decompress(body)
    return unpack_indices(packed)
