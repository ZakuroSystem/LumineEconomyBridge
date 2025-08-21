"""Wrapper module for world tile generation utilities.

This module now re-exports the helpers from ``dashboard`` so they can be used
as a library without maintaining separate implementations.
"""

from dashboard import PaletteClient, generate_world_tiles


def _print_log(entry):
    """Default logger for CLI usage."""
    import json

    print(json.dumps(entry))


if __name__ == "__main__":  # pragma: no cover - CLI utility
    import argparse
    from tile_store import TileStore

    parser = argparse.ArgumentParser(description="Full world tile generation")
    parser.add_argument("world", help="World identifier")
    parser.add_argument("region_dir", help="Path to world/region directory")
    parser.add_argument(
        "--api", default="http://127.0.0.1:8765", help="MapColor service base URL"
    )
    parser.add_argument(
        "--token", default="", help="Shared authentication token"
    )
    args = parser.parse_args()

    client = PaletteClient(args.api, args.token)
    store = TileStore("tiles")
    generate_world_tiles(args.world, args.region_dir, client, store, log_fn=_print_log)

