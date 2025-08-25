from fastapi import (
    FastAPI,
    Response,
    HTTPException,
    Header,
    Depends,
    Request,
    WebSocket,
    WebSocketDisconnect,
)
from pydantic import BaseModel
from typing import Dict, Optional, List, Union, Tuple, Any
from tile_store import TileStore
import sqlite3
import json
from contextlib import closing, contextmanager, asynccontextmanager, suppress
import yaml
import os
import time
import shutil
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
import threading
import asyncio
import secrets
import base64
import hashlib
import httpx
import re
from email.utils import parsedate_to_datetime, formatdate

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
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS settings (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
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
    conn.execute(
        "INSERT OR IGNORE INTO currencies(name, symbol) VALUES('thy', '\u00A5')"
    )
    conn.execute(
        "INSERT OR IGNORE INTO settings(key, value) VALUES('default_currency', 'thy')"
    )
    try:
        conn.execute("ALTER TABLE currencies ADD COLUMN description TEXT")
    except sqlite3.OperationalError:
        pass
    try:
        conn.execute("ALTER TABLE currencies ADD COLUMN active INTEGER NOT NULL DEFAULT 1")
    except sqlite3.OperationalError:
        pass
    try:
        conn.execute("ALTER TABLE currencies ADD COLUMN treasury TEXT")
    except sqlite3.OperationalError:
        pass
    try:
        conn.execute("ALTER TABLE currencies ADD COLUMN tax_rate INTEGER NOT NULL DEFAULT 0")
    except sqlite3.OperationalError:
        pass
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS currency_managers (
            currency TEXT NOT NULL,
            uuid TEXT NOT NULL,
            PRIMARY KEY(currency, uuid)
        )
        """
    )
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
            last_seen INTEGER NOT NULL,
            lang_hint INTEGER NOT NULL DEFAULT 0
        )
        """
    )
    try:
        conn.execute("ALTER TABLE players ADD COLUMN lang_hint INTEGER NOT NULL DEFAULT 0")
    except sqlite3.OperationalError:
        pass
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS system_accounts (
            uuid TEXT PRIMARY KEY
        )
        """
    )
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS account_links (
            user_uuid TEXT NOT NULL,
            system_uuid TEXT NOT NULL,
            PRIMARY KEY(user_uuid, system_uuid)
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
        CREATE TABLE IF NOT EXISTS admin_users (
            name TEXT PRIMARY KEY
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
            last_activity_at INTEGER NOT NULL,
            listed INTEGER NOT NULL DEFAULT 1
        )
        """
    )
    try:
        conn.execute("ALTER TABLE shops ADD COLUMN listed INTEGER NOT NULL DEFAULT 1")
    except sqlite3.OperationalError:
        pass
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
            sale_name TEXT NOT NULL,
            stock INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            PRIMARY KEY(shop_id, item_key),
            UNIQUE(shop_id, sale_name),
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
            client_tx_id TEXT UNIQUE,
            shop_id TEXT NOT NULL,
            buyer_uuid TEXT NOT NULL,
            item_key TEXT NOT NULL,
            qty INTEGER NOT NULL,
            currency TEXT NOT NULL,
            total_price INTEGER NOT NULL,
            timestamp INTEGER NOT NULL,
            result TEXT NOT NULL,
            reason TEXT,
            grant_token TEXT,
            world TEXT,
            x INTEGER,
            y INTEGER,
            z INTEGER,
            tx_type TEXT NOT NULL DEFAULT 'buy'
        )
        """
    )
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS shop_owners (
            shop_id TEXT NOT NULL,
            owner_uuid TEXT NOT NULL,
            PRIMARY KEY (shop_id, owner_uuid)
        )
        """
    )
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS shop_visits (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            shop_id TEXT NOT NULL,
            visitor_uuid TEXT NOT NULL,
            timestamp INTEGER NOT NULL
        )
        """
    )
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS sale_events (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            start_ts INTEGER NOT NULL,
            end_ts INTEGER NOT NULL,
            pct REAL NOT NULL,
            account TEXT NOT NULL,
            active INTEGER NOT NULL
        )
        """
    )
    try:
        conn.execute("ALTER TABLE shop_tx ADD COLUMN client_tx_id TEXT")
    except sqlite3.OperationalError:
        pass
    try:
        conn.execute("ALTER TABLE shop_tx ADD COLUMN grant_token TEXT")
    except sqlite3.OperationalError:
        pass
    for col, typ in [
        ("world", "TEXT"),
        ("x", "INTEGER"),
        ("y", "INTEGER"),
        ("z", "INTEGER"),
        ("tx_type", "TEXT NOT NULL DEFAULT 'buy'")
    ]:
        try:
            conn.execute(f"ALTER TABLE shop_tx ADD COLUMN {col} {typ}")
        except sqlite3.OperationalError:
            pass
    conn.execute(
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_shop_tx_client ON shop_tx(client_tx_id, tx_type)"
    )
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS pending_messages (
            uuid TEXT NOT NULL,
            payload TEXT NOT NULL
        )
        """
    )

app = FastAPI()
# allow the dashboard to access API endpoints when served from a different
# origin (e.g., Flask on another port)
from fastapi.middleware.cors import CORSMiddleware
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)
tile_store = TileStore("tiles")

# separate cash transaction persistence
cash_conn = sqlite3.connect(
    "cash_transaction.db", check_same_thread=False, isolation_level=None
)
cash_conn.row_factory = sqlite3.Row
with cash_conn:
    cash_conn.execute(
        """
        CREATE TABLE IF NOT EXISTS notes (
            note_id TEXT PRIMARY KEY,
            currency TEXT NOT NULL,
            amount INTEGER NOT NULL,
            owner_uuid TEXT NOT NULL
        )
        """
    )
    cash_conn.execute(
        """
        CREATE TABLE IF NOT EXISTS cash_events (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            note_id TEXT NOT NULL,
            player_uuid TEXT NOT NULL,
            action TEXT NOT NULL,
            currency TEXT NOT NULL,
            amount INTEGER NOT NULL,
            location TEXT,
            ts INTEGER NOT NULL
        )
        """
    )

SHARED_TOKEN = os.environ.get("LE_TOKEN", "devtoken")
MAPCOLOR_URL = os.environ.get("MAPCOLOR_URL", "http://127.0.0.1:8765")
RATE_LIMIT: Dict[str, Tuple[float, int]] = {}
RATE_LIMIT_MAX = 10


def verify_token(x_le_token: str = Header(...)) -> None:
    if SHARED_TOKEN and x_le_token != SHARED_TOKEN:
        raise HTTPException(status_code=401, detail="invalid token")


def check_rate_limit(ip: str) -> None:
    """Very small per-IP rate limiter for snapshot posts."""

    now = time.time()
    window_start, count = RATE_LIMIT.get(ip, (now, 0))
    if now - window_start >= 1:
        RATE_LIMIT[ip] = (now, 1)
        return
    if count >= RATE_LIMIT_MAX:
        raise HTTPException(status_code=429, detail="rate limit exceeded")
    RATE_LIMIT[ip] = (window_start, count + 1)


async def _tile_worker() -> None:
    """Background task processing dirty and queued tiles."""

    while True:
        tile_store.process_dirty()
        tile_store.process_queue()
        await asyncio.sleep(0.2)


_tile_worker_task: asyncio.Task | None = None


@app.on_event("startup")
async def _start_tile_worker() -> None:
    global _tile_worker_task
    _tile_worker_task = asyncio.create_task(_tile_worker())


@app.on_event("shutdown")
async def _stop_tile_worker() -> None:
    if _tile_worker_task:
        _tile_worker_task.cancel()
        with suppress(Exception):
            await _tile_worker_task

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
    placer_uuid: str
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
    client_tx_id: str


class ShopSellPayload(BaseModel):
    player_uuid: str
    shop_id: str
    item_key: str
    qty: int
    currency: str
    timestamp: int
    client_tx_id: str


class ShopAddStockPayload(BaseModel):
    owner_uuid: str
    shop_id: str
    nbt_blob: str
    material: str
    display_name: Optional[str]
    qty: int
    price: int
    sale_name: Optional[str] = None
    currency: Optional[str] = None


class ShopTakeStockPayload(BaseModel):
    owner_uuid: str
    shop_id: str
    item_key: str
    qty: int


class ShopSetPricePayload(BaseModel):
    owner_uuid: str
    shop_id: str
    item_key: Optional[str] = None
    sale_name: Optional[str] = None
    currency: str
    price: int


class ShopPingPayload(BaseModel):
    shop_id: str
    timestamp: int


class ShopReopenPayload(BaseModel):
    owner_uuid: str
    shop_id: str
    timestamp: int


class CashEvent(BaseModel):
    note_id: str
    player_uuid: str
    action: str
    currency: str
    amount: int
    location: Optional[str] = None


class ShopRemovePayload(BaseModel):
    shop_id: str
    refund: bool = False


class ShopRemoveItemPayload(BaseModel):
    owner_uuid: str
    shop_id: str
    sale_name: str
    refund: bool = False


class ShopAddOwnerPayload(BaseModel):
    owner_uuid: str
    shop_id: str
    target_uuid: str


class ShopRemoveOwnerPayload(BaseModel):
    owner_uuid: str
    shop_id: str
    target_uuid: str


class ShopVisitPayload(BaseModel):
    player_uuid: str
    shop_id: str
    timestamp: int


class ShopListingPayload(BaseModel):
    owner_uuid: str
    shop_id: str
    listed: bool


class AdminUserPayload(BaseModel):
    name: str


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


def is_currency(cur: sqlite3.Cursor, token: str) -> bool:
    return (
        cur.execute(
            "SELECT 1 FROM currencies WHERE LOWER(name)=LOWER(?) OR LOWER(symbol)=LOWER(?)",
            (token, token),
        ).fetchone()
        is not None
    )


def get_default_currency(cur: sqlite3.Cursor) -> str:
    row = cur.execute(
        "SELECT value FROM settings WHERE key='default_currency'"
    ).fetchone()
    return resolve_currency(cur, row["value"]) if row else "thy"


def get_uuid(name: str) -> Optional[str]:
    with closing(conn.cursor()) as cur:
        row = cur.execute("SELECT uuid FROM name_index WHERE name=?", (name.lower(),)).fetchone()
        return row["uuid"] if row else None


def get_name(uuid: str) -> Optional[str]:
    with closing(conn.cursor()) as cur:
        row = cur.execute("SELECT name FROM name_index WHERE uuid=? LIMIT 1", (uuid,)).fetchone()
        return row["name"] if row else None


def has_link(cur: sqlite3.Cursor, user_uuid: str, system_uuid: str) -> bool:
    return (
        cur.execute(
            "SELECT 1 FROM account_links WHERE user_uuid=? AND system_uuid=?",
            (user_uuid, system_uuid),
        ).fetchone()
        is not None
    )


def is_shop_owner(cur: sqlite3.Cursor, shop_id: str, uuid: str) -> bool:
    if cur.execute(
        "SELECT 1 FROM shop_owners WHERE shop_id=? AND owner_uuid=?",
        (shop_id, uuid),
    ).fetchone():
        return True
    row = cur.execute(
        "SELECT owner_uuid FROM shops WHERE shop_id=?",
        (shop_id,),
    ).fetchone()
    return bool(row and row["owner_uuid"] == uuid)


def get_balance(cur: sqlite3.Cursor, uuid: str, currency: str) -> int:
    cur.execute(
        "INSERT OR IGNORE INTO accounts(uuid, currency, balance) VALUES (?,?,0)",
        (uuid, currency),
    )
    row = cur.execute(
        "SELECT balance FROM accounts WHERE uuid=? AND currency=?",
        (uuid, currency),
    ).fetchone()
    return row["balance"] if row else 0


def is_frozen(cur: sqlite3.Cursor, uuid: str, currency: str) -> bool:
    row = cur.execute(
        "SELECT frozen FROM accounts WHERE uuid=? AND currency=?",
        (uuid, currency),
    ).fetchone()
    return bool(row and row["frozen"])


def is_currency_manager(cur: sqlite3.Cursor, uuid: str, currency: str) -> bool:
    return (
        cur.execute(
            "SELECT 1 FROM currency_managers WHERE currency=? AND uuid=?",
            (currency, uuid),
        ).fetchone()
        is not None
    )


def tax_info(cur: sqlite3.Cursor, currency: str) -> Tuple[int, Optional[str]]:
    row = cur.execute(
        "SELECT tax_rate, treasury FROM currencies WHERE name=?",
        (currency,),
    ).fetchone()
    if not row:
        return 0, None
    return row["tax_rate"], row["treasury"]


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
    if not rows:
        cur.execute(
            "INSERT OR IGNORE INTO accounts(uuid, currency, balance) VALUES (?,?,0)",
            (uuid, get_default_currency(cur)),
        )
        rows = cur.execute(
            "SELECT currency, balance FROM accounts WHERE uuid=?", (uuid,)
        ).fetchall()
    return {r["currency"]: r["balance"] for r in rows}


def get_scoreboard(cur: sqlite3.Cursor, uuid: str) -> Dict[str, int]:
    return list_balances(cur, uuid)


def format_amount(cur: sqlite3.Cursor, amount: int, currency: str) -> str:
    row = cur.execute("SELECT symbol FROM currencies WHERE name=?", (currency,)).fetchone()
    symbol = ""
    if row and row["symbol"] and row["symbol"] != currency:
        symbol = row["symbol"]
    sign = "-" if amount < 0 else ""
    amt = abs(amount)
    whole, frac = divmod(amt, 1000)
    if frac:
        return f"§e{sign}{symbol}{whole:,}.{frac:03d}§r"
    return f"§e{sign}{symbol}{whole:,}§r"


def is_online(cur: sqlite3.Cursor, uuid: str, now: int) -> bool:
    row = cur.execute("SELECT last_seen FROM players WHERE uuid=?", (uuid,)).fetchone()
    return bool(row and now - row["last_seen"] < 30)


def queue_message(cur: sqlite3.Cursor, msg: Dict[str, str]) -> None:
    safe = dict(msg)
    safe["text"] = sanitize_text(safe.get("text", ""))
    cur.execute(
        "INSERT INTO pending_messages(uuid, payload) VALUES(?, ?)",
        (safe.get("player"), json.dumps(safe, ensure_ascii=False)),
    )


LOG_PATH = "economy_commands.log"


# No escaping or color stripping so Java can render text exactly
def sanitize_text(text: str) -> str:
    return text


def append_log(entry: Dict[str, Union[str, int, float]]) -> None:
    safe_entry = {
        k: sanitize_text(v) if isinstance(v, str) else v
        for k, v in entry.items()
    }
    with open(LOG_PATH, "a", encoding="utf-8") as f:
        f.write(json.dumps(safe_entry, ensure_ascii=False) + "\n")


def sanitize_messages(msgs: List[Dict[str, str]]) -> None:
    for m in msgs:
        m["text"] = sanitize_text(m.get("text", ""))

# websocket broadcast of tile updates ---------------------------------------

WS_CLIENTS: List[WebSocket] = []


async def _broadcast_tile_update(world: str, tx: int, tz: int) -> None:
    msg = json.dumps({"world": world, "tx": tx, "tz": tz, "ts": int(time.time() * 1000)})
    for ws in list(WS_CLIENTS):
        try:
            await ws.send_text(msg)
        except Exception:
            try:
                await ws.close()
            except Exception:
                pass
            WS_CLIENTS.remove(ws)


def notify_tile_update(world: str, tx: int, tz: int) -> None:
    loop = asyncio.get_event_loop()
    loop.create_task(_broadcast_tile_update(world, tx, tz))


# attach helpers to tile store once defined
tile_store.log_fn = append_log
tile_store.broadcast_fn = notify_tile_update


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


async def suspend_loop():
    while True:
        await asyncio.sleep(3600)
        cutoff = int(time.time()) - 5 * 86400
        with transaction() as cur:
            cur.execute(
                "UPDATE shops SET status='suspended' WHERE status='active' AND last_activity_at<?",
                (cutoff,),
            )
@asynccontextmanager
async def lifespan(app: FastAPI):
    asyncio.create_task(auto_backup_loop())
    asyncio.create_task(suspend_loop())
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
    append_log(entry)


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


@app.get("/api/admin/list")
async def get_admin_list():
    rows = conn.execute("SELECT name FROM admin_users").fetchall()
    return {"admins": [r["name"] for r in rows]}


@app.post("/api/admin/add")
async def add_admin(payload: AdminUserPayload):
    with conn:
        conn.execute(
            "INSERT OR IGNORE INTO admin_users(name) VALUES(?)", (payload.name,)
        )
    return {"status": "success"}


@app.post("/api/admin/remove")
async def remove_admin(payload: AdminUserPayload):
    with conn:
        conn.execute("DELETE FROM admin_users WHERE name=?", (payload.name,))
    return {"status": "success"}


@app.get("/api/config")
async def get_config():
    return {"timeout": 2000, "sync_interval": 1}


@app.post("/api/message")
async def message(payload: MessagePayload):
    cmd = payload.command.lstrip("/").split()
    action = cmd[0].lower() if cmd else ""
    messages: List[Dict[str, str]] = []
    scoreboards: Dict[str, Dict[str, int]] = {}
    success = True
    error_text = None
    exec_lang = get_lang(payload.player)

    def parse_amount_token(token: str, base: Optional[int], positive_only: bool = True) -> Optional[int]:
        if token.endswith('%'):
            if base is None:
                return None
            try:
                pct = Decimal(token[:-1])
            except InvalidOperation:
                return None
            amt_dec = (Decimal(base) * pct) / Decimal(100)
        else:
            try:
                amt_dec = Decimal(token)
            except InvalidOperation:
                return None
            amt_dec *= 1000
        amt = int(amt_dec.to_integral_value(rounding=ROUND_HALF_UP))
        if positive_only and amt <= 0:
            return None
        return amt

    def parse_amount(index: int, base: Optional[int] = None) -> Optional[int]:
        if len(cmd) <= index:
            return None
        return parse_amount_token(cmd[index], base, True)

    def parse_amount_any(index: int, base: Optional[int] = None) -> Optional[int]:
        if len(cmd) <= index:
            return None
        return parse_amount_token(cmd[index], base, False)

    def extract_currency(start: int) -> Tuple[str, int, bool]:
        if len(cmd) > start and is_currency(cur, cmd[start]):
            return resolve_currency(cur, cmd[start]), start + 1, True
        return get_default_currency(cur), start, False

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
    elif action in {"webtoken", "weblink"}:
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
                "INSERT OR IGNORE INTO players(uuid, last_seen, lang_hint) VALUES (?, ?, 0)",
                (payload.player, payload.timestamp),
            )
            cur.execute(
                "UPDATE players SET last_seen=? WHERE uuid=?",
                (payload.timestamp, payload.player),
            )
            cur.execute(
                "INSERT OR IGNORE INTO accounts(uuid, currency, balance) VALUES (?,?,0)",
                (payload.player, get_default_currency(cur)),
            )

            exec_uuid = payload.player
            is_exec_admin = (
                cur.execute(
                    "SELECT 1 FROM admin_users WHERE name=?", (payload.executor.lower(),)
                ).fetchone()
                is not None
            )
            actions: List[Dict[str, Optional[str]]] = []

            if not cmd:
                success = False
                error_text = t("error.no_command", lang=exec_lang)
            elif action == "shop" and len(cmd) >= 2 and cmd[1].lower() == "quick":
                shop = cmd[2] if len(cmd) >= 3 else ""
                messages.append({"target": "chat", "text": t("shop.quick_created", lang=exec_lang, shop=shop)})
            elif action == "currency":
                sub = cmd[1].lower() if len(cmd) >= 2 else ""
                if sub == "create" and len(cmd) >= 3:
                    cname = cmd[2]
                    symbol = cmd[3] if len(cmd) >= 4 else None
                    ensure_currency(cur, cname, symbol)
                    messages.append({"target": "chat", "text": t("currency.create", lang=exec_lang, currency=cname)})
                elif sub == "default" and len(cmd) >= 3:
                    cname = resolve_currency(cur, cmd[2])
                    if not is_currency(cur, cname):
                        success = False
                        error_text = t("error.invalid_currency", lang=exec_lang)
                    else:
                        cur.execute(
                            "INSERT OR REPLACE INTO settings(key,value) VALUES('default_currency', ?)",
                            (cname,),
                        )
                        messages.append({"target": "chat", "text": t("currency.default", lang=exec_lang, currency=cname)})
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
                    if sub == "manager" and len(cmd) >= 5 and cmd[2] in {"add", "remove"}:
                        cname = resolve_currency(cur, cmd[3])
                        target_name = cmd[4].lower()
                        target_uuid = get_uuid(target_name)
                        if not is_exec_admin:
                            success = False
                            error_text = t("error.no_permission", lang=exec_lang)
                        elif target_uuid is None or not is_currency(cur, cname):
                            success = False
                            error_text = t("error.invalid_args", lang=exec_lang)
                        else:
                            if cmd[2] == "add":
                                cur.execute(
                                    "INSERT OR IGNORE INTO currency_managers(currency, uuid) VALUES(?,?)",
                                    (cname, target_uuid),
                                )
                                messages.append(
                                    {
                                        "target": "chat",
                                        "text": t(
                                            "currency.manager_add",
                                            lang=exec_lang,
                                            currency=cname,
                                            player=target_name,
                                        ),
                                    }
                                )
                            else:
                                cur.execute(
                                    "DELETE FROM currency_managers WHERE currency=? AND uuid=?",
                                    (cname, target_uuid),
                                )
                                messages.append(
                                    {
                                        "target": "chat",
                                        "text": t(
                                            "currency.manager_remove",
                                            lang=exec_lang,
                                            currency=cname,
                                            player=target_name,
                                        ),
                                    }
                                )
                    elif sub == "tax" and len(cmd) >= 4:
                        cname = resolve_currency(cur, cmd[2])
                        if not (
                            is_exec_admin
                            or is_currency_manager(cur, exec_uuid, cname)
                        ):
                            success = False
                            error_text = t("error.no_permission", lang=exec_lang)
                        else:
                            try:
                                rate_dec = Decimal(cmd[3])
                            except InvalidOperation:
                                success = False
                                error_text = t("error.invalid_args", lang=exec_lang)
                            else:
                                rate = int((rate_dec * 10).to_integral_value(rounding=ROUND_HALF_UP))
                                cur.execute(
                                    "UPDATE currencies SET tax_rate=? WHERE name=?",
                                    (rate, cname),
                                )
                                messages.append(
                                    {
                                        "target": "chat",
                                        "text": t(
                                            "currency.tax",
                                            lang=exec_lang,
                                            currency=cname,
                                            rate=f"{rate/10:.1f}",
                                        ),
                                    }
                                )
                    elif sub == "treasury" and len(cmd) >= 4:
                        cname = resolve_currency(cur, cmd[2])
                        target_name = cmd[3].lower()
                        target_uuid = get_uuid(target_name)
                        if not (
                            is_exec_admin
                            or is_currency_manager(cur, exec_uuid, cname)
                        ):
                            success = False
                            error_text = t("error.no_permission", lang=exec_lang)
                        elif target_uuid is None:
                            success = False
                            error_text = t("error.invalid_args", lang=exec_lang)
                        else:
                            cur.execute(
                                "UPDATE currencies SET treasury=? WHERE name=?",
                                (target_uuid, cname),
                            )
                            messages.append(
                                {
                                    "target": "chat",
                                    "text": t(
                                        "currency.treasury",
                                        lang=exec_lang,
                                        currency=cname,
                                        account=target_name,
                                    ),
                                }
                            )
                    else:
                        success = False
                        error_text = t("error.invalid_args", lang=exec_lang)
            elif action == "money" and len(cmd) >= 2:
                sub = cmd[1].lower()
                if sub in {"give", "take"} and len(cmd) >= 4:
                    target_name = cmd[2].lower()
                    currency, amt_idx, _ = extract_currency(3)
                    target_uuid = get_uuid(target_name)
                    base = get_balance(cur, target_uuid, currency) if target_uuid else None
                    amt = parse_amount(amt_idx, base)
                    if (
                        amt is None
                        or target_uuid is None
                        or not is_currency(cur, currency)
                    ):
                        success = False
                        error_text = t("error.invalid_args", lang=exec_lang) if amt is None or target_uuid is None else t("error.invalid_currency", lang=exec_lang)
                    else:
                        if not (
                            is_exec_admin
                            or is_currency_manager(cur, exec_uuid, currency)
                        ):
                            success = False
                            error_text = t("error.no_permission", lang=exec_lang)
                        elif is_frozen(cur, target_uuid, currency):
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
                elif sub == "top":
                    currency = None
                    page = 1
                    if len(cmd) >= 4 and is_currency(cur, cmd[2]):
                        currency = resolve_currency(cur, cmd[2])
                        try:
                            page = max(1, int(cmd[3]))
                        except ValueError:
                            success = False
                            error_text = t("error.invalid_args", lang=exec_lang)
                    elif len(cmd) >= 3:
                        if is_currency(cur, cmd[2]):
                            currency = resolve_currency(cur, cmd[2])
                        else:
                            currency = get_default_currency(cur)
                            try:
                                page = max(1, int(cmd[2]))
                            except ValueError:
                                success = False
                                error_text = t("error.invalid_args", lang=exec_lang)
                    else:
                        currency = get_default_currency(cur)
                    if success:
                        if not is_currency(cur, currency):
                            success = False
                            error_text = t("error.invalid_currency", lang=exec_lang)
                        else:
                            offset = (page - 1) * 10
                            rows = cur.execute(
                                "SELECT n.name, a.balance FROM accounts a JOIN name_index n ON a.uuid=n.uuid WHERE a.currency=? ORDER BY a.balance DESC LIMIT 10 OFFSET ?",
                                (currency, offset),
                            ).fetchall()
                            messages.append(
                                {
                                    "target": "chat",
                                    "text": t(
                                        "money.top_header",
                                        lang=exec_lang,
                                        currency=currency,
                                        page=page,
                                    ),
                                }
                            )
                        if rows:
                            for idx, r in enumerate(rows, start=offset + 1):
                                messages.append(
                                    {
                                        "target": "chat",
                                        "text": t(
                                            "money.top_entry",
                                            lang=exec_lang,
                                            rank=idx,
                                            player=r["name"],
                                            amount=format_amount(cur, r["balance"], currency),
                                        ),
                                    }
                                )
                        else:
                            messages.append({"target": "chat", "text": t("money.top_empty", lang=exec_lang)})
                elif sub == "pay" and len(cmd) >= 4:
                    if len(cmd) >= 6:
                        src_name = cmd[2].lower()
                        dst_name = cmd[3].lower()
                        currency, amt_idx, _ = extract_currency(4)
                    elif len(cmd) == 5:
                        if is_currency(cur, cmd[3]):
                            src_name = payload.executor.lower()
                            dst_name = cmd[2].lower()
                            currency, amt_idx, _ = extract_currency(3)
                        else:
                            src_name = payload.executor.lower()
                            dst_name = cmd[2].lower()
                            currency = get_default_currency(cur)
                            amt_idx = 3
                    else:  # len == 4
                        src_name = payload.executor.lower()
                        dst_name = cmd[2].lower()
                        currency, amt_idx, _ = extract_currency(3)
                    src_uuid = get_uuid(src_name)
                    dst_uuid = get_uuid(dst_name)
                    if (
                        not is_exec_admin
                        and src_uuid != exec_uuid
                        and not has_link(cur, exec_uuid, src_uuid)
                    ):
                        success = False
                        error_text = t("error.no_permission", lang=exec_lang)
                    else:
                        base = get_balance(cur, src_uuid, currency) if src_uuid else None
                        amt = parse_amount(amt_idx, base)
                        if (
                            amt is None
                            or src_uuid is None
                            or dst_uuid is None
                            or not is_currency(cur, currency)
                        ):
                            success = False
                            error_text = t("error.invalid_args", lang=exec_lang) if amt is None or src_uuid is None or dst_uuid is None else t("error.invalid_currency", lang=exec_lang)
                        else:
                            rate, treasury = tax_info(cur, currency)
                            tax_amt = amt * rate // 1000
                            required = amt + tax_amt
                            if is_frozen(cur, src_uuid, currency) or is_frozen(cur, dst_uuid, currency):
                                success = False
                                error_text = t("error.frozen", lang=exec_lang)
                            elif get_balance(cur, src_uuid, currency) < required:
                                success = False
                                error_text = t("error.insufficient", lang=exec_lang)
                            else:
                                if not add_balance(cur, src_uuid, currency, -required):
                                    success = False
                                    error_text = t("error.pay_failed", lang=exec_lang)
                                else:
                                    add_balance(cur, dst_uuid, currency, amt)
                                    if tax_amt > 0 and treasury:
                                        add_balance(cur, treasury, currency, tax_amt)
                                        record_transaction(
                                            cur,
                                            payload.timestamp,
                                            src_uuid,
                                            treasury,
                                            currency,
                                            tax_amt,
                                            "tax",
                                        )
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
            elif action == "pay" and len(cmd) >= 3 and parse_amount_token(cmd[2], None) is not None:
                dst_name = cmd[1].lower()
                currency = resolve_currency(cur, cmd[3]) if len(cmd) >= 4 else get_default_currency(cur)
                src_uuid = exec_uuid
                dst_uuid = get_uuid(dst_name)
                base = get_balance(cur, src_uuid, currency)
                amt = parse_amount_token(cmd[2], base)
                if (
                    amt is None
                    or dst_uuid is None
                    or not is_currency(cur, currency)
                ):
                    success = False
                    error_text = t("error.invalid_args", lang=exec_lang) if amt is None or dst_uuid is None else t("error.invalid_currency", lang=exec_lang)
                else:
                    rate, treasury = tax_info(cur, currency)
                    tax_amt = amt * rate // 1000
                    required = amt + tax_amt
                    if is_frozen(cur, src_uuid, currency) or is_frozen(cur, dst_uuid, currency):
                        success = False
                        error_text = t("error.frozen", lang=exec_lang)
                    elif get_balance(cur, src_uuid, currency) < required:
                        success = False
                        error_text = t("error.insufficient", lang=exec_lang)
                    elif not add_balance(cur, src_uuid, currency, -required):
                        success = False
                        error_text = t("error.pay_failed", lang=exec_lang)
                    else:
                        add_balance(cur, dst_uuid, currency, amt)
                        if tax_amt > 0 and treasury:
                            add_balance(cur, treasury, currency, tax_amt)
                            record_transaction(
                                cur,
                                payload.timestamp,
                                src_uuid,
                                treasury,
                                currency,
                                tax_amt,
                                "tax",
                            )
                        messages.append({
                            "target": "chat",
                            "text": t(
                                "money.pay",
                                lang=exec_lang,
                                src=payload.executor.lower(),
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
                                    src=payload.executor.lower(),
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
            elif action in {"pay", "transfer"} and len(cmd) >= 4:
                src_name = cmd[1].lower()
                dst_name = cmd[2].lower()
                currency, amt_idx, _ = extract_currency(3)
                src_uuid = get_uuid(src_name)
                dst_uuid = get_uuid(dst_name)
                if (
                    not is_exec_admin
                    and src_uuid is not None
                    and src_uuid != exec_uuid
                    and not has_link(cur, exec_uuid, src_uuid)
                ):
                    success = False
                    error_text = t("error.no_permission", lang=exec_lang)
                else:
                    base = get_balance(cur, src_uuid, currency) if src_uuid else None
                    amt = parse_amount(amt_idx, base)
                    if (
                        amt is None
                        or src_uuid is None
                        or dst_uuid is None
                        or not is_currency(cur, currency)
                    ):
                        success = False
                        error_text = t("error.invalid_args", lang=exec_lang) if amt is None or src_uuid is None or dst_uuid is None else t("error.invalid_currency", lang=exec_lang)
                    else:
                        rate, treasury = tax_info(cur, currency)
                        tax_amt = amt * rate // 1000
                        required = amt + tax_amt
                        if is_frozen(cur, src_uuid, currency) or is_frozen(cur, dst_uuid, currency):
                            success = False
                            error_text = t("error.frozen", lang=exec_lang)
                        elif get_balance(cur, src_uuid, currency) < required:
                            success = False
                            error_text = t("error.insufficient", lang=exec_lang)
                        elif not add_balance(cur, src_uuid, currency, -required):
                            success = False
                            error_text = t("error.pay_failed", lang=exec_lang)
                        else:
                            add_balance(cur, dst_uuid, currency, amt)
                            if tax_amt > 0 and treasury:
                                add_balance(cur, treasury, currency, tax_amt)
                                record_transaction(
                                    cur,
                                    payload.timestamp,
                                    src_uuid,
                                    treasury,
                                    currency,
                                    tax_amt,
                                    "tax",
                                )
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
            elif action in {"deposit", "withdraw"} and len(cmd) >= 4:
                src_name = cmd[1].lower()
                dst_name = cmd[2].lower()
                currency, amt_idx, _ = extract_currency(3)
                src_uuid = get_uuid(src_name)
                dst_uuid = get_uuid(dst_name)
                if (
                    not is_exec_admin
                    and src_uuid is not None
                    and src_uuid != exec_uuid
                    and not has_link(cur, exec_uuid, src_uuid)
                ):
                    success = False
                    error_text = t("error.no_permission", lang=exec_lang)
                else:
                    amt = parse_amount(amt_idx)
                    if (
                        amt is None
                        or src_uuid is None
                        or dst_uuid is None
                        or not is_currency(cur, currency)
                    ):
                        success = False
                        error_text = t("error.invalid_args", lang=exec_lang) if amt is None or src_uuid is None or dst_uuid is None else t("error.invalid_currency", lang=exec_lang)
                    else:
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
            elif action in {"balance", "wallet"}:
                target_name = None
                currency = None
                if len(cmd) >= 3 and is_currency(cur, cmd[1]):
                    currency = resolve_currency(cur, cmd[1])
                    target_name = cmd[2].lower()
                elif len(cmd) >= 2:
                    if is_currency(cur, cmd[1]):
                        currency = resolve_currency(cur, cmd[1])
                    else:
                        target_name = cmd[1].lower()
                if currency and not is_currency(cur, currency):
                    success = False
                    error_text = t("error.invalid_currency", lang=exec_lang)
                else:
                    target_uuid = get_uuid(target_name) if target_name else exec_uuid
                    if target_uuid is None:
                        success = False
                        error_text = t("error.invalid_args", lang=exec_lang)
                    else:
                        if currency:
                            bal = get_balance(cur, target_uuid, currency)
                            key = "balance.other_single" if target_uuid != exec_uuid else "balance.single"
                            messages.append(
                                {
                                    "target": "chat",
                                    "text": t(
                                        key,
                                        lang=exec_lang,
                                        player=target_name or payload.executor,
                                        currency=currency,
                                        amount=format_amount(cur, bal, currency),
                                    ),
                                }
                            )
                        else:
                            bals = list_balances(cur, target_uuid)
                            if bals:
                                balances = ", ".join(
                                    f"§e{k}§7={format_amount(cur, v, k)}§a" for k, v in bals.items()
                                )
                                key = "balance.other_all" if target_uuid != exec_uuid else "balance.all"
                                messages.append(
                                    {
                                        "target": "chat",
                                        "text": t(key, lang=exec_lang, player=target_name or payload.executor, balances=balances),
                                    }
                                )
                            else:
                                key = "balance.other_empty" if target_uuid != exec_uuid else "balance.empty"
                                messages.append({"target": "chat", "text": t(key, lang=exec_lang, player=target_name or payload.executor)})
                        scoreboards[target_uuid] = get_scoreboard(cur, target_uuid)
            elif action == "account" and len(cmd) >= 4 and cmd[1].lower() == "connect":
                user_name = cmd[2].lower()
                sys_name = cmd[3].lower()
                user_uuid = get_uuid(user_name)
                sys_uuid = get_uuid(sys_name)
                if (
                    user_uuid is None
                    or sys_uuid is None
                    or cur.execute(
                        "SELECT 1 FROM system_accounts WHERE uuid=?", (sys_uuid,)
                    ).fetchone()
                    is None
                ):
                    success = False
                    error_text = t("error.invalid_args", lang=exec_lang)
                else:
                    cur.execute(
                        "INSERT OR REPLACE INTO account_links(user_uuid, system_uuid) VALUES (?,?)",
                        (user_uuid, sys_uuid),
                    )
                    messages.append(
                        {
                            "target": "chat",
                            "text": t(
                                "account.connected",
                                lang=exec_lang,
                                user=user_name,
                                system=sys_name,
                            ),
                        }
                    )
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
                        if not is_currency(cur, act["currency"]):
                            continue
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
                        if not is_currency(cur, act["currency"]):
                            continue
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
            elif action == "setbalance" and len(cmd) >= 3:
                target_name = cmd[1].lower()
                currency, amt_idx, _ = extract_currency(2)
                target_uuid = get_uuid(target_name)
                base = get_balance(cur, target_uuid, currency) if target_uuid else None
                amt = parse_amount_any(amt_idx, base)
                if (
                    amt is None
                    or target_uuid is None
                    or not is_currency(cur, currency)
                ):
                    success = False
                    error_text = t("error.invalid_args", lang=exec_lang) if amt is None or target_uuid is None else t("error.invalid_currency", lang=exec_lang)
                else:
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
        sanitize_messages(messages)
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
        sanitize_messages(err_msgs)
        res = {"status": "error", "messages": err_msgs}

    log_command(payload, success, error_text)
    return res


@app.post("/api/sync")
async def sync(payload: DeltaPayload, token: None = Depends(verify_token)):
    with transaction() as cur:
        for k, v in payload.delta.items():
            if is_currency(cur, k):
                add_balance(cur, payload.player, k, v)
        cur.execute(
            "INSERT OR IGNORE INTO players(uuid, last_seen, lang_hint) VALUES (?, ?, 0)",
            (payload.player, payload.timestamp),
        )
        cur.execute(
            "UPDATE players SET last_seen=? WHERE uuid=?",
            (payload.timestamp, payload.player),
        )
    return {"status": "success"}


@app.post("/api/rewrite")
async def rewrite(payload: RewritePayload, token: None = Depends(verify_token)):
    msgs = []
    hint_sent = False
    with transaction() as cur:
        for k, v in payload.scoreboard.items():
            if is_currency(cur, k):
                set_balance(cur, payload.player, k, v)
        cur.execute(
            "INSERT OR IGNORE INTO players(uuid, last_seen, lang_hint) VALUES (?, ?, 0)",
            (payload.player, payload.timestamp),
        )
        cur.execute(
            "UPDATE players SET last_seen=? WHERE uuid=?",
            (payload.timestamp, payload.player),
        )
        row = cur.execute(
            "SELECT lang_hint FROM players WHERE uuid=?",
            (payload.player,),
        ).fetchone()
        hint_sent = bool(row["lang_hint"] if row else 0)
        rows = cur.execute(
            "SELECT rowid, payload FROM pending_messages WHERE uuid=?",
            (payload.player,),
        ).fetchall()
        for r in rows:
            msgs.append(json.loads(r["payload"]))
            cur.execute("DELETE FROM pending_messages WHERE rowid=?", (r["rowid"],))
    if get_lang(payload.player) == "en" and not hint_sent:
        msgs.append(
            {
                "target": "chat",
                "text": t("lang.switch_hint", lang="jp"),
                "player": payload.player,
                "delay": 5,
            }
        )
        with transaction() as cur:
            cur.execute(
                "UPDATE players SET lang_hint=1 WHERE uuid=?",
                (payload.player,),
            )
    sanitize_messages(msgs)
    return {"status": "success", "messages": msgs}


@app.post("/api/shop/place")
async def shop_place(payload: ShopPlacePayload, token: None = Depends(verify_token)):
    start = time.time()
    result = "ok"
    with transaction() as cur:
        existing = cur.execute(
            "SELECT 1 FROM shops WHERE shop_id=?",
            (payload.shop_id,),
        ).fetchone()
        if existing:
            authorized = cur.execute(
                "SELECT 1 FROM shop_owners WHERE shop_id=? AND owner_uuid=?",
                (payload.shop_id, payload.placer_uuid),
            ).fetchone()
            if not authorized:
                raise HTTPException(status_code=403, detail="not owner")
            cur.execute(
                "UPDATE shop_locations SET world=?, x=?, y=?, z=? WHERE shop_id=?",
                (payload.world, payload.x, payload.y, payload.z, payload.shop_id),
            )
            cur.execute(
                "UPDATE shops SET last_activity_at=? WHERE shop_id=?",
                (payload.timestamp, payload.shop_id),
            )
        else:
            if payload.owner_uuid != payload.placer_uuid:
                raise HTTPException(status_code=403, detail="owner mismatch")
            cur.execute(
                "INSERT INTO shops(shop_id, owner_uuid, status, created_at, last_activity_at) VALUES(?,?,?,?,?)",
                (payload.shop_id, payload.owner_uuid, "active", payload.timestamp, payload.timestamp),
            )
            cur.execute(
                "INSERT INTO shop_locations(shop_id, world, x, y, z) VALUES(?,?,?,?,?)",
                (payload.shop_id, payload.world, payload.x, payload.y, payload.z),
            )
            cur.execute(
                "INSERT INTO shop_owners(shop_id, owner_uuid) VALUES(?,?)",
                (payload.shop_id, payload.owner_uuid),
            )
    latency_ms = int((time.time() - start) * 1000)
    log_entry = {
        "type": "shop_place",
        "timestamp": payload.timestamp,
        "shop_id": payload.shop_id,
        "owner": payload.owner_uuid,
        "placer": payload.placer_uuid,
        "world": payload.world,
        "x": payload.x,
        "y": payload.y,
        "z": payload.z,
        "result": result,
        "latency_ms": latency_ms,
    }
    append_log(log_entry)
    return {"status": result}


@app.get("/api/shop/ids")
async def shop_ids():
    start = time.time()
    with transaction() as cur:
        rows = cur.execute("SELECT shop_id FROM shops").fetchall()
    ids = [r["shop_id"] for r in rows]
    latency_ms = int((time.time() - start) * 1000)
    log_entry = {
        "type": "shop_ids",
        "timestamp": int(time.time()),
        "count": len(ids),
        "latency_ms": latency_ms,
    }
    append_log(log_entry)
    return {"ids": ids}


@app.get("/api/shop/items")
async def shop_items(shop_id: str):
    start = time.time()
    with transaction() as cur:
        srow = cur.execute(
            "SELECT owner_uuid,status,last_activity_at FROM shops WHERE shop_id=?",
            (shop_id,),
        ).fetchone()
        if not srow:
            result = {"status": "error", "reason": "shop_not_found"}
        elif srow["status"] != "active":
            result = {
                "status": srow["status"],
                "last_activity_at": srow["last_activity_at"],
                "owner_uuid": srow["owner_uuid"],
            }
        else:
            rows = cur.execute(
                "SELECT st.item_key, st.sale_name, st.stock, it.material, it.display_name, it.nbt_blob FROM shop_stock st JOIN shop_items it ON st.item_key=it.item_key WHERE st.shop_id=?",
                (shop_id,),
            ).fetchall()
            items = []
            owners = [r["owner_uuid"] for r in cur.execute("SELECT owner_uuid FROM shop_owners WHERE shop_id=?", (shop_id,)).fetchall()]
            sale = cur.execute(
                "SELECT pct,end_ts FROM sale_events WHERE active=1 AND start_ts<=? AND end_ts>=?",
                (int(time.time()), int(time.time())),
            ).fetchone()
            for r in rows:
                price_rows = cur.execute(
                    "SELECT currency, price FROM shop_prices WHERE shop_id=? AND item_key=?",
                    (shop_id, r["item_key"]),
                ).fetchall()
                prices = {pr["currency"]: pr["price"] for pr in price_rows}
                if sale:
                    for k in list(prices.keys()):
                        prices[k] = int(prices[k] * (100 - sale["pct"]) / 100)
                items.append(
                    {
                        "item_key": r["item_key"],
                        "sale_name": r["sale_name"],
                        "material": r["material"],
                        "display_name": r["display_name"],
                        "nbt_blob": base64.b64encode(r["nbt_blob"]).decode("ascii"),
                        "stock": r["stock"],
                        "prices": prices,
                    }
                )
            result = {
                "status": "active",
                "owner_uuid": srow["owner_uuid"],
                "owners": owners,
                "items": items,
            }
            if sale:
                result["sale_pct"] = sale["pct"]
                result["sale_ends"] = sale["end_ts"]
    latency_ms = int((time.time() - start) * 1000)
    log_entry = {
        "type": "shop_items",
        "timestamp": int(time.time()),
        "shop_id": shop_id,
        "status": result.get("status"),
        "latency_ms": latency_ms,
    }
    append_log(log_entry)
    return result


@app.post("/api/shop/buy")
async def shop_buy(payload: ShopBuyPayload):
    start = time.time()
    messages: List[Dict[str, str]] = []
    scoreboards: Dict[str, Dict[str, int]] = {}
    grant: List[Dict[str, str]] = []
    success = False
    reason: Optional[str] = None
    total_price = 0
    grant_token: Optional[str] = None
    location: Optional[sqlite3.Row] = None
    with transaction() as cur:
        existing = cur.execute(
            "SELECT * FROM shop_tx WHERE client_tx_id=? AND tx_type='buy'",
            (payload.client_tx_id,),
        ).fetchone()
        if existing:
            success = existing["result"] == "success"
            reason = existing["reason"]
            total_price = existing["total_price"]
            grant_token = existing["grant_token"]
            shop = cur.execute(
                "SELECT owner_uuid FROM shops WHERE shop_id=?",
                (existing["shop_id"],),
            ).fetchone()
            owner = shop["owner_uuid"] if shop else None
            if success and grant_token:
                stock_row = cur.execute(
                    "SELECT stock FROM shop_stock WHERE shop_id=? AND item_key=?",
                    (existing["shop_id"], existing["item_key"]),
                ).fetchone()
                remaining = stock_row["stock"] if stock_row else 0
                grant.append(
                    {
                        "item_key": existing["item_key"],
                        "qty": existing["qty"],
                        "grant_token": grant_token,
                    }
                )
                if owner:
                    scoreboards[existing["buyer_uuid"]] = get_scoreboard(
                        cur, existing["buyer_uuid"]
                    )
                    scoreboards[owner] = get_scoreboard(cur, owner)
                buyer_name = get_name(existing["buyer_uuid"]) or existing["buyer_uuid"]
                buyer_msg = {
                    "target": "chat",
                    "player": existing["buyer_uuid"],
                    "text": f"Bought x{existing['qty']} for {existing['currency']} {existing['total_price']} (left {remaining})",
                }
                owner_msg = {
                    "target": "chat",
                    "player": owner,
                    "text": f"Sold x{existing['qty']} to {buyer_name} for {existing['currency']} {existing['total_price']} (left {remaining})",
                }
                messages.append(buyer_msg)
                if owner:
                    messages.append(owner_msg)
            else:
                messages.append(
                    {
                        "target": "chat",
                        "player": existing["buyer_uuid"],
                        "text": f"Purchase failed: {reason}",
                    }
                )
        else:
            shop = cur.execute(
                "SELECT owner_uuid, status FROM shops WHERE shop_id=?",
                (payload.shop_id,),
            ).fetchone()
            location = cur.execute(
                "SELECT world, x, y, z FROM shop_locations WHERE shop_id=?",
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
                        base_price = price_row["price"] * payload.qty
                        sale = cur.execute(
                            "SELECT id,pct,account FROM sale_events WHERE active=1 AND start_ts<=? AND end_ts>=?",
                            (payload.timestamp, payload.timestamp),
                        ).fetchone()
                        discount = 0
                        if sale:
                            discount = int(base_price * sale["pct"] / 100)
                            if get_balance(cur, sale["account"], payload.currency) >= discount:
                                add_balance(cur, sale["account"], payload.currency, -discount)
                                total_price = base_price - discount
                            else:
                                cur.execute(
                                    "UPDATE sale_events SET active=0, end_ts=? WHERE id=?",
                                    (payload.timestamp, sale["id"]),
                                )
                                total_price = base_price
                        else:
                            total_price = base_price
                        if get_balance(cur, payload.player_uuid, payload.currency) < total_price:
                            reason = "insufficient_funds"
                        elif not transfer(
                            cur, payload.player_uuid, owner, payload.currency, total_price
                        ):
                            reason = "transfer_failed"
                        else:
                            grant_token = secrets.token_hex(8)
                            cur.execute(
                                "UPDATE shop_stock SET stock=stock-? WHERE shop_id=? AND item_key=?",
                                (payload.qty, payload.shop_id, payload.item_key),
                            )
                            cur.execute(
                                "UPDATE shops SET last_activity_at=? WHERE shop_id=?",
                                (payload.timestamp, payload.shop_id),
                            )
                            cur.execute(
                                "INSERT INTO shop_tx(client_tx_id,shop_id,buyer_uuid,item_key,qty,currency,total_price,timestamp,result,grant_token,world,x,y,z,tx_type) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                                (
                                    payload.client_tx_id,
                                    payload.shop_id,
                                    payload.player_uuid,
                                    payload.item_key,
                                    payload.qty,
                                    payload.currency,
                                    total_price,
                                    payload.timestamp,
                                    "success",
                                    grant_token,
                                    location["world"] if location else None,
                                    location["x"] if location else None,
                                    location["y"] if location else None,
                                    location["z"] if location else None,
                                    "buy",
                                ),
                            )
                            success = True
                            stock_row = cur.execute(
                                "SELECT stock FROM shop_stock WHERE shop_id=? AND item_key=?",
                                (payload.shop_id, payload.item_key),
                            ).fetchone()
                            remaining = stock_row["stock"] if stock_row else 0
                            buyer_name = get_name(payload.player_uuid) or payload.player_uuid
                            buyer_msg = {
                                "target": "chat",
                                "player": payload.player_uuid,
                                "text": f"Bought x{payload.qty} for {payload.currency} {total_price} (left {remaining})",
                            }
                            owner_msg = {
                                "target": "chat",
                                "player": owner,
                                "text": f"Sold x{payload.qty} to {buyer_name} for {payload.currency} {total_price} (left {remaining})",
                            }
                            messages.append(buyer_msg)
                            if is_online(cur, owner, payload.timestamp):
                                messages.append(owner_msg)
                            else:
                                queue_message(cur, owner_msg)
                            scoreboards[payload.player_uuid] = get_scoreboard(
                                cur, payload.player_uuid
                            )
                            scoreboards[owner] = get_scoreboard(cur, owner)
                            grant.append(
                                {
                                    "item_key": payload.item_key,
                                    "qty": payload.qty,
                                    "grant_token": grant_token,
                                }
                            )
            if not success:
                cur.execute(
                    "INSERT INTO shop_tx(client_tx_id,shop_id,buyer_uuid,item_key,qty,currency,total_price,timestamp,result,reason,world,x,y,z,tx_type) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    (
                        payload.client_tx_id,
                        payload.shop_id,
                        payload.player_uuid,
                        payload.item_key,
                        payload.qty,
                        payload.currency,
                        total_price,
                        payload.timestamp,
                        "fail",
                        reason,
                        location["world"] if location else None,
                        location["x"] if location else None,
                        location["y"] if location else None,
                        location["z"] if location else None,
                        "buy",
                    ),
                )
                messages.append(
                    {
                        "target": "chat",
                        "player": payload.player_uuid,
                        "text": f"Purchase failed: {reason}",
                    }
                )
    latency_ms = int((time.time() - start) * 1000)
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
        "client_tx_id": payload.client_tx_id,
        "latency_ms": latency_ms,
        "world": location["world"] if location else None,
        "x": location["x"] if location else None,
        "y": location["y"] if location else None,
        "z": location["z"] if location else None,
    }
    append_log(log_entry)
    sanitize_messages(messages)
    return {
        "status": "success" if success else "error",
        "messages": messages,
        "scoreboards": scoreboards,
        "grant": grant,
    }


@app.post("/api/shop/sell")
async def shop_sell(payload: ShopSellPayload):
    start = time.time()
    messages: List[Dict[str, str]] = []
    scoreboards: Dict[str, Dict[str, int]] = {}
    success = False
    reason: Optional[str] = None
    total_price = 0
    location: Optional[sqlite3.Row] = None
    with transaction() as cur:
        existing = cur.execute(
            "SELECT * FROM shop_tx WHERE client_tx_id=? AND tx_type='sell'",
            (payload.client_tx_id,),
        ).fetchone()
        if existing:
            success = existing["result"] == "success"
            reason = existing["reason"]
            total_price = existing["total_price"]
            shop = cur.execute(
                "SELECT owner_uuid FROM shops WHERE shop_id=?",
                (existing["shop_id"],),
            ).fetchone()
            owner = shop["owner_uuid"] if shop else None
            if success:
                if owner:
                    scoreboards[payload.player_uuid] = get_scoreboard(cur, payload.player_uuid)
                    scoreboards[owner] = get_scoreboard(cur, owner)
                player_name = get_name(payload.player_uuid) or payload.player_uuid
                buyer_msg = {
                    "target": "chat",
                    "player": payload.player_uuid,
                    "text": f"Sold x{existing['qty']} for {existing['currency']} {existing['total_price']}",
                }
                owner_msg = {
                    "target": "chat",
                    "player": owner,
                    "text": f"Bought x{existing['qty']} from {player_name} for {existing['currency']} {existing['total_price']}",
                }
                messages.extend([buyer_msg, owner_msg])
            else:
                messages.append(
                    {
                        "target": "chat",
                        "player": payload.player_uuid,
                        "text": f"Sale failed: {reason}",
                    }
                )
        else:
            shop = cur.execute(
                "SELECT owner_uuid, status FROM shops WHERE shop_id=?",
                (payload.shop_id,),
            ).fetchone()
            location = cur.execute(
                "SELECT world, x, y, z FROM shop_locations WHERE shop_id=?",
                (payload.shop_id,),
            ).fetchone()
            if not shop:
                reason = "shop_not_found"
            elif shop["status"] != "active":
                reason = "shop_suspended"
            else:
                owner = shop["owner_uuid"]
                price_row = cur.execute(
                    "SELECT price FROM shop_prices WHERE shop_id=? AND item_key=? AND currency=?",
                    (payload.shop_id, payload.item_key, payload.currency),
                ).fetchone()
                if not price_row:
                    reason = "invalid_currency"
                else:
                    total_price = price_row["price"] * payload.qty
                    if get_balance(cur, owner, payload.currency) < total_price:
                        reason = "insufficient_funds"
                    elif not transfer(cur, owner, payload.player_uuid, payload.currency, total_price):
                        reason = "transfer_failed"
                    else:
                        cur.execute(
                            "UPDATE shop_stock SET stock=stock+? WHERE shop_id=? AND item_key=?",
                            (payload.qty, payload.shop_id, payload.item_key),
                        )
                        cur.execute(
                            "UPDATE shops SET last_activity_at=? WHERE shop_id=?",
                            (payload.timestamp, payload.shop_id),
                        )
                        cur.execute(
                            "INSERT INTO shop_tx(client_tx_id,shop_id,buyer_uuid,item_key,qty,currency,total_price,timestamp,result,world,x,y,z,tx_type) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                            (
                                payload.client_tx_id,
                                payload.shop_id,
                                payload.player_uuid,
                                payload.item_key,
                                payload.qty,
                                payload.currency,
                                total_price,
                                payload.timestamp,
                                "success",
                                location["world"] if location else None,
                                location["x"] if location else None,
                                location["y"] if location else None,
                                location["z"] if location else None,
                                "sell",
                            ),
                        )
                        success = True
                        player_name = get_name(payload.player_uuid) or payload.player_uuid
                        buyer_msg = {
                            "target": "chat",
                            "player": payload.player_uuid,
                            "text": f"Sold x{payload.qty} for {payload.currency} {total_price}",
                        }
                        owner_msg = {
                            "target": "chat",
                            "player": owner,
                            "text": f"Bought x{payload.qty} from {player_name} for {payload.currency} {total_price}",
                        }
                        messages.append(buyer_msg)
                        if is_online(cur, owner, payload.timestamp):
                            messages.append(owner_msg)
                        else:
                            queue_message(cur, owner_msg)
                        scoreboards[payload.player_uuid] = get_scoreboard(cur, payload.player_uuid)
                        scoreboards[owner] = get_scoreboard(cur, owner)
        if not success and not existing:
            cur.execute(
                "INSERT INTO shop_tx(client_tx_id,shop_id,buyer_uuid,item_key,qty,currency,total_price,timestamp,result,reason,world,x,y,z,tx_type) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (
                    payload.client_tx_id,
                    payload.shop_id,
                    payload.player_uuid,
                    payload.item_key,
                    payload.qty,
                    payload.currency,
                    total_price,
                    payload.timestamp,
                    "fail",
                    reason,
                    location["world"] if location else None,
                    location["x"] if location else None,
                    location["y"] if location else None,
                    location["z"] if location else None,
                    "sell",
                ),
            )
            messages.append(
                {
                    "target": "chat",
                    "player": payload.player_uuid,
                    "text": f"Sale failed: {reason}",
                }
            )
    latency_ms = int((time.time() - start) * 1000)
    log_entry = {
        "type": "shop_sell",
        "timestamp": payload.timestamp,
        "shop_id": payload.shop_id,
        "buyer": payload.player_uuid,
        "item_key": payload.item_key,
        "qty": payload.qty,
        "currency": payload.currency,
        "total_price": total_price,
        "result": "success" if success else "error",
        "reason": reason,
        "client_tx_id": payload.client_tx_id,
        "latency_ms": latency_ms,
        "world": location["world"] if location else None,
        "x": location["x"] if location else None,
        "y": location["y"] if location else None,
        "z": location["z"] if location else None,
    }
    append_log(log_entry)
    sanitize_messages(messages)
    return {
        "status": "success" if success else "error",
        "messages": messages,
        "scoreboards": scoreboards,
    }


@app.post("/api/shop/add_stock")
async def shop_add_stock(payload: ShopAddStockPayload):
    start = time.time()
    blob = base64.b64decode(payload.nbt_blob)
    item_key = hashlib.sha256(blob).hexdigest()
    result = "success"
    reason: Optional[str] = None
    sale_name = payload.sale_name or payload.display_name or payload.material
    with transaction() as cur:
        shop = cur.execute(
            "SELECT status FROM shops WHERE shop_id=?",
            (payload.shop_id,),
        ).fetchone()
        if not shop or not is_shop_owner(cur, payload.shop_id, payload.owner_uuid) or shop["status"] != "active":
            result = "error"
            reason = "not_owner"
        else:
            ts = int(time.time())
            cur.execute(
                "INSERT OR IGNORE INTO shop_items(item_key, material, display_name, nbt_blob) VALUES(?,?,?,?)",
                (item_key, payload.material, payload.display_name, blob),
            )
            cur.execute(
                """
                INSERT INTO shop_stock(shop_id, item_key, sale_name, stock, updated_at)
                VALUES(?,?,?,?,?)
                ON CONFLICT(shop_id,item_key) DO UPDATE SET stock=stock+excluded.stock, updated_at=excluded.updated_at, sale_name=excluded.sale_name
                """,
                (payload.shop_id, item_key, sale_name, payload.qty, ts),
            )
            currency = payload.currency or get_default_currency(cur)
            cur.execute(
                """
                INSERT INTO shop_prices(shop_id, item_key, currency, price) VALUES(?,?,?,?)
                ON CONFLICT(shop_id,item_key,currency) DO UPDATE SET price=excluded.price
                """,
                (payload.shop_id, item_key, currency, payload.price),
            )
            cur.execute(
                "UPDATE shops SET last_activity_at=? WHERE shop_id=?",
                (ts, payload.shop_id),
            )
    latency_ms = int((time.time() - start) * 1000)
    log_entry = {
        "type": "shop_add_stock",
        "timestamp": int(time.time()),
        "shop_id": payload.shop_id,
        "owner": payload.owner_uuid,
        "item_key": item_key,
        "qty": payload.qty,
        "sale_name": sale_name,
        "price": payload.price,
        "result": result,
        "reason": reason,
        "latency_ms": latency_ms,
    }
    append_log(log_entry)
    if result == "success":
        return {"status": "success", "item_key": item_key}
    return {"status": "error", "reason": reason}


@app.post("/api/shop/take_stock")
async def shop_take_stock(payload: ShopTakeStockPayload):
    start = time.time()
    grant: List[Dict[str, str]] = []
    result = "success"
    reason: Optional[str] = None
    with transaction() as cur:
        shop = cur.execute(
            "SELECT status FROM shops WHERE shop_id=?",
            (payload.shop_id,),
        ).fetchone()
        if not shop or not is_shop_owner(cur, payload.shop_id, payload.owner_uuid) or shop["status"] != "active":
            result = "error"
            reason = "not_owner"
        else:
            row = cur.execute(
                "SELECT stock FROM shop_stock WHERE shop_id=? AND item_key=?",
                (payload.shop_id, payload.item_key),
            ).fetchone()
            if not row or row["stock"] < payload.qty:
                result = "error"
                reason = "insufficient_stock"
            else:
                ts = int(time.time())
                cur.execute(
                    "UPDATE shop_stock SET stock=stock-?, updated_at=? WHERE shop_id=? AND item_key=?",
                    (payload.qty, ts, payload.shop_id, payload.item_key),
                )
                cur.execute(
                    "UPDATE shops SET last_activity_at=? WHERE shop_id=?",
                    (ts, payload.shop_id),
                )
                item = cur.execute(
                    "SELECT nbt_blob FROM shop_items WHERE item_key=?", (payload.item_key,)
                ).fetchone()
                if item:
                    grant.append(
                        {
                            "item_key": payload.item_key,
                            "qty": payload.qty,
                            "nbt_blob": base64.b64encode(item["nbt_blob"]).decode("ascii"),
                            "grant_token": secrets.token_hex(8),
                        }
                    )
    latency_ms = int((time.time() - start) * 1000)
    log_entry = {
        "type": "shop_take_stock",
        "timestamp": int(time.time()),
        "shop_id": payload.shop_id,
        "owner": payload.owner_uuid,
        "item_key": payload.item_key,
        "qty": payload.qty,
        "result": result,
        "reason": reason,
        "latency_ms": latency_ms,
    }
    append_log(log_entry)
    if result == "success":
        return {"status": "success", "grant": grant}
    return {"status": "error", "reason": reason}


@app.post("/api/shop/set_price")
async def shop_set_price(payload: ShopSetPricePayload):
    start = time.time()
    result = "success"
    reason: Optional[str] = None
    with transaction() as cur:
        shop = cur.execute(
            "SELECT status FROM shops WHERE shop_id=?",
            (payload.shop_id,),
        ).fetchone()
        if not shop or not is_shop_owner(cur, payload.shop_id, payload.owner_uuid) or shop["status"] != "active":
            result = "error"
            reason = "not_owner"
        else:
            item_key = payload.item_key
            if item_key is None and payload.sale_name:
                row = cur.execute(
                    "SELECT item_key FROM shop_stock WHERE shop_id=? AND sale_name=?",
                    (payload.shop_id, payload.sale_name),
                ).fetchone()
                if row:
                    item_key = row["item_key"]
            if not item_key:
                result = "error"
                reason = "item_not_found"
            else:
                if not is_currency(cur, payload.currency):
                    result = "error"
                    reason = "invalid_currency"
                else:
                    ts = int(time.time())
                    cur.execute(
                        """
                        INSERT INTO shop_prices(shop_id, item_key, currency, price) VALUES(?,?,?,?)
                        ON CONFLICT(shop_id,item_key,currency) DO UPDATE SET price=excluded.price
                        """,
                        (payload.shop_id, item_key, payload.currency, payload.price),
                    )
                    cur.execute(
                        "UPDATE shops SET last_activity_at=? WHERE shop_id=?",
                        (ts, payload.shop_id),
                    )
    latency_ms = int((time.time() - start) * 1000)
    log_entry = {
        "type": "shop_set_price",
        "timestamp": int(time.time()),
        "shop_id": payload.shop_id,
        "owner": payload.owner_uuid,
        "item_key": item_key,
        "sale_name": payload.sale_name,
        "currency": payload.currency,
        "price": payload.price,
        "result": result,
        "reason": reason,
        "latency_ms": latency_ms,
    }
    append_log(log_entry)
    if result == "success":
        return {"status": "success"}
    return {"status": "error", "reason": reason}


@app.post("/api/shop/remove_item")
async def shop_remove_item(payload: ShopRemoveItemPayload):
    start = time.time()
    grants: List[Dict[str, str]] = []
    result = "success"
    reason: Optional[str] = None
    with transaction() as cur:
        if not is_shop_owner(cur, payload.shop_id, payload.owner_uuid):
            result = "error"
            reason = "not_owner"
        else:
            row = cur.execute(
                "SELECT item_key, stock FROM shop_stock WHERE shop_id=? AND sale_name=?",
                (payload.shop_id, payload.sale_name),
            ).fetchone()
            if not row:
                result = "error"
                reason = "item_not_found"
            else:
                item_key = row["item_key"]
                if payload.refund and row["stock"] > 0:
                    blob_row = cur.execute(
                        "SELECT nbt_blob FROM shop_items WHERE item_key=?",
                        (item_key,),
                    ).fetchone()
                    if blob_row:
                        grants.append(
                            {
                                "item_key": item_key,
                                "qty": row["stock"],
                                "nbt_blob": base64.b64encode(blob_row["nbt_blob"]).decode("ascii"),
                                "grant_token": secrets.token_hex(8),
                            }
                        )
                cur.execute(
                    "DELETE FROM shop_stock WHERE shop_id=? AND item_key=?",
                    (payload.shop_id, item_key),
                )
                cur.execute(
                    "DELETE FROM shop_prices WHERE shop_id=? AND item_key=?",
                    (payload.shop_id, item_key),
                )
                ts = int(time.time())
                cur.execute(
                    "UPDATE shops SET last_activity_at=? WHERE shop_id=?",
                    (ts, payload.shop_id),
                )
    latency_ms = int((time.time() - start) * 1000)
    log_entry = {
        "type": "shop_remove_item",
        "timestamp": int(time.time()),
        "shop_id": payload.shop_id,
        "owner": payload.owner_uuid,
        "sale_name": payload.sale_name,
        "refund": payload.refund,
        "result": result,
        "reason": reason,
        "latency_ms": latency_ms,
    }
    append_log(log_entry)
    return {"status": result, "reason": reason, "grant": grants}


@app.post("/api/shop/add_owner")
async def shop_add_owner(payload: ShopAddOwnerPayload):
    start = time.time()
    result = "success"
    with transaction() as cur:
        if not is_shop_owner(cur, payload.shop_id, payload.owner_uuid):
            result = "error"
            reason = "not_owner"
        else:
            cur.execute(
                "INSERT OR IGNORE INTO shop_owners(shop_id, owner_uuid) VALUES(?,?)",
                (payload.shop_id, payload.target_uuid),
            )
    latency_ms = int((time.time() - start) * 1000)
    append_log(
        {
            "type": "shop_add_owner",
            "timestamp": int(time.time()),
            "shop_id": payload.shop_id,
            "actor": payload.owner_uuid,
            "target": payload.target_uuid,
            "result": result,
            "latency_ms": latency_ms,
        }
    )
    return {"status": result}


@app.post("/api/shop/remove_owner")
async def shop_remove_owner(payload: ShopRemoveOwnerPayload):
    start = time.time()
    result = "success"
    with transaction() as cur:
        if not is_shop_owner(cur, payload.shop_id, payload.owner_uuid):
            result = "error"
            reason = "not_owner"
        else:
            cur.execute(
                "DELETE FROM shop_owners WHERE shop_id=? AND owner_uuid=?",
                (payload.shop_id, payload.target_uuid),
            )
    latency_ms = int((time.time() - start) * 1000)
    append_log(
        {
            "type": "shop_remove_owner",
            "timestamp": int(time.time()),
            "shop_id": payload.shop_id,
            "actor": payload.owner_uuid,
            "target": payload.target_uuid,
            "result": result,
            "latency_ms": latency_ms,
        }
    )
    return {"status": result}


@app.post("/api/shop/listing")
async def shop_listing(payload: ShopListingPayload):
    start = time.time()
    result = "success"
    reason: Optional[str] = None
    with transaction() as cur:
        if not is_shop_owner(cur, payload.shop_id, payload.owner_uuid):
            result = "error"
            reason = "not_owner"
        else:
            cur.execute(
                "UPDATE shops SET listed=? WHERE shop_id=?",
                (1 if payload.listed else 0, payload.shop_id),
            )
    latency_ms = int((time.time() - start) * 1000)
    append_log(
        {
            "type": "shop_listing",
            "timestamp": int(time.time()),
            "shop_id": payload.shop_id,
            "actor": payload.owner_uuid,
            "listed": payload.listed,
            "result": result,
            "reason": reason,
            "latency_ms": latency_ms,
        }
    )
    if result == "success":
        return {"status": "success"}
    return {"status": "error", "reason": reason}


@app.post("/api/shop/visit")
async def shop_visit(payload: ShopVisitPayload):
    with transaction() as cur:
        cur.execute(
            "INSERT INTO shop_visits(shop_id, visitor_uuid, timestamp) VALUES(?,?,?)",
            (payload.shop_id, payload.player_uuid, payload.timestamp),
        )
    append_log(
        {
            "type": "shop_visit",
            "timestamp": payload.timestamp,
            "shop_id": payload.shop_id,
            "visitor": payload.player_uuid,
        }
    )
    return {"status": "ok"}


@app.post("/api/shop/ping")
async def shop_ping(payload: ShopPingPayload):
    start = time.time()
    with transaction() as cur:
        cur.execute(
            "UPDATE shops SET last_activity_at=? WHERE shop_id=? AND status='active'",
            (payload.timestamp, payload.shop_id),
        )
    latency_ms = int((time.time() - start) * 1000)
    log_entry = {
        "type": "shop_ping",
        "timestamp": payload.timestamp,
        "shop_id": payload.shop_id,
        "latency_ms": latency_ms,
    }
    append_log(log_entry)
    return {"status": "ok"}


@app.post("/api/shop/reopen")
async def shop_reopen(payload: ShopReopenPayload):
    start = time.time()
    result = "success"
    reason: Optional[str] = None
    with transaction() as cur:
        if not is_shop_owner(cur, payload.shop_id, payload.owner_uuid):
            result = "error"
            reason = "not_owner"
        else:
            cur.execute(
                "UPDATE shops SET status='active', last_activity_at=? WHERE shop_id=?",
                (payload.timestamp, payload.shop_id),
            )
    latency_ms = int((time.time() - start) * 1000)
    log_entry = {
        "type": "shop_reopen",
        "timestamp": payload.timestamp,
        "shop_id": payload.shop_id,
        "owner": payload.owner_uuid,
        "result": result,
        "reason": reason,
        "latency_ms": latency_ms,
    }
    append_log(log_entry)
    if result == "success":
        return {"status": "success"}
    return {"status": "error", "reason": reason}


@app.post("/api/shop/remove")
async def shop_remove(payload: ShopRemovePayload):
    start = time.time()
    grants: List[Dict[str, str]] = []
    location: Optional[Dict[str, Union[str, float]]] = None
    with transaction() as cur:
        if payload.refund:
            rows = cur.execute(
                "SELECT st.item_key, st.stock, it.nbt_blob FROM shop_stock st JOIN shop_items it ON st.item_key=it.item_key WHERE st.shop_id=?",
                (payload.shop_id,),
            ).fetchall()
            for r in rows:
                grants.append(
                    {
                        "item_key": r["item_key"],
                        "qty": r["stock"],
                        "nbt_blob": base64.b64encode(r["nbt_blob"]).decode("ascii"),
                        "grant_token": secrets.token_hex(8),
                    }
                )
            cur.execute("DELETE FROM shop_stock WHERE shop_id=?", (payload.shop_id,))
        loc_row = cur.execute(
            "SELECT world, x, y, z FROM shop_locations WHERE shop_id=?",
            (payload.shop_id,),
        ).fetchone()
        if loc_row:
            location = {
                "world": loc_row["world"],
                "x": loc_row["x"],
                "y": loc_row["y"],
                "z": loc_row["z"],
            }
        cur.execute("DELETE FROM shop_locations WHERE shop_id=?", (payload.shop_id,))
        cur.execute("UPDATE shops SET status='suspended' WHERE shop_id=?", (payload.shop_id,))
        cur.execute("DELETE FROM shop_owners WHERE shop_id=?", (payload.shop_id,))
    latency_ms = int((time.time() - start) * 1000)
    log_entry = {
        "type": "shop_remove",
        "timestamp": int(time.time()),
        "shop_id": payload.shop_id,
        "refund": payload.refund,
        "latency_ms": latency_ms,
    }
    append_log(log_entry)
    return {"status": "success", "grant": grants, "location": location}


class TileCoord(BaseModel):
    tx: int
    tz: int


class InvalidateRequest(BaseModel):
    world: str
    tiles: List[TileCoord]
    reason: Optional[str] = None


class ChunkData(BaseModel):
    cx: int
    cz: int
    data: str


@app.post("/api/cash/event")
def cash_event(ev: CashEvent, token: None = Depends(verify_token)):
    ts = int(time.time() * 1000)
    with cash_conn:
        cash_conn.execute(
            "INSERT INTO cash_events(note_id, player_uuid, action, currency, amount, location, ts) VALUES(?,?,?,?,?,?,?)",
            (
                ev.note_id,
                ev.player_uuid,
                ev.action,
                ev.currency,
                ev.amount,
                ev.location,
                ts,
            ),
        )
        if ev.action in ("issue", "transfer", "pickup", "store", "retrieve"):
            cash_conn.execute(
                "INSERT OR REPLACE INTO notes(note_id, currency, amount, owner_uuid) VALUES(?,?,?,?)",
                (ev.note_id, ev.currency, ev.amount, ev.player_uuid),
            )
        elif ev.action == "destroy":
            cash_conn.execute("DELETE FROM notes WHERE note_id=?", (ev.note_id,))
    return {"status": "ok"}


@app.get("/api/cash/holdings/{player_uuid}")
def cash_holdings(player_uuid: str, token: None = Depends(verify_token)):
    cur = cash_conn.execute(
        "SELECT currency, SUM(amount) AS total FROM notes WHERE owner_uuid=? GROUP BY currency",
        (player_uuid,),
    )
    return {"holdings": [dict(r) for r in cur.fetchall()]}


class ChunkSnapshotRequest(BaseModel):
    world: str
    y_start: int = 250
    chunks: List[ChunkData]
    ts: int


@app.post("/plugin/chunk/snapshot")
@app.post("/plugin/chunk_snapshot")
def chunk_snapshot(
    req: ChunkSnapshotRequest,
    request: Request,
    token: None = Depends(verify_token),
):
    ip = request.client.host if request.client else ""
    check_rate_limit(ip)
    for ch in req.chunks:
        tile_store.save_chunk(req.world, ch.cx, ch.cz, ch.data)
    append_log(
        {
            "type": "chunk_snapshot",
            "world": req.world,
            "chunks": len(req.chunks),
            "ip": ip,
        }
    )
    return {"status": "stored", "chunks": len(req.chunks)}


@app.get("/api/mapcolor/palette")
@app.get("/mapcolor/palette")
@app.get("/plugin/mapcolor/palette")
async def mapcolor_palette(token: None = Depends(verify_token)):
    async with httpx.AsyncClient() as client:
        resp = await client.get(
            f"{MAPCOLOR_URL}/plugin/mapcolor/palette",
            headers={"X-LE-Token": SHARED_TOKEN},
            timeout=10,
        )
    return Response(
        content=resp.content,
        status_code=resp.status_code,
        media_type=resp.headers.get("content-type"),
    )


@app.post("/api/mapcolor/resolve")
@app.post("/mapcolor/resolve")
@app.post("/plugin/mapcolor/resolve")
async def mapcolor_resolve(req: Request, token: None = Depends(verify_token)):
    body = await req.body()
    async with httpx.AsyncClient() as client:
        resp = await client.post(
            f"{MAPCOLOR_URL}/plugin/mapcolor/resolve",
            content=body,
            headers={
                "X-LE-Token": SHARED_TOKEN,
                "Content-Type": "application/json",
            },
            timeout=10,
        )
    return Response(
        content=resp.content,
        status_code=resp.status_code,
        media_type=resp.headers.get("content-type"),
    )


@app.get("/tiles/worlds")
@app.get("/api/tiles/worlds")
@app.get("/plugin/tiles/worlds")
def list_worlds(token: None = Depends(verify_token)):
    worlds = set()
    for name in os.listdir(tile_store.base_dir):
        m = re.match(r"tile_(.+?)_(-?\d+)_(-?\d+)\.tile\.zlib$", name)
        if m:
            worlds.add(m.group(1))
    return {"worlds": sorted(worlds)}


@app.api_route("/tiles/{world}/{tx}/{tz}", methods=["GET", "HEAD"])
@app.api_route("/api/tiles/{world}/{tx}/{tz}", methods=["GET", "HEAD"])
@app.api_route("/plugin/tiles/{world}/{tx}/{tz}", methods=["GET", "HEAD"])
def get_tile(
    world: str,
    tx: int,
    tz: int,
    request: Request,
    token: None = Depends(verify_token),
):
    data = tile_store.load_tile(world, tx, tz)
    if data is None:
        raise HTTPException(status_code=404, detail="tile not found")
    tile_store.touch_tile(world, tx, tz)
    meta = tile_store.tile_meta(world, tx, tz)
    last = meta.get("last_updated", 0)
    if_modified = request.headers.get("if-modified-since")
    if if_modified and last:
        try:
            ims = parsedate_to_datetime(if_modified)
            if last // 1000 <= int(ims.timestamp()):
                return Response(status_code=304)
        except Exception:
            pass
    headers = {}
    if last:
        headers["Last-Modified"] = formatdate(last / 1000, usegmt=True)
    if request.method == "HEAD":
        return Response(status_code=200, headers=headers)
    return Response(content=data, media_type="application/octet-stream", headers=headers)


@app.post("/tiles/invalidate")
def invalidate_tiles(req: InvalidateRequest, token: None = Depends(verify_token)):
    tile_store.invalidate(req.world, [t.dict() for t in req.tiles])
    return {"status": "queued"}


@app.get("/tiles/status")
def tiles_status(token: None = Depends(verify_token)):
    return tile_store.status()


@app.get("/metrics")
def metrics() -> Response:
    metrics = tile_store.metrics()
    body = "\n".join(f"{k} {v}" for k, v in metrics.items()) + "\n"
    return Response(content=body, media_type="text/plain")


@app.get("/shops")
def shops(token: None = Depends(verify_token)):
    rows = conn.execute(
        "SELECT sl.shop_id, sl.world, sl.x, sl.y, sl.z, s.status, s.listed FROM shop_locations sl JOIN shops s ON sl.shop_id = s.shop_id"
    ).fetchall()
    result = []
    for r in rows:
        result.append(
            {
                "shop_id": r["shop_id"],
                "world": r["world"],
                "x": r["x"],
                "y": r["y"],
                "z": r["z"],
                "status": r["status"],
                "listed": r["listed"],
            }
        )
    return result


@app.get("/api/shops/search")
def search_shops(
    currency: Optional[str] = None,
    min_price: Optional[int] = None,
    max_price: Optional[int] = None,
    item: Optional[str] = None,
):
    q = [
        "SELECT s.shop_id, si.display_name, ss.sale_name, sp.currency, sp.price, sl.world, sl.x, sl.y, sl.z",
        "FROM shop_prices sp",
        "JOIN shop_stock ss ON sp.shop_id=ss.shop_id AND sp.item_key=ss.item_key",
        "JOIN shop_items si ON sp.item_key=si.item_key",
        "JOIN shops s ON sp.shop_id=s.shop_id",
        "JOIN shop_locations sl ON s.shop_id=sl.shop_id",
        "WHERE s.listed=1",
    ]
    params: List[Any] = []
    if currency:
        q.append("AND sp.currency=?")
        params.append(currency)
    if min_price is not None:
        q.append("AND sp.price>=?")
        params.append(min_price)
    if max_price is not None:
        q.append("AND sp.price<=?")
        params.append(max_price)
    if item:
        q.append("AND (si.display_name LIKE ? OR ss.sale_name LIKE ?)")
        like = f"%{item}%"
        params.extend([like, like])
    q.append("ORDER BY sp.price ASC")
    rows = conn.execute(" ".join(q), params).fetchall()
    out = []
    for r in rows:
        out.append(
            {
                "shop_id": r["shop_id"],
                "item": r["display_name"] or r["sale_name"],
                "currency": r["currency"],
                "price": r["price"],
                "world": r["world"],
                "x": r["x"],
                "y": r["y"],
                "z": r["z"],
            }
        )
    return out


@app.get("/api/shops/compare")
def compare_shops(item: str, currency: str):
    rows = search_shops(currency=currency, item=item)
    return rows


@app.get("/logs/summary")
def logs_summary(
    since: Optional[int] = None, token: None = Depends(verify_token)
) -> Dict[str, Dict[str, int]]:
    """Aggregate JSONL audit logs.

    Returns counts and error totals grouped by entry ``type``. If ``since`` is
    provided, only log entries with ``ts`` greater than or equal to the value
    (UNIX milliseconds) are considered.
    """

    summary: Dict[str, Dict[str, int]] = {}
    if os.path.exists(LOG_PATH):
        with open(LOG_PATH, "r", encoding="utf-8") as f:
            for line in f:
                try:
                    entry = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if since is not None and entry.get("ts", 0) < since:
                    continue
                etype = entry.get("type")
                if not etype:
                    continue
                info = summary.setdefault(etype, {"count": 0, "errors": 0})
                info["count"] += 1
                if "error" in entry or entry.get("status") == "error":
                    info["errors"] += 1
    return summary


@app.websocket("/ws/tiles")
async def ws_tiles(ws: WebSocket):
    await ws.accept()
    WS_CLIENTS.append(ws)
    try:
        while True:
            await ws.receive_text()
    except WebSocketDisconnect:
        pass
    finally:
        if ws in WS_CLIENTS:
            WS_CLIENTS.remove(ws)
