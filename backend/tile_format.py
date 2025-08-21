"""Tile file format helpers.

This module implements packing and unpacking of 64×64 tile data. Each
pixel stores a 6-bit colour index which is zlib compressed following a fixed
24-byte header.
"""

import struct
import time
import zlib
from dataclasses import dataclass
from typing import Iterable, List, Sequence, Tuple

MAGIC = b"MTIL"
PALETTE_LEN = 64
RESERVED = 0
TILE_SIZE = 64
PIXEL_COUNT = TILE_SIZE * TILE_SIZE
PACKED_SIZE = PIXEL_COUNT * 6 // 8  # 3072 bytes


@dataclass
class TileHeader:
    """Metadata stored in the first 24 bytes of a tile file."""

    tx: int
    tz: int
    timestamp_ms: int

    def encode(self) -> bytes:
        """Encode the header to its binary representation."""

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

    @staticmethod
    def decode(data: bytes) -> "TileHeader":
        """Decode a 24-byte header into a :class:`TileHeader`.

        Raises:
            ValueError: if the header is malformed or constants differ from
                the expected values.
        """

        (magic, palette_len, reserved, tile_size, tx, tz, ts) = struct.unpack(
            ">4sBBHiiQ", data
        )
        if (
            magic != MAGIC
            or palette_len != PALETTE_LEN
            or reserved != RESERVED
            or tile_size != TILE_SIZE
        ):
            raise ValueError("invalid tile header")
        return TileHeader(tx=tx, tz=tz, timestamp_ms=ts)


def pack_indices(indices: Sequence[int]) -> bytes:
    """Pack 4096 colour indices into 6-bit little-endian representation."""

    if len(indices) != PIXEL_COUNT:
        raise ValueError(f"expected {PIXEL_COUNT} indices, got {len(indices)}")
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
    """Unpack 6-bit packed data into a list of indices."""

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


def encode_tile(tx: int, tz: int, indices: Sequence[int]) -> bytes:
    """Encode a tile from colour indices.

    Args:
        tx, tz: Tile coordinates.
        indices: Sequence of length 4096 with 6-bit colour indices.
    """

    header = TileHeader(tx, tz, int(time.time() * 1000)).encode()
    packed = pack_indices(indices)
    compressed = zlib.compress(packed)
    return header + compressed


def decode_tile(data: bytes) -> Tuple[TileHeader, List[int]]:
    """Decode tile bytes into header and colour indices."""

    header = TileHeader.decode(data[:24])
    packed = zlib.decompress(data[24:])
    indices = unpack_indices(packed)
    if len(indices) != PIXEL_COUNT:
        raise ValueError("corrupt tile payload")
    return header, indices
