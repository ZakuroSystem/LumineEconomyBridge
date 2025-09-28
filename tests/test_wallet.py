import sys

from pathlib import Path

BACKEND_DIR = Path(__file__).resolve().parents[1] / "backend"
sys.path.append(str(BACKEND_DIR))

import main  # noqa: E402


def _prepare_currency(cur, currency: str = "zen"):
    main.ensure_currency(cur, currency, None)
    cur.execute(
        "INSERT OR REPLACE INTO settings(key, value) VALUES('default_currency', ?)",
        (currency,),
    )


def test_wallet_initialization_seeds_from_scoreboard():
    player = "wallet-test-player"
    scoreboard = {"zen": 250}
    with main.transaction() as cur:
        _prepare_currency(cur)
        cur.execute("DELETE FROM accounts WHERE uuid=?", (player,))
        cur.execute("DELETE FROM players WHERE uuid=?", (player,))
        updated = main.maybe_initialize_wallet(cur, player, scoreboard)
        assert updated is True
        assert main.get_balance(cur, player, "zen") == 250
        row = cur.execute(
            "SELECT wallet_initialized FROM players WHERE uuid=?",
            (player,),
        ).fetchone()
        assert row is not None and row["wallet_initialized"] == 1


def test_wallet_initialization_idempotent():
    player = "wallet-test-repeat"
    with main.transaction() as cur:
        _prepare_currency(cur)
        cur.execute("DELETE FROM accounts WHERE uuid=?", (player,))
        cur.execute("DELETE FROM players WHERE uuid=?", (player,))
        main.maybe_initialize_wallet(cur, player, {"zen": 400})
        main.maybe_initialize_wallet(cur, player, {"zen": 0})
        assert main.get_balance(cur, player, "zen") == 400
        row = cur.execute(
            "SELECT wallet_initialized FROM players WHERE uuid=?",
            (player,),
        ).fetchone()
        assert row is not None and row["wallet_initialized"] == 1
