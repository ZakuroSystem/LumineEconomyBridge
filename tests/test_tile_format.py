import os
import random
import sys

sys.path.append(os.path.join(os.path.dirname(__file__), "..", "backend"))

from tile_format import (
    TILE_SIZE,
    PIXEL_COUNT,
    encode_tile,
    decode_tile,
    pack_indices,
    unpack_indices,
)


def make_indices() -> list[int]:
    # produce deterministic pseudo-random indices
    random.seed(1234)
    return [random.randint(0, 63) for _ in range(PIXEL_COUNT)]


def test_pack_unpack_roundtrip():
    indices = make_indices()
    packed = pack_indices(indices)
    assert len(packed) == PIXEL_COUNT * 6 // 8
    assert unpack_indices(packed) == indices


def test_encode_decode_tile_roundtrip():
    indices = make_indices()
    data = encode_tile(5, -3, indices)
    header, decoded = decode_tile(data)
    assert (header.tx, header.tz) == (5, -3)
    assert decoded == indices
