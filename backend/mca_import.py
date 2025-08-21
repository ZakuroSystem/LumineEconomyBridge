"""World tile generation utilities.

This module simply re-exports the helpers from :mod:`dashboard` so other
modules can import them without relying on a standalone CLI script.
"""

from dashboard import PaletteClient, generate_world_tiles

__all__ = ["PaletteClient", "generate_world_tiles"]
