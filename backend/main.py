from fastapi import FastAPI
from pydantic import BaseModel
from typing import Dict, Optional, List, Union
import sqlite3
import json
from contextlib import closing, contextmanager, asynccontextmanager
import yaml
import os
import time
import shutil
import threading
import asyncio
import secrets
import base64

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
            frozen INTEGER NOT NULL DEFAULT 0,
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
            symbol TEXT,
            description TEXT,
            active INTEGER NOT NULL DEFAULT 1
        )
        """
    )
    # schema upgrades
    try:
        conn.execute("ALTER TABLE accounts ADD COLUMN frozen INTEGER NOT NULL DEFAULT 0")
    except sqlite3.OperationalError:
        pass
    try:
        conn.execute("ALTER TABLE currencies ADD COLUMN symbol TEXT")
    except sqlite3.OperationalError:
        pass
    try:
        conn.execute("ALTER TABLE currencies ADD COLUMN description TEXT")
    except sqlite3.OperationalError:
        pass
    try:
        conn.execute("ALTER TABLE currencies ADD COLUMN active INTEGER NOT NULL DEFAULT 1")
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
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS players (
            uuid TEXT PRIMARY KEY,
            last_seen INTEGER NOT NULL
        )
        """
    )
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS settings (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        )
        """
    )
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS system_accounts (
            uuid TEXT PRIMARY KEY
        )
        """
    )
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS player_lang (
            uuid TEXT PRIMARY KEY,
            lang TEXT NOT NULL
        )
        """
    )
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS link_tokens (
            token TEXT PRIMARY KEY,
            uuid TEXT NOT NULL,
            expires INTEGER NOT NULL
        )
        """
    )

    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS shops (
            shop_id TEXT PRIMARY KEY,
            owner_uuid TEXT NOT NULL,
            status TEXT NOT NULL DEFAULT 'active',
            created_at INTEGER NOT NULL,
            last_activity_at INTEGER NOT NULL
        )
        """
    )
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS shop_locations (
            shop_id TEXT NOT NULL,
            world TEXT NOT NULL,
            x REAL NOT NULL,
            y REAL NOT NULL,
            z REAL NOT NULL,
            PRIMARY KEY(shop_id, world, x, y, z),
            FOREIGN KEY(shop_id) REFERENCES shops(shop_id)
        )
        """
    )
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS shop_items (
            item_key TEXT PRIMARY KEY,
            material TEXT NOT NULL,
            display_name TEXT,
            nbt_blob BLOB NOT NULL
        )
        """
    )
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS shop_stock (
            shop_id TEXT NOT NULL,
            item_key TEXT NOT NULL,
            stock INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            PRIMARY KEY(shop_id, item_key),
            FOREIGN KEY(shop_id) REFERENCES shops(shop_id),
            FOREIGN KEY(item_key) REFERENCES shop_items(item_key)
        )
        """
    )
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS shop_prices (
            shop_id TEXT NOT NULL,
            item_key TEXT NOT NULL,
            currency TEXT NOT NULL,
            price INTEGER NOT NULL,
            PRIMARY KEY(shop_id, item_key, currency),
            FOREIGN KEY(shop_id) REFERENCES shops(shop_id),
            FOREIGN KEY(item_key) REFERENCES shop_items(item_key)
        )
        """
    )
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS shop_tx (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            shop_id TEXT NOT NULL,
            buyer_uuid TEXT NOT NULL,
            item_key TEXT NOT NULL,
            qty INTEGER NOT NULL,
            currency TEXT NOT NULL,
            total_price INTEGER NOT NULL,
            timestamp INTEGER NOT NULL,
            result TEXT NOT NULL,
            reason TEXT
        )
        """
    )

app = FastAPI()

db_lock = threading.Lock()

undo_stacks: Dict[str, List[List[Dict[str, Union[str, int, None]]]]] = {}
redo_stack: Dict[str, Optional[List[Dict[str, Union[str, int, None]]]]] = {}

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


def t(key: str, *, lang: str = "en", **kwargs) -> str:
    template = LANG.get(lang, {})
    for part in key.split('.'):  # nested lookup
        if isinstance(template, dict):
            template = template.get(part, key)
        else:
            template = key
            break
    if not isinstance(template, str):
        template = key
    return template.format(**kwargs)


def get_lang(uuid: str) -> str:
    row = conn.execute("SELECT lang FROM player_lang WHERE uuid=?", (uuid,)).fetchone()
    return row["lang"] if row else "en"


def set_lang(uuid: str, lang: str) -> None:
    with transaction() as cur:
        cur.execute(
            "INSERT OR REPLACE INTO player_lang(uuid, lang) VALUES (?, ?)",
            (uuid, lang),
        )


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


class ShopPlacePayload(BaseModel):
    shop_id: str
    owner_uuid: str
    world: str
    x: float
    y: float
    z: float
    timestamp: int


class ShopBuyPayload(BaseModel):
    player_uuid: str
    shop_id: str
    item_key: str
    qty: int
    currency: str
    timestamp: int


def ensure_currency(cur: sqlite3.Cursor, currency: str, symbol: Optional[str] = None) -> None:
    cur.execute(
        "INSERT OR IGNORE INTO currencies(name, symbol) VALUES (?,?)",
        (currency, symbol),
    )
    if symbol is not None:
        cur.execute("UPDATE currencies SET symbol=? WHERE name=?", (symbol, currency))


def resolve_currency(cur: sqlite3.Cursor, token: str) -> str:
    row = cur.execute(
        "SELECT name FROM currencies WHERE LOWER(name)=LOWER(?) OR LOWER(symbol)=LOWER(?)",
        (token, token),
    ).fetchone()
    return row["name"] if row else token


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


def is_frozen(cur: sqlite3.Cursor, uuid: str, currency: str) -> bool:
    row = cur.execute(
        "SELECT frozen FROM accounts WHERE uuid=? AND currency=?",
        (uuid, currency),
    ).fetchone()
    return bool(row and row["frozen"])


def set_balance(cur: sqlite3.Cursor, uuid: str, currency: str, amount: int) -> None:
    cur.execute(
        """
        INSERT INTO accounts(uuid, currency, balance) VALUES (?,?,?)
        ON CONFLICT(uuid,currency) DO UPDATE SET balance=excluded.balance
        """,
        (uuid, currency, amount),
    )


def add_balance(cur: sqlite3.Cursor, uuid: str, currency: str, delta: int) -> bool:
    row = cur.execute(
        "SELECT balance, frozen FROM accounts WHERE uuid=? AND currency=?",
        (uuid, currency),
    ).fetchone()
    bal = row["balance"] if row else 0
    if row and row["frozen"]:
        return False
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
    symbol = ""
    if row and row["symbol"] and row["symbol"] != currency:
        symbol = row["symbol"]
    return f"§e{symbol}{amount:,}§r"


LOG_PATH = "economy_commands.log"


BACKUP_DIR = "backups"
os.makedirs(BACKUP_DIR, exist_ok=True)


def get_setting(key: str, default: int) -> int:
    row = conn.execute("SELECT value FROM settings WHERE key=?", (key,)).fetchone()
    return int(row["value"]) if row else default


def set_setting(key: str, value: int) -> None:
    with transaction() as cur:
        cur.execute(
            "INSERT INTO settings(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
            (key, str(value)),
        )


def list_backups() -> List[str]:
    files = [
        os.path.join(BACKUP_DIR, f)
        for f in os.listdir(BACKUP_DIR)
        if f.endswith(".db")
    ]
    files.sort(reverse=True)
    return files


def trim_backups(keep: int) -> None:
    for path in list_backups()[keep:]:
        os.remove(path)


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
        interval = get_setting("auto_backup_interval", 3600)
        await asyncio.sleep(interval)
        backup_db()
        trim_backups(get_setting("auto_backup_keep", 10))
@asynccontextmanager
async def lifespan(app: FastAPI):
    asyncio.create_task(auto_backup_loop())
    yield

app.router.lifespan_context = lifespan


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


def push_undo(executor: str, actions: List[Dict[str, Optional[str]]]) -> None:
    stack = undo_stacks.setdefault(executor, [])
    stack.append(actions)
    if len(stack) > 5:
        stack.pop(0)
    redo_stack[executor] = None


@app.get("/api/config")
async def get_config():
    return {"timeout": 2000, "sync_interval": 10}


@app.post("/api/message")
async def message(payload: MessagePayload):
    cmd = payload.command.lstrip("/").split()
    action = cmd[0].lower() if cmd else ""
    messages: List[Dict[str, str]] = []
    scoreboards: Dict[str, Dict[str, int]] = {}
    success = True
    error_text = None
    exec_lang = get_lang(payload.player)

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

    if action == "lang":
        if len(cmd) >= 2 and cmd[1] in {"en", "jp"}:
            set_lang(payload.player, cmd[1])
            exec_lang = cmd[1]
            messages.append({"target": "chat", "text": t("lang.set", lang=exec_lang, code=cmd[1])})
        else:
            success = False
            error_text = t("lang.invalid", lang=exec_lang)
    elif action == "backup":
        path = backup_db()
        trim_backups(get_setting("auto_backup_keep", 10))
        messages.append({"target": "chat", "text": t("backup.created", lang=exec_lang, file=os.path.basename(path))})
    elif action == "restore" and len(cmd) >= 2:
        file = os.path.join(BACKUP_DIR, cmd[1])
        try:
            restore_db(file)
            messages.append({"target": "chat", "text": t("restore.done", lang=exec_lang)})
        except FileNotFoundError:
            success = False
            error_text = t("error.no_backup", lang=exec_lang)
    elif action == "webtoken":
        token = secrets.token_hex(4)
        expires = payload.timestamp + 600
        with transaction() as cur:
            cur.execute(
                "INSERT OR REPLACE INTO link_tokens(token, uuid, expires) VALUES(?,?,?)",
                (token, payload.player, expires),
            )
        messages.append({"target": "chat", "text": t("web.token", lang=exec_lang, token=token)})
    else:
        with transaction() as cur:
            cur.execute(
                "INSERT OR REPLACE INTO name_index(name, uuid) VALUES (?, ?)",
                (payload.executor.lower(), payload.player),
            )
            cur.execute(
                "INSERT OR REPLACE INTO players(uuid, last_seen) VALUES (?, ?)",
                (payload.player, payload.timestamp),
            )

            exec_uuid = payload.player
            actions: List[Dict[str, Optional[str]]] = []

            if not cmd:
                success = False
                error_text = t("error.no_command", lang=exec_lang)
            elif action == "currency":
                sub = cmd[1].lower() if len(cmd) >= 2 else ""
                if sub == "create" and len(cmd) >= 3:
                    cname = cmd[2]
                    symbol = cmd[3] if len(cmd) >= 4 else None
                    ensure_currency(cur, cname, symbol)
                    messages.append({"target": "chat", "text": t("currency.create", lang=exec_lang, currency=cname)})
                elif sub == "supply":
                    if len(cmd) >= 3:
                        cname = resolve_currency(cur, cmd[2])
                        total = cur.execute(
                            "SELECT COALESCE(SUM(balance),0) AS total FROM accounts WHERE currency=?",
                            (cname,),
                        ).fetchone()["total"]
                        messages.append(
                            {
                                "target": "chat",
                                "text": t(
                                    "currency.supply_entry",
                                    lang=exec_lang,
                                    currency=cname,
                                    amount=format_amount(cur, total, cname),
                                ),
                            }
                        )
                    else:
                        rows = cur.execute(
                            "SELECT currency, SUM(balance) AS total FROM accounts GROUP BY currency"
                        ).fetchall()
                        messages.append({"target": "chat", "text": t("currency.supply_header", lang=exec_lang)})
                        for r in rows:
                            messages.append(
                                {
                                    "target": "chat",
                                    "text": t(
                                        "currency.supply_entry",
                                        lang=exec_lang,
                                        currency=r["currency"],
                                        amount=format_amount(cur, r["total"], r["currency"]),
                                    ),
                                }
                            )
                else:
                    success = False
                    error_text = t("error.invalid_args", lang=exec_lang)
            elif action == "money" and len(cmd) >= 2:
                sub = cmd[1].lower()
                if sub in {"give", "take"} and len(cmd) >= 5:
                    target_name = cmd[2].lower()
                    currency = resolve_currency(cur, cmd[3])
                    amt = parse_amount(4)
                    target_uuid = get_uuid(target_name)
                    if amt is None or target_uuid is None:
                        success = False
                        error_text = t("error.invalid_args", lang=exec_lang)
                    else:
                        ensure_currency(cur, currency)
                        if is_frozen(cur, target_uuid, currency):
                            success = False
                            error_text = t("error.frozen", lang=exec_lang)
                        else:
                            delta = amt if sub == "give" else -amt
                            if not add_balance(cur, target_uuid, currency, delta):
                                success = False
                                error_text = t("error.insufficient", lang=exec_lang)
                            else:
                                messages.append({
                                    "target": "chat",
                                    "text": t(
                                        f"money.{sub}",
                                        lang=exec_lang,
                                        target=target_name,
                                        currency=currency,
                                        amount=format_amount(cur, amt, currency),
                                    ),
                                })
                                if sub == "give":
                                    messages.append(
                                        {
                                            "target": "chat",
                                            "player": target_uuid,
                                            "text": t(
                                                "receive",
                                                lang=get_lang(target_uuid),
                                                src=payload.executor,
                                                amount=format_amount(cur, amt, currency),
                                            ),
                                        }
                                    )
                                record_transaction(
                                    cur,
                                    payload.timestamp,
                                    None if sub == "give" else target_uuid,
                                    target_uuid if sub == "give" else None,
                                    currency,
                                    amt,
                                    "mint" if sub == "give" else "burn",
                                )
                                actions.append({
                                    "src": None if sub == "give" else target_uuid,
                                    "dst": target_uuid if sub == "give" else None,
                                    "currency": currency,
                                    "amount": amt,
                                })
                                scoreboards[target_uuid] = get_scoreboard(cur, target_uuid)
                elif sub == "pay" and len(cmd) >= 6:
                    src_name = cmd[2].lower()
                    dst_name = cmd[3].lower()
                    currency = resolve_currency(cur, cmd[4])
                    amt = parse_amount(5)
                    src_uuid = get_uuid(src_name)
                    dst_uuid = get_uuid(dst_name)
                    if (
                        amt is None
                        or src_uuid is None
                        or dst_uuid is None
                    ):
                        success = False
                        error_text = t("error.invalid_args", lang=exec_lang)
                    else:
                        ensure_currency(cur, currency)
                        if is_frozen(cur, src_uuid, currency) or is_frozen(cur, dst_uuid, currency):
                            success = False
                            error_text = t("error.frozen", lang=exec_lang)
                        elif get_balance(cur, src_uuid, currency) < amt:
                            success = False
                            error_text = t("error.insufficient", lang=exec_lang)
                        elif not transfer(cur, src_uuid, dst_uuid, currency, amt):
                            success = False
                            error_text = t("error.pay_failed", lang=exec_lang)
                        else:
                            messages.append({
                                "target": "chat",
                                "text": t(
                                    "money.pay",
                                    lang=exec_lang,
                                    src=src_name,
                                    dst=dst_name,
                                    amount=format_amount(cur, amt, currency),
                                ),
                            })
                            messages.append(
                                {
                                    "target": "chat",
                                    "player": dst_uuid,
                                    "text": t(
                                        "receive",
                                        lang=get_lang(dst_uuid),
                                        src=src_name,
                                        amount=format_amount(cur, amt, currency),
                                    ),
                                }
                            )
                            record_transaction(
                                cur,
                                payload.timestamp,
                                src_uuid,
                                dst_uuid,
                                currency,
                                amt,
                                "pay",
                            )
                            actions.append({
                                "src": src_uuid,
                                "dst": dst_uuid,
                                "currency": currency,
                                "amount": amt,
                            })
                            scoreboards[src_uuid] = get_scoreboard(cur, src_uuid)
                            scoreboards[dst_uuid] = get_scoreboard(cur, dst_uuid)
                else:
                    success = False
                    error_text = t("error.invalid_args", lang=exec_lang)
            elif action in {"pay", "transfer"} and len(cmd) >= 5:
                src_name = cmd[1].lower()
                dst_name = cmd[2].lower()
                currency = resolve_currency(cur, cmd[3])
                amt = parse_amount(4)
                src_uuid = get_uuid(src_name)
                dst_uuid = get_uuid(dst_name)
                if (
                    amt is None
                    or src_uuid is None
                    or dst_uuid is None
                ):
                    success = False
                    error_text = t("error.invalid_args", lang=exec_lang)
                else:
                    ensure_currency(cur, currency)
                    if is_frozen(cur, src_uuid, currency) or is_frozen(cur, dst_uuid, currency):
                        success = False
                        error_text = t("error.frozen", lang=exec_lang)
                    elif get_balance(cur, src_uuid, currency) < amt:
                        success = False
                        error_text = t("error.insufficient", lang=exec_lang)
                    elif not transfer(cur, src_uuid, dst_uuid, currency, amt):
                        success = False
                        error_text = t("error.pay_failed", lang=exec_lang)
                    else:
                        msg_key = "money.pay" if action == "pay" else "transfer"
                        messages.append({
                            "target": "chat",
                            "text": t(
                                msg_key,
                                lang=exec_lang,
                                src=src_name,
                                dst=dst_name,
                                amount=format_amount(cur, amt, currency),
                            ),
                        })
                        messages.append(
                            {
                                "target": "chat",
                                "player": dst_uuid,
                                "text": t(
                                    "receive",
                                    lang=get_lang(dst_uuid),
                                    src=src_name,
                                    amount=format_amount(cur, amt, currency),
                                ),
                            }
                        )
                        record_transaction(
                            cur,
                            payload.timestamp,
                            src_uuid,
                            dst_uuid,
                            currency,
                            amt,
                            action if action == "pay" else "transfer",
                        )
                        actions.append({
                            "src": src_uuid,
                            "dst": dst_uuid,
                            "currency": currency,
                            "amount": amt,
                        })
                        scoreboards[src_uuid] = get_scoreboard(cur, src_uuid)
                        scoreboards[dst_uuid] = get_scoreboard(cur, dst_uuid)
            elif action in {"deposit", "withdraw"} and len(cmd) >= 5:
                src_name = cmd[1].lower()
                dst_name = cmd[2].lower()
                currency = resolve_currency(cur, cmd[3])
                amt = parse_amount(4)
                src_uuid = get_uuid(src_name)
                dst_uuid = get_uuid(dst_name)
                if (
                    amt is None
                    or src_uuid is None
                    or dst_uuid is None
                ):
                    success = False
                    error_text = t("error.invalid_args", lang=exec_lang)
                else:
                    ensure_currency(cur, currency)
                    if is_frozen(cur, src_uuid, currency) or is_frozen(cur, dst_uuid, currency):
                        success = False
                        error_text = t("error.frozen", lang=exec_lang)
                    else:
                        ok = transfer(cur, src_uuid, dst_uuid, currency, amt)
                        if not ok:
                            success = False
                            error_text = t("error.insufficient", lang=exec_lang)
                        else:
                            messages.append({
                                "target": "chat",
                                "text": t(
                                    action,
                                    lang=exec_lang,
                                src=src_name,
                                dst=dst_name,
                                amount=format_amount(cur, amt, currency),
                            ),
                        })
                        messages.append(
                            {
                                "target": "chat",
                                "player": dst_uuid,
                                "text": t(
                                    "receive",
                                    lang=get_lang(dst_uuid),
                                    src=src_name,
                                    amount=format_amount(cur, amt, currency),
                                ),
                            }
                        )
                        record_transaction(
                            cur,
                            payload.timestamp,
                            src_uuid,
                            dst_uuid,
                            currency,
                            amt,
                            action,
                        )
                        actions.append({
                            "src": src_uuid,
                            "dst": dst_uuid,
                            "currency": currency,
                            "amount": amt,
                        })
                        scoreboards[src_uuid] = get_scoreboard(cur, src_uuid)
                        scoreboards[dst_uuid] = get_scoreboard(cur, dst_uuid)
            elif action == "balance":
                currency = resolve_currency(cur, cmd[1]) if len(cmd) >= 2 else None
                ensure_currency(cur, currency) if currency else None
                if currency:
                    bal = get_balance(cur, exec_uuid, currency)
                    messages.append({
                        "target": "chat",
                        "text": t(
                            "balance.single",
                            lang=exec_lang,
                            currency=currency,
                            amount=format_amount(cur, bal, currency),
                        ),
                    })
                else:
                    bals = list_balances(cur, exec_uuid)
                    if bals:
                        balances = ", ".join(
                            f"§e{k}§7={format_amount(cur, v, k)}§a" for k, v in bals.items()
                        )
                        messages.append({"target": "chat", "text": t("balance.all", lang=exec_lang, balances=balances)})
                    else:
                        messages.append({"target": "chat", "text": t("balance.empty", lang=exec_lang)})
                scoreboards[exec_uuid] = get_scoreboard(cur, exec_uuid)
            elif action == "account" and len(cmd) >= 3 and cmd[1].lower() == "create":
                name = cmd[2].lower()
                cur.execute("INSERT OR IGNORE INTO system_accounts(uuid) VALUES (?)", (name,))
                cur.execute(
                    "INSERT OR REPLACE INTO name_index(name, uuid) VALUES (?,?)",
                    (name, name),
                )
                messages.append({"target": "chat", "text": t("account.created", lang=exec_lang, id=name)})
            elif action == "undo":
                stack = undo_stacks.get(exec_uuid)
                if stack:
                    last = stack.pop()
                    redo_stack[exec_uuid] = last
                    for act in reversed(last):
                        ensure_currency(cur, act["currency"])
                        src, dst, curcode, amt = act["src"], act["dst"], act["currency"], act["amount"]
                        if src and dst:
                            transfer(cur, dst, src, curcode, amt)
                            record_transaction(cur, payload.timestamp, dst, src, curcode, amt, "undo")
                        elif src is None and dst:
                            add_balance(cur, dst, curcode, -amt)
                            record_transaction(cur, payload.timestamp, dst, None, curcode, amt, "undo")
                        elif dst is None and src:
                            add_balance(cur, src, curcode, amt)
                            record_transaction(cur, payload.timestamp, None, src, curcode, amt, "undo")
                        for u in filter(None, [src, dst]):
                            scoreboards[u] = get_scoreboard(cur, u)
                    messages.append({"target": "chat", "text": t("undo.done", lang=exec_lang)})
                else:
                    success = False
                    error_text = t("undo.none", lang=exec_lang)
            elif action == "redo":
                last = redo_stack.get(exec_uuid)
                if last:
                    for act in last:
                        ensure_currency(cur, act["currency"])
                        src, dst, curcode, amt = act["src"], act["dst"], act["currency"], act["amount"]
                        if src and dst:
                            transfer(cur, src, dst, curcode, amt)
                            record_transaction(cur, payload.timestamp, src, dst, curcode, amt, "redo")
                        elif src is None and dst:
                            add_balance(cur, dst, curcode, amt)
                            record_transaction(cur, payload.timestamp, None, dst, curcode, amt, "redo")
                        elif dst is None and src:
                            add_balance(cur, src, curcode, -amt)
                            record_transaction(cur, payload.timestamp, src, None, curcode, amt, "redo")
                        for u in filter(None, [src, dst]):
                            scoreboards[u] = get_scoreboard(cur, u)
                    push_undo(exec_uuid, last)
                    redo_stack[exec_uuid] = None
                    messages.append({"target": "chat", "text": t("redo.done", lang=exec_lang)})
                else:
                    success = False
                    error_text = t("redo.none", lang=exec_lang)
            elif action == "setbalance" and len(cmd) >= 4:
                target_name = cmd[1].lower()
                currency = resolve_currency(cur, cmd[2])
                amt = parse_amount_any(3)
                target_uuid = get_uuid(target_name)
                if amt is None or target_uuid is None:
                    success = False
                    error_text = t("error.invalid_args", lang=exec_lang)
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
                        actions.append({
                            "src": target_uuid if delta < 0 else None,
                            "dst": target_uuid if delta > 0 else None,
                            "currency": currency,
                            "amount": abs(delta),
                        })
                    messages.append({
                        "target": "chat",
                        "text": t(
                            "setbalance",
                            lang=exec_lang,
                            target=target_name,
                            currency=currency,
                            amount=format_amount(cur, amt, currency),
                        ),
                    })
                    scoreboards[target_uuid] = get_scoreboard(cur, target_uuid)
            elif action == "history" and len(cmd) >= 2:
                target_name = cmd[1].lower()
                target_uuid = get_uuid(target_name)
                limit = parse_amount_any(2) or 5
                if target_uuid is None:
                    success = False
                    error_text = t("error.invalid_args", lang=exec_lang)
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
                    messages.append({"target": "chat", "text": t("history.header", lang=exec_lang, player=target_name)})
                    for r in rows:
                        src = get_name(r["from_account"]) if r["from_account"] else "-"
                        dst = get_name(r["to_account"]) if r["to_account"] else "-"
                        messages.append({
                            "target": "chat",
                            "text": t(
                                "history.entry",
                                lang=exec_lang,
                                time=r["timestamp"],
                                src=src,
                                dst=dst,
                                amount=format_amount(cur, r["amount"], r["currency"]),
                                currency=r["currency"],
                                reason=r["reason"],
                            ),
                        })
            elif action == "help":
                help_cfg = LANG.get(exec_lang, {}).get("help", {})
                messages.append({"target": "chat", "text": help_cfg.get("header", "Available commands:")})
                for line in help_cfg.get("lines", []):
                    messages.append({"target": "chat", "text": line})
            else:
                success = False
                error_text = t("error.unknown_command", lang=exec_lang)
            if success and actions:
                push_undo(exec_uuid, actions)

    if success:
        res = {"status": "success", "messages": messages}
        if scoreboards:
            res["scoreboards"] = scoreboards
    else:
        err_msgs = [{"target": "chat", "text": error_text or ""}]
        if error_text in {
            t("error.invalid_args", lang=exec_lang),
            t("error.no_command", lang=exec_lang),
            t("error.unknown_command", lang=exec_lang),
        }:
            err_msgs.append({"target": "chat", "text": t("help.suggest", lang=exec_lang)})
        res = {"status": "error", "messages": err_msgs}

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
        cur.execute(
            "INSERT OR REPLACE INTO players(uuid, last_seen) VALUES (?, ?)",
            (payload.player, payload.timestamp),
        )
    msgs = []
    if get_lang(payload.player) == "en":
        msgs.append({
            "target": "chat",
            "text": t("lang.switch_hint", lang="jp"),
            "player": payload.player,
            "delay": 5,
        })
    return {"status": "success", "messages": msgs}


@app.post("/api/shop/place")
async def shop_place(payload: ShopPlacePayload):
    with transaction() as cur:
        cur.execute(
            "INSERT OR IGNORE INTO shops(shop_id, owner_uuid, status, created_at, last_activity_at) VALUES(?,?,?,?,?)",
            (payload.shop_id, payload.owner_uuid, "active", payload.timestamp, payload.timestamp),
        )
        cur.execute(
            "INSERT OR REPLACE INTO shop_locations(shop_id, world, x, y, z) VALUES(?,?,?,?,?)",
            (payload.shop_id, payload.world, payload.x, payload.y, payload.z),
        )
    return {"status": "ok"}


@app.get("/api/shop/items")
async def shop_items(shop_id: str):
    with transaction() as cur:
        srow = cur.execute("SELECT status FROM shops WHERE shop_id=?", (shop_id,)).fetchone()
        if not srow:
            return {"status": "error", "reason": "shop_not_found"}
        if srow["status"] != "active":
            return {"status": srow["status"]}
        rows = cur.execute(
            "SELECT st.item_key, st.stock, it.material, it.display_name, it.nbt_blob FROM shop_stock st JOIN shop_items it ON st.item_key=it.item_key WHERE st.shop_id=?",
            (shop_id,),
        ).fetchall()
        items = []
        for r in rows:
            price_rows = cur.execute(
                "SELECT currency, price FROM shop_prices WHERE shop_id=? AND item_key=?",
                (shop_id, r["item_key"]),
            ).fetchall()
            prices = {pr["currency"]: pr["price"] for pr in price_rows}
            items.append(
                {
                    "item_key": r["item_key"],
                    "material": r["material"],
                    "display_name": r["display_name"],
                    "nbt_blob": base64.b64encode(r["nbt_blob"]).decode("ascii"),
                    "stock": r["stock"],
                    "prices": prices,
                }
            )
        return {"status": "active", "items": items}


@app.post("/api/shop/buy")
async def shop_buy(payload: ShopBuyPayload):
    messages: List[Dict[str, str]] = []
    scoreboards: Dict[str, Dict[str, int]] = {}
    grant: List[Dict[str, str]] = []
    success = False
    reason: Optional[str] = None
    total_price = 0
    with transaction() as cur:
        shop = cur.execute(
            "SELECT owner_uuid, status FROM shops WHERE shop_id=?",
            (payload.shop_id,),
        ).fetchone()
        if not shop:
            reason = "shop_not_found"
        elif shop["status"] != "active":
            reason = "shop_suspended"
        else:
            owner = shop["owner_uuid"]
            stock_row = cur.execute(
                "SELECT stock FROM shop_stock WHERE shop_id=? AND item_key=?",
                (payload.shop_id, payload.item_key),
            ).fetchone()
            if not stock_row or stock_row["stock"] < payload.qty:
                reason = "insufficient_stock"
            else:
                price_row = cur.execute(
                    "SELECT price FROM shop_prices WHERE shop_id=? AND item_key=? AND currency=?",
                    (payload.shop_id, payload.item_key, payload.currency),
                ).fetchone()
                if not price_row:
                    reason = "invalid_currency"
                else:
                    total_price = price_row["price"] * payload.qty
                    if get_balance(cur, payload.player_uuid, payload.currency) < total_price:
                        reason = "insufficient_funds"
                    elif not transfer(
                        cur, payload.player_uuid, owner, payload.currency, total_price
                    ):
                        reason = "transfer_failed"
                    else:
                        cur.execute(
                            "UPDATE shop_stock SET stock=stock-? WHERE shop_id=? AND item_key=?",
                            (payload.qty, payload.shop_id, payload.item_key),
                        )
                        cur.execute(
                            "UPDATE shops SET last_activity_at=? WHERE shop_id=?",
                            (payload.timestamp, payload.shop_id),
                        )
                        cur.execute(
                            "INSERT INTO shop_tx(shop_id,buyer_uuid,item_key,qty,currency,total_price,timestamp,result) VALUES(?,?,?,?,?,?,?,?)",
                            (
                                payload.shop_id,
                                payload.player_uuid,
                                payload.item_key,
                                payload.qty,
                                payload.currency,
                                total_price,
                                payload.timestamp,
                                "success",
                            ),
                        )
                        success = True
                        messages.append(
                            {
                                "target": "chat",
                                "player": payload.player_uuid,
                                "text": f"Purchased x{payload.qty}",
                            }
                        )
                        messages.append(
                            {
                                "target": "chat",
                                "player": owner,
                                "text": f"Sold x{payload.qty}",
                            }
                        )
                        scoreboards[payload.player_uuid] = get_scoreboard(
                            cur, payload.player_uuid
                        )
                        scoreboards[owner] = get_scoreboard(cur, owner)
                        grant.append(
                            {
                                "item_key": payload.item_key,
                                "qty": payload.qty,
                                "grant_token": secrets.token_hex(8),
                            }
                        )
        if not success:
            cur.execute(
                "INSERT INTO shop_tx(shop_id,buyer_uuid,item_key,qty,currency,total_price,timestamp,result,reason) VALUES(?,?,?,?,?,?,?,?,?)",
                (
                    payload.shop_id,
                    payload.player_uuid,
                    payload.item_key,
                    payload.qty,
                    payload.currency,
                    total_price,
                    payload.timestamp,
                    "fail",
                    reason,
                ),
            )
            messages.append(
                {
                    "target": "chat",
                    "player": payload.player_uuid,
                    "text": f"Purchase failed: {reason}",
                }
            )
    log_entry = {
        "type": "shop_buy",
        "timestamp": payload.timestamp,
        "shop_id": payload.shop_id,
        "buyer": payload.player_uuid,
        "item_key": payload.item_key,
        "qty": payload.qty,
        "currency": payload.currency,
        "total_price": total_price,
        "result": "success" if success else "error",
        "reason": reason,
    }
    with open(LOG_PATH, "a", encoding="utf-8") as f:
        f.write(json.dumps(log_entry, ensure_ascii=False) + "\n")
    return {
        "status": "success" if success else "error",
        "messages": messages,
        "scoreboards": scoreboards,
        "grant": grant,
    }
