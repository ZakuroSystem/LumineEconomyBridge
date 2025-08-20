from fastapi import FastAPI
from pydantic import BaseModel
from typing import Dict, Optional, List
import sqlite3
import json
from contextlib import closing, contextmanager
import yaml
import os
import time
import shutil
import threading
import asyncio

# SQLite persistence
conn = sqlite3.connect(
    "economy.db", check_same_thread=False, isolation_level=None
)
conn.row_factory = sqlite3.Row
conn.execute("PRAGMA journal_mode=WAL")
conn.execute("PRAGMA synchronous=NORMAL")
with conn:
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS accounts (
            uuid TEXT NOT NULL,
            currency TEXT NOT NULL,
            balance INTEGER NOT NULL,
            PRIMARY KEY(uuid, currency)
        )
        """
    )
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS transactions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            timestamp INTEGER NOT NULL,
            from_account TEXT,
            to_account TEXT,
            currency TEXT NOT NULL,
            amount INTEGER NOT NULL,
            reason TEXT NOT NULL
        )
        """
    )
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS currencies (
            name TEXT PRIMARY KEY,
            symbol TEXT
        )
        """
    )
    # add symbol column if upgrading from old schema
    try:
        conn.execute("ALTER TABLE currencies ADD COLUMN symbol TEXT")
    except sqlite3.OperationalError:
        pass
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS name_index (
            name TEXT PRIMARY KEY,
            uuid TEXT NOT NULL
        )
        """
    )

app = FastAPI()

db_lock = threading.Lock()


@contextmanager
def transaction():
    with db_lock:
        conn.execute("BEGIN IMMEDIATE")
        cur = conn.cursor()
        try:
            yield cur
            conn.commit()
        except Exception:
            conn.rollback()
            raise
        finally:
            cur.close()


with open("lang.yml", encoding="utf-8") as f:
    LANG = yaml.safe_load(f)


def t(key: str, **kwargs) -> str:
    template = LANG
    for part in key.split('.'):  # nested lookup
        template = template.get(part, key)
    if not isinstance(template, str):
        template = key
    return template.format(**kwargs)


class Location(BaseModel):
    world: str
    x: float
    y: float
    z: float


class MessagePayload(BaseModel):
    player: str
    executor: str
    command: str
    timestamp: int
    location: Location


class DeltaPayload(BaseModel):
    player: str
    delta: Dict[str, int]
    timestamp: int


class RewritePayload(BaseModel):
    player: str
    scoreboard: Dict[str, int]
    timestamp: int


def ensure_currency(cur: sqlite3.Cursor, currency: str, symbol: Optional[str] = None) -> None:
    cur.execute(
        "INSERT OR IGNORE INTO currencies(name, symbol) VALUES (?,?)",
        (currency, symbol or currency),
    )
    if symbol:
        cur.execute("UPDATE currencies SET symbol=? WHERE name=?", (symbol, currency))


def get_uuid(name: str) -> Optional[str]:
    with closing(conn.cursor()) as cur:
        row = cur.execute("SELECT uuid FROM name_index WHERE name=?", (name.lower(),)).fetchone()
        return row["uuid"] if row else None


def get_name(uuid: str) -> Optional[str]:
    with closing(conn.cursor()) as cur:
        row = cur.execute("SELECT name FROM name_index WHERE uuid=? LIMIT 1", (uuid,)).fetchone()
        return row["name"] if row else None


def get_balance(cur: sqlite3.Cursor, uuid: str, currency: str) -> int:
    row = cur.execute(
        "SELECT balance FROM accounts WHERE uuid=? AND currency=?", (uuid, currency)
    ).fetchone()
    return row["balance"] if row else 0


def set_balance(cur: sqlite3.Cursor, uuid: str, currency: str, amount: int) -> None:
    cur.execute(
        """
        INSERT INTO accounts(uuid, currency, balance) VALUES (?,?,?)
        ON CONFLICT(uuid,currency) DO UPDATE SET balance=excluded.balance
        """,
        (uuid, currency, amount),
    )


def add_balance(cur: sqlite3.Cursor, uuid: str, currency: str, delta: int) -> bool:
    bal = get_balance(cur, uuid, currency)
    new_bal = bal + delta
    if new_bal < 0:
        return False
    set_balance(cur, uuid, currency, new_bal)
    return True


def transfer(cur: sqlite3.Cursor, src: str, dst: str, currency: str, amount: int) -> bool:
    if amount <= 0:
        return False
    if not add_balance(cur, src, currency, -amount):
        return False
    add_balance(cur, dst, currency, amount)
    return True


def list_balances(cur: sqlite3.Cursor, uuid: str) -> Dict[str, int]:
    rows = cur.execute(
        "SELECT currency, balance FROM accounts WHERE uuid=?", (uuid,)
    ).fetchall()
    return {r["currency"]: r["balance"] for r in rows}


def get_scoreboard(cur: sqlite3.Cursor, uuid: str) -> Dict[str, int]:
    balances = list_balances(cur, uuid)
    return {k: v for k, v in balances.items() if k in ("currency1", "currency2")}


def format_amount(cur: sqlite3.Cursor, amount: int, currency: str) -> str:
    row = cur.execute("SELECT symbol FROM currencies WHERE name=?", (currency,)).fetchone()
    symbol = row["symbol"] if row and row["symbol"] else currency
    return f"{symbol}{amount:,}"


LOG_PATH = "economy_commands.log"


BACKUP_DIR = "backups"
os.makedirs(BACKUP_DIR, exist_ok=True)


def backup_db() -> str:
    ts = time.strftime("%Y%m%d%H%M%S")
    dest = os.path.join(BACKUP_DIR, f"economy-{ts}.db")
    with db_lock:
        dest_conn = sqlite3.connect(dest)
        with dest_conn:
            conn.backup(dest_conn)
        dest_conn.close()
    return dest


def restore_db(path: str) -> None:
    global conn
    with db_lock:
        conn.close()
        shutil.copy2(path, "economy.db")
        conn = sqlite3.connect(
            "economy.db", check_same_thread=False, isolation_level=None
        )
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA synchronous=NORMAL")


async def auto_backup_loop():
    while True:
        await asyncio.sleep(3600)
        backup_db()


@app.on_event("startup")
async def startup_event():
    asyncio.create_task(auto_backup_loop())


def log_command(payload: MessagePayload, success: bool, error: Optional[str] = None) -> None:
    entry = {
        "timestamp": payload.timestamp,
        "executor": payload.executor,
        "command": payload.command,
        "world": payload.location.world,
        "x": payload.location.x,
        "y": payload.location.y,
        "z": payload.location.z,
        "success": success,
    }
    if not success and error:
        entry["error"] = error
    with open(LOG_PATH, "a", encoding="utf-8") as f:
        f.write(json.dumps(entry, ensure_ascii=False) + "\n")


def record_transaction(
    cur: sqlite3.Cursor,
    timestamp: int,
    from_account: Optional[str],
    to_account: Optional[str],
    currency: str,
    amount: int,
    reason: str,
) -> None:
    cur.execute(
        """
        INSERT INTO transactions(timestamp, from_account, to_account, currency, amount, reason)
        VALUES (?,?,?,?,?,?)
        """,
        (timestamp, from_account, to_account, currency, amount, reason),
    )


@app.get("/api/config")
async def get_config():
    return {"timeout": 2000, "sync_interval": 10}


@app.post("/api/message")
async def message(payload: MessagePayload):
    cmd = payload.command.lstrip("/").split()
    action = cmd[0].lower() if cmd else ""
    messages: List[Dict[str, str]] = []
    scoreboard = None
    success = True
    error_text = None

    def parse_amount(index: int) -> Optional[int]:
        if len(cmd) <= index:
            return None
        try:
            amt = int(cmd[index])
        except ValueError:
            return None
        return amt if amt > 0 else None

    def parse_amount_any(index: int) -> Optional[int]:
        if len(cmd) <= index:
            return None
        try:
            return int(cmd[index])
        except ValueError:
            return None

    if action == "backup":
        path = backup_db()
        messages.append({"target": "chat", "text": t("backup.created", file=os.path.basename(path))})
    elif action == "restore" and len(cmd) >= 2:
        file = os.path.join(BACKUP_DIR, cmd[1])
        try:
            restore_db(file)
            messages.append({"target": "chat", "text": t("restore.done")})
        except FileNotFoundError:
            success = False
            error_text = t("error.no_backup")
    else:
        with transaction() as cur:
            cur.execute(
                "INSERT OR REPLACE INTO name_index(name, uuid) VALUES (?, ?)",
                (payload.executor.lower(), payload.player),
            )

            exec_uuid = payload.player

            if not cmd:
                success = False
                error_text = t("error.no_command")
            elif action == "currency" and len(cmd) >= 3 and cmd[1].lower() == "create":
                cname = cmd[2]
                symbol = cmd[3] if len(cmd) >= 4 else None
                ensure_currency(cur, cname, symbol)
                messages.append({"target": "chat", "text": t("currency.create", currency=cname)})
            elif action == "money" and len(cmd) >= 2:
                sub = cmd[1].lower()
                if sub in {"give", "take"} and len(cmd) >= 5:
                    target_name = cmd[2].lower()
                    currency = cmd[3]
                    amt = parse_amount(4)
                    target_uuid = get_uuid(target_name)
                    if amt is None or target_uuid is None:
                        success = False
                        error_text = t("error.invalid_args")
                    else:
                        ensure_currency(cur, currency)
                        delta = amt if sub == "give" else -amt
                        if not add_balance(cur, target_uuid, currency, delta):
                            success = False
                            error_text = t("error.insufficient")
                        else:
                            messages.append({
                                "target": "chat",
                                "text": t(
                                    f"money.{sub}",
                                    target=target_name,
                                    currency=currency,
                                    amount=format_amount(cur, amt, currency),
                                ),
                            })
                            record_transaction(
                                cur,
                                payload.timestamp,
                                None if sub == "give" else target_uuid,
                                target_uuid if sub == "give" else None,
                                currency,
                                amt,
                                "mint" if sub == "give" else "burn",
                            )
                            if target_uuid == exec_uuid:
                                scoreboard = get_scoreboard(cur, exec_uuid)
                elif sub == "pay" and len(cmd) >= 6:
                    src_name = cmd[2].lower()
                    dst_name = cmd[3].lower()
                    currency = cmd[4]
                    amt = parse_amount(5)
                    src_uuid = get_uuid(src_name)
                    dst_uuid = get_uuid(dst_name)
                    if (
                        amt is None
                        or src_uuid is None
                        or dst_uuid is None
                        or not transfer(cur, src_uuid, dst_uuid, currency, amt)
                    ):
                        success = False
                        error_text = t("error.pay_failed")
                    else:
                        ensure_currency(cur, currency)
                        messages.append({
                            "target": "chat",
                            "text": t(
                                "money.pay",
                                src=src_name,
                                dst=dst_name,
                                amount=format_amount(cur, amt, currency),
                            ),
                        })
                        record_transaction(
                            cur,
                            payload.timestamp,
                            src_uuid,
                            dst_uuid,
                            currency,
                            amt,
                            "pay",
                        )
                        if src_uuid == exec_uuid or dst_uuid == exec_uuid:
                            scoreboard = get_scoreboard(cur, exec_uuid)
                else:
                    success = False
                    error_text = t("error.invalid_args")
            elif action in {"deposit", "withdraw"} and len(cmd) >= 5:
                src_name = cmd[1].lower()
                dst_name = cmd[2].lower()
                currency = cmd[3]
                amt = parse_amount(4)
                src_uuid = get_uuid(src_name)
                dst_uuid = get_uuid(dst_name)
                if (
                    amt is None
                    or src_uuid is None
                    or dst_uuid is None
                ):
                    success = False
                    error_text = t("error.invalid_args")
                else:
                    ensure_currency(cur, currency)
                    ok = transfer(
                        cur,
                        src_uuid if action == "deposit" else dst_uuid,
                        dst_uuid if action == "deposit" else src_uuid,
                        currency,
                        amt,
                    )
                    if not ok:
                        success = False
                        error_text = t("error.insufficient")
                    else:
                        verb_key = "deposit" if action == "deposit" else "withdraw"
                        messages.append({
                            "target": "chat",
                            "text": t(
                                verb_key,
                                src=src_name,
                                dst=dst_name,
                                amount=format_amount(cur, amt, currency),
                            ),
                        })
                        record_transaction(
                            cur,
                            payload.timestamp,
                            src_uuid if action == "deposit" else dst_uuid,
                            dst_uuid if action == "deposit" else src_uuid,
                            currency,
                            amt,
                            action,
                        )
                        if exec_uuid in {src_uuid, dst_uuid}:
                            scoreboard = get_scoreboard(cur, exec_uuid)
            elif action == "balance":
                currency = cmd[1] if len(cmd) >= 2 else None
                ensure_currency(cur, currency) if currency else None
                if currency:
                    bal = get_balance(cur, exec_uuid, currency)
                    messages.append({
                        "target": "chat",
                        "text": t(
                            "balance.single",
                            currency=currency,
                            amount=format_amount(cur, bal, currency),
                        ),
                    })
                else:
                    bals = list_balances(cur, exec_uuid)
                    if bals:
                        balances = ", ".join(
                            f"{k}={format_amount(cur, v, k)}" for k, v in bals.items()
                        )
                        messages.append({"target": "chat", "text": t("balance.all", balances=balances)})
                    else:
                        messages.append({"target": "chat", "text": t("balance.empty")})
                scoreboard = get_scoreboard(cur, exec_uuid)
            elif action == "setbalance" and len(cmd) >= 4:
                target_name = cmd[1].lower()
                currency = cmd[2]
                amt = parse_amount_any(3)
                target_uuid = get_uuid(target_name)
                if amt is None or target_uuid is None:
                    success = False
                    error_text = t("error.invalid_args")
                else:
                    ensure_currency(cur, currency)
                    old = get_balance(cur, target_uuid, currency)
                    set_balance(cur, target_uuid, currency, amt)
                    delta = amt - old
                    if delta != 0:
                        record_transaction(
                            cur,
                            payload.timestamp,
                            None,
                            target_uuid,
                            currency,
                            abs(delta),
                            "setbalance",
                        )
                    messages.append({
                        "target": "chat",
                        "text": t(
                            "setbalance",
                            target=target_name,
                            currency=currency,
                            amount=format_amount(cur, amt, currency),
                        ),
                    })
                    if target_uuid == exec_uuid:
                        scoreboard = get_scoreboard(cur, exec_uuid)
            elif action == "history" and len(cmd) >= 2:
                target_name = cmd[1].lower()
                target_uuid = get_uuid(target_name)
                limit = parse_amount_any(2) or 5
                if target_uuid is None:
                    success = False
                    error_text = t("error.invalid_args")
                else:
                    rows = cur.execute(
                        """
                        SELECT timestamp, from_account, to_account, currency, amount, reason
                        FROM transactions
                        WHERE from_account=? OR to_account=?
                        ORDER BY id DESC LIMIT ?
                        """,
                        (target_uuid, target_uuid, limit),
                    ).fetchall()
                    messages.append({"target": "chat", "text": t("history.header", player=target_name)})
                    for r in rows:
                        src = get_name(r["from_account"]) if r["from_account"] else "-"
                        dst = get_name(r["to_account"]) if r["to_account"] else "-"
                        messages.append({
                            "target": "chat",
                            "text": t(
                                "history.entry",
                                time=r["timestamp"],
                                src=src,
                                dst=dst,
                                amount=format_amount(cur, r["amount"], r["currency"]),
                                currency=r["currency"],
                                reason=r["reason"],
                            ),
                        })
            else:
                messages.append({"target": "chat", "text": f"Echo: {payload.command}"})
                scoreboard = get_scoreboard(cur, exec_uuid)

    if success:
        res = {"status": "success", "messages": messages}
        if scoreboard is not None:
            res["scoreboard"] = scoreboard
    else:
        res = {
            "status": "error",
            "messages": [{"target": "chat", "text": error_text or ""}]}

    log_command(payload, success, error_text)
    return res


@app.post("/api/sync")
async def sync(payload: DeltaPayload):
    with transaction() as cur:
        for k, v in payload.delta.items():
            ensure_currency(cur, k)
            add_balance(cur, payload.player, k, v)
    return {"status": "success"}


@app.post("/api/rewrite")
async def rewrite(payload: RewritePayload):
    with transaction() as cur:
        for k, v in payload.scoreboard.items():
            ensure_currency(cur, k)
            set_balance(cur, payload.player, k, v)
    return {"status": "success"}
