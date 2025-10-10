import asyncio
import time
from pathlib import Path
import sys

BACKEND_DIR = Path(__file__).resolve().parents[1] / "backend"
sys.path.append(str(BACKEND_DIR))

import main  # noqa: E402


def _prepare_currency(cur, currency: str = "zen"):
    main.ensure_currency(cur, currency, None)
    cur.execute(
        "INSERT OR REPLACE INTO settings(key, value) VALUES('default_currency', ?)",
        (currency,),
    )


def test_api_pay_reference_persisted():
    currency = "zen"
    src_name = "api-runner"
    dst_name = "api-target"
    src_uuid = "api-src-uuid"
    dst_uuid = "api-dst-uuid"
    reference = "order-xyz"
    amount_tokens = "10"
    timestamp = int(time.time())

    with main.transaction() as cur:
        _prepare_currency(cur, currency)
        cur.execute("DELETE FROM transactions WHERE from_account=? OR to_account=?", (src_uuid, src_uuid))
        cur.execute("DELETE FROM transactions WHERE from_account=? OR to_account=?", (dst_uuid, dst_uuid))
        cur.execute("INSERT OR REPLACE INTO name_index(name, uuid) VALUES(?, ?)", (src_name, src_uuid))
        cur.execute("INSERT OR REPLACE INTO name_index(name, uuid) VALUES(?, ?)", (dst_name, dst_uuid))
        main.set_balance(cur, src_uuid, currency, main.AMOUNT_SCALE * 100)
        main.set_balance(cur, dst_uuid, currency, 0)

    payload = main.MessagePayload(
        player=src_uuid,
        executor=src_name,
        command=f"/pay {dst_name} {amount_tokens} {currency} {reference}",
        timestamp=timestamp,
        location=main.Location(world="world", x=0, y=64, z=0),
        scoreboard={},
    )

    result = asyncio.run(main.message(payload))
    assert result["status"] == "success"

    row = main.conn.execute(
        "SELECT reference, amount FROM transactions WHERE from_account=? AND to_account=? ORDER BY id DESC LIMIT 1",
        (src_uuid, dst_uuid),
    ).fetchone()
    assert row is not None
    assert row["reference"] == reference
    assert row["amount"] == int(amount_tokens) * main.AMOUNT_SCALE

    tax_row = main.conn.execute(
        "SELECT reference FROM transactions WHERE from_account=? AND reason='tax' ORDER BY id DESC LIMIT 1",
        (src_uuid,),
    ).fetchone()
    if tax_row is not None:
        assert tax_row["reference"] == reference
