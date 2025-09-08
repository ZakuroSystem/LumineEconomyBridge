import os
import sys

import anvil

BACKEND_DIR = os.path.join(os.path.dirname(__file__), '..', 'backend')
sys.path.append(BACKEND_DIR)

from dashboard import _top_index


def _resolver(name: str) -> int:
    return 1 if name == "minecraft:grass_block" else 0


def test_top_index_below_zero():
    chunk = anvil.EmptyChunk(0, 0)
    section = anvil.EmptySection(-1)
    section.set_block(anvil.Block("minecraft", "grass_block"), 0, 0, 0)
    chunk.sections[0] = section
    assert _top_index(chunk, 0, 0, _resolver) != 0


def test_top_index_above_255():
    chunk = anvil.EmptyChunk(0, 0)
    section = anvil.EmptySection(16)
    section.set_block(anvil.Block("minecraft", "grass_block"), 0, 15, 0)
    chunk.sections[15] = section
    assert _top_index(chunk, 0, 0, _resolver) != 0

