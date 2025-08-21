import argparse
import sys
from tile_store import TileStore


def main() -> int:
    parser = argparse.ArgumentParser(description="Purge old or out-of-bounds tiles")
    parser.add_argument("base", help="Tile directory")
    parser.add_argument("--max-age-days", type=int, default=None)
    parser.add_argument(
        "--border",
        nargs=4,
        type=int,
        metavar=("min_tx", "max_tx", "min_tz", "max_tz"),
        default=None,
        help="Tile coordinate border to keep",
    )
    parser.add_argument("--world", default=None)
    args = parser.parse_args()

    store = TileStore(args.base)
    max_age_ms = args.max_age_days * 86400000 if args.max_age_days is not None else None
    removed = store.gc(max_age_ms=max_age_ms, border=tuple(args.border) if args.border else None, world=args.world)
    print(removed)
    return 0


if __name__ == "__main__":
    sys.exit(main())
