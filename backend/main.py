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
from typing import Dict, Optional, List, Union, Tuple, Any, Set, Sequence, NamedTuple
from tile_store import TileStore
from tile_format import PIXEL_COUNT
import sqlite3
import json
from contextlib import closing, contextmanager, asynccontextmanager, suppress
from pathlib import Path
import yaml
import os
import logging

logging.basicConfig(level=logging.INFO)
import time
import shutil
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP, ROUND_FLOOR, getcontext
import threading
import asyncio
import secrets
import base64
import hashlib
import re
from email.utils import parsedate_to_datetime, formatdate

from decimal_config import (
    DECIMAL_PLACES_KEY,
    ALLOWED_DECIMAL_PLACES,
    DEFAULT_DECIMAL_PLACES,
    LEGACY_DEFAULT_DECIMAL_PLACES,
    clamp_decimal_places,
    compute_scale,
    rescale_value,
)

getcontext().prec = 28


def _round_half_up(value: Union[int, float, Decimal]) -> int:
    return int(Decimal(value).quantize(Decimal("1"), rounding=ROUND_HALF_UP))


def _dec_ceil(value: Decimal) -> int:
    return int((-value).to_integral_value(rounding=ROUND_FLOOR) * -1)


def _autoprice_single(stock: int, lower: int, upper: int, high: int, low: int) -> int:
    if stock <= lower:
        return _round_half_up(high)
    if stock >= upper:
        return _round_half_up(low)
    if upper <= lower:
        return _round_half_up(high)
    s = Decimal(stock)
    l = Decimal(lower)
    u = Decimal(upper)
    ph = Decimal(high)
    pl = Decimal(low)
    slope = (pl - ph) / (u - l)
    raw = ph + slope * (s - l)
    return int(raw.quantize(Decimal("1"), rounding=ROUND_HALF_UP))


def _autoprice_range(start: int, end: int, lower: int, upper: int, high: int, low: int) -> int:
    if start > end:
        return 0
    total = 0
    if upper <= lower:
        return (end - start + 1) * _round_half_up(high)
    low_lo, low_hi = start, min(end, lower)
    if low_hi >= low_lo:
        total += (low_hi - low_lo + 1) * _round_half_up(high)
    up_lo, up_hi = max(start, upper), end
    if up_hi >= up_lo:
        total += (up_hi - up_lo + 1) * _round_half_up(low)
    mid_lo, mid_hi = max(start, lower + 1), min(end, upper - 1)
    if mid_hi >= mid_lo:
        ph = Decimal(high)
        pl = Decimal(low)
        l = Decimal(lower)
        u = Decimal(upper)
        slope = (pl - ph) / (u - l)
        if slope == 0:
            rounded = int(ph.quantize(Decimal("1"), rounding=ROUND_HALF_UP))
            total += (mid_hi - mid_lo + 1) * rounded
        else:
            intercept = ph - slope * l

            def raw_price(x: int) -> Decimal:
                return ph + slope * (Decimal(x) - l)

            candidates = [
                _round_half_up(raw_price(mid_lo)),
                _round_half_up(raw_price(mid_hi)),
                _round_half_up(raw_price(lower + 1)),
                _round_half_up(raw_price(upper - 1)),
            ]
            k_min, k_max = min(candidates), max(candidates)
            for k in range(k_min, k_max + 1):
                bound_low = (Decimal(k) - Decimal("0.5") - intercept) / slope
                bound_high = (Decimal(k) + Decimal("0.5") - intercept) / slope
                lo, hi = (bound_low, bound_high) if bound_low <= bound_high else (bound_high, bound_low)
                s_lo = _dec_ceil(lo)
                s_hi = _dec_ceil(hi) - 1
                a, b = max(mid_lo, s_lo), min(mid_hi, s_hi)
                if b >= a:
                    total += (b - a + 1) * k
    return int(total)


def autoprice_total_buy(stock: int, qty: int, lower: int, upper: int, high: int, low: int) -> int:
    if qty <= 0:
        return 0
    return _autoprice_range(stock - qty + 1, stock, lower, upper, high, low)


def autoprice_total_sell(stock: int, qty: int, lower: int, upper: int, high: int, low: int) -> int:
    if qty <= 0:
        return 0
    return _autoprice_range(stock, stock + qty - 1, lower, upper, high, low)


def autoprice_price_after(stock: int, lower: int, upper: int, high: int, low: int) -> int:
    return _autoprice_single(stock, lower, upper, high, low)

BYPASS_FILE = Path(__file__).resolve().parent / "bypass.txt"
BYPASS_USERS: Set[str] = set()

# SQLite persistence
conn = sqlite3.connect(
    "economy.db", check_same_thread=False, isolation_level=None
)
conn.row_factory = sqlite3.Row
conn.execute("PRAGMA journal_mode=WAL")
conn.execute("PRAGMA synchronous=NORMAL")

def load_bypass_users() -> None:
    global BYPASS_USERS
    try:
        raw = BYPASS_FILE.read_text(encoding="utf-8")
    except FileNotFoundError:
        logging.warning("Bypass file not found: %s", BYPASS_FILE)
        entries: Set[str] = set()
    else:
        entries = {
            line.strip().lower()
            for line in raw.splitlines()
            if line.strip() and not line.strip().startswith("#")
        }
    BYPASS_USERS = entries
    if not entries:
        return
    with conn:
        for name in entries:
            conn.execute(
                "INSERT OR IGNORE INTO admin_users(name) VALUES(?)",
                (name,),
            )


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
            active INTEGER NOT NULL DEFAULT 1,
            treasury TEXT,
            tax_rate INTEGER NOT NULL DEFAULT 0,
            trade_tax_enabled INTEGER NOT NULL DEFAULT 0,
            trade_tax_rate INTEGER NOT NULL DEFAULT 0,
            transfer_tax_enabled INTEGER NOT NULL DEFAULT 0,
            transfer_tax_rate INTEGER NOT NULL DEFAULT 0
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
    try:
        conn.execute(
            "ALTER TABLE currencies ADD COLUMN trade_tax_enabled INTEGER NOT NULL DEFAULT 0"
        )
    except sqlite3.OperationalError:
        pass
    try:
        conn.execute(
            "ALTER TABLE currencies ADD COLUMN trade_tax_rate INTEGER NOT NULL DEFAULT 0"
        )
    except sqlite3.OperationalError:
        pass
    try:
        conn.execute(
            "ALTER TABLE currencies ADD COLUMN transfer_tax_enabled INTEGER NOT NULL DEFAULT 0"
        )
    except sqlite3.OperationalError:
        pass
    try:
        conn.execute(
            "ALTER TABLE currencies ADD COLUMN transfer_tax_rate INTEGER NOT NULL DEFAULT 0"
        )
    except sqlite3.OperationalError:
        pass
    conn.execute(
        """
        UPDATE currencies
        SET transfer_tax_rate=tax_rate,
            transfer_tax_enabled=CASE WHEN tax_rate>0 THEN 1 ELSE transfer_tax_enabled END,
            tax_rate=0
        WHERE tax_rate>0
            AND transfer_tax_rate=0
            AND transfer_tax_enabled=0
        """
    )
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
            listed INTEGER NOT NULL DEFAULT 1,
            account_uuid TEXT,
            trade_mode TEXT NOT NULL DEFAULT 'both'
        )
        """
    )
    try:
        conn.execute("ALTER TABLE shops ADD COLUMN account_uuid TEXT")
    except sqlite3.OperationalError:
        pass
    try:
        conn.execute("ALTER TABLE shops ADD COLUMN listed INTEGER NOT NULL DEFAULT 1")
    except sqlite3.OperationalError:
        pass
    try:
        conn.execute(
            "ALTER TABLE shops ADD COLUMN trade_mode TEXT NOT NULL DEFAULT 'both'"
        )
    except sqlite3.OperationalError:
        pass
    conn.execute("UPDATE shops SET account_uuid=owner_uuid WHERE account_uuid IS NULL")
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
            buy_price INTEGER,
            PRIMARY KEY(shop_id, item_key, currency),
            FOREIGN KEY(shop_id) REFERENCES shops(shop_id),
            FOREIGN KEY(item_key) REFERENCES shop_items(item_key)
        )
        """
    )
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS shop_autoprice (
            shop_id TEXT NOT NULL,
            item_key TEXT NOT NULL,
            currency TEXT NOT NULL,
            lower_threshold INTEGER NOT NULL,
            upper_threshold INTEGER NOT NULL,
            high_price INTEGER NOT NULL,
            low_price INTEGER NOT NULL,
            PRIMARY KEY(shop_id, item_key, currency),
            FOREIGN KEY(shop_id) REFERENCES shops(shop_id),
            FOREIGN KEY(item_key) REFERENCES shop_items(item_key)
        )
        """
    )
    try:
        conn.execute("ALTER TABLE shop_prices ADD COLUMN buy_price INTEGER")
    except sqlite3.OperationalError:
        pass
    conn.execute(
        "UPDATE shop_prices SET buy_price=price WHERE buy_price IS NULL"
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
            tax_amount INTEGER NOT NULL DEFAULT 0,
            tax_account TEXT,
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
    try:
        conn.execute(
            "ALTER TABLE shop_tx ADD COLUMN tax_amount INTEGER NOT NULL DEFAULT 0"
        )
    except sqlite3.OperationalError:
        pass
    try:
        conn.execute("ALTER TABLE shop_tx ADD COLUMN tax_account TEXT")
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
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS player_quests (
            player_uuid TEXT NOT NULL,
            quest_id TEXT NOT NULL,
            completed_at INTEGER NOT NULL,
            PRIMARY KEY(player_uuid, quest_id)
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
            owner_uuid TEXT NOT NULL,
            currency TEXT NOT NULL,
            amount INTEGER NOT NULL,
            PRIMARY KEY(owner_uuid, currency)
        )
        """
    )
    try:
        cash_conn.execute("ALTER TABLE notes DROP COLUMN quantity")
    except sqlite3.OperationalError:
        pass
    cash_conn.execute(
        """
        CREATE TABLE IF NOT EXISTS cash_events (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            player_uuid TEXT NOT NULL,
            action TEXT NOT NULL,
            currency TEXT NOT NULL,
            amount INTEGER NOT NULL,
            quantity INTEGER NOT NULL,
            location TEXT,
            ts INTEGER NOT NULL
        )
        """
    )

decimal_places = DEFAULT_DECIMAL_PLACES
AMOUNT_SCALE = compute_scale(decimal_places)

SHARED_TOKEN = os.environ.get("LE_TOKEN", "devtoken")

from palette import PALETTE, resolve_block


RATE_LIMIT: Dict[str, Tuple[float, int]] = {}
RATE_LIMIT_MAX = 10


def verify_token(x_le_token: str = Header(...)) -> None:
    if SHARED_TOKEN and x_le_token != SHARED_TOKEN:
        raise HTTPException(status_code=401, detail="invalid token")


def verify_token_optional(x_le_token: str | None = Header(None)) -> None:
    if SHARED_TOKEN and x_le_token and x_le_token != SHARED_TOKEN:
        raise HTTPException(status_code=401, detail="invalid token")


ALLOWED_PLUGIN_HOSTS = {"127.0.0.1", "::1", "localhost", "testclient"}


def _is_trusted_client(host: str) -> bool:
    if not host:
        return False
    if host in ALLOWED_PLUGIN_HOSTS:
        return True
    if host.startswith("127."):
        return True
    if host.startswith("::ffff:127."):
        return True
    return False


def ensure_plugin_request(
    request: Request, token: None = Depends(verify_token)
) -> None:
    host = request.client.host if request.client else ""
    if not _is_trusted_client(host):
        raise HTTPException(status_code=403, detail="forbidden")


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
    global _tile_worker_task, EVENT_LOOP
    EVENT_LOOP = asyncio.get_running_loop()
    _tile_worker_task = asyncio.create_task(_tile_worker())


@app.on_event("shutdown")
async def _stop_tile_worker() -> None:
    if _tile_worker_task:
        _tile_worker_task.cancel()
        with suppress(Exception):
            await _tile_worker_task


@app.on_event("startup")
async def _log_world_dir_status() -> None:
    """Record the WORLD_DIR environment variable on startup."""
    root = os.environ.get("WORLD_DIR")
    candidates: List[str] = []
    resolved = None
    exists = bool(root and os.path.isdir(root))
    if root:
        candidates = [os.path.join(root, "region"), root]
        for cand in candidates:
            if os.path.isdir(cand):
                has_mca = any(fn.endswith(".mca") for fn in os.listdir(cand))
                app.logger.info(
                    "WORLD_DIR candidate %s exists=%s mca=%s", cand, True, has_mca
                )
                if has_mca:
                    resolved = cand
                    break
            else:
                app.logger.info("WORLD_DIR candidate %s exists=%s", cand, False)
    if not root:
        app.logger.warning("WORLD_DIR is not set")
    else:
        app.logger.info(
            "WORLD_DIR env=%s exists=%s resolved=%s", root, exists, resolved
        )
    append_log(
        {
            "type": "world_dir",
            "world_dir": root,
            "exists": exists,
            "resolved": resolved,
            "candidates": candidates,
            "ts": int(time.time() * 1000),
        }
    )

load_bypass_users()


def has_admin_access(name: str, cur: Optional[sqlite3.Cursor] = None) -> bool:
    if not name:
        return False
    lowered = name.lower()
    if lowered in BYPASS_USERS:
        return True
    if cur is not None:
        row = cur.execute(
            "SELECT 1 FROM admin_users WHERE name=?",
            (lowered,),
        ).fetchone()
    else:
        row = conn.execute(
            "SELECT 1 FROM admin_users WHERE name=?",
            (lowered,),
        ).fetchone()
    return row is not None

db_lock = threading.Lock()

undo_stacks: Dict[str, List[List[Dict[str, Union[str, int, None]]]]] = {}
redo_stack: Dict[str, Optional[List[Dict[str, Union[str, int, None]]]]] = {}


def _apply_decimal_places(new_places: int) -> None:
    global decimal_places, AMOUNT_SCALE
    decimal_places = clamp_decimal_places(new_places)
    AMOUNT_SCALE = compute_scale(decimal_places)
    undo_stacks.clear()
    redo_stack.clear()


def _fetch_decimal_places(cur: sqlite3.Cursor) -> Optional[int]:
    row = cur.execute(
        "SELECT value FROM settings WHERE key=?", (DECIMAL_PLACES_KEY,)
    ).fetchone()
    if not row:
        return None
    try:
        return int(row["value"])
    except (TypeError, ValueError):
        return None


def _rescale_table(
    cur: sqlite3.Cursor,
    table: str,
    key_columns: Sequence[str],
    value_columns: Sequence[str],
    old_places: int,
    new_places: int,
) -> None:
    if old_places == new_places:
        return
    select_cols = list(dict.fromkeys([*key_columns, *value_columns]))
    rows = cur.execute(
        f"SELECT {', '.join(select_cols)} FROM {table}"
    ).fetchall()
    for row in rows:
        updates: List[str] = []
        values: List[int] = []
        for column in value_columns:
            current = row[column]
            if current is None:
                continue
            scaled = rescale_value(current, old_places, new_places)
            if scaled != current:
                updates.append(column)
                values.append(scaled)
        if updates:
            set_clause = ", ".join(f"{col}=?" for col in updates)
            where_clause = " AND ".join(f"{col}=?" for col in key_columns)
            params = values + [row[col] for col in key_columns]
            cur.execute(
                f"UPDATE {table} SET {set_clause} WHERE {where_clause}", params
            )


def _rescale_cash_db(old_places: int, new_places: int) -> None:
    if old_places == new_places:
        return
    cur = cash_conn.cursor()
    try:
        _rescale_table(
            cur,
            "notes",
            ("owner_uuid", "currency"),
            ("amount",),
            old_places,
            new_places,
        )
        _rescale_table(
            cur,
            "cash_events",
            ("id",),
            ("amount",),
            old_places,
            new_places,
        )
    finally:
        cur.close()


def _update_decimal_places(desired_places: int) -> int:
    desired = clamp_decimal_places(desired_places)
    with transaction() as cur:
        stored = _fetch_decimal_places(cur)
        if stored is None:
            stored = LEGACY_DEFAULT_DECIMAL_PLACES
        if stored != desired:
            _rescale_table(cur, "accounts", ("uuid", "currency"), ("balance",), stored, desired)
            _rescale_table(cur, "transactions", ("id",), ("amount",), stored, desired)
            _rescale_table(
                cur,
                "shop_prices",
                ("shop_id", "item_key", "currency"),
                ("price", "buy_price"),
                stored,
                desired,
            )
            _rescale_table(
                cur,
                "shop_autoprice",
                ("shop_id", "item_key", "currency"),
                ("high_price", "low_price"),
                stored,
                desired,
            )
            _rescale_table(
                cur,
                "shop_tx",
                ("id",),
                ("total_price",),
                stored,
                desired,
            )
        cur.execute(
            """
            INSERT INTO settings(key, value)
            VALUES(?, ?)
            ON CONFLICT(key) DO UPDATE SET value=excluded.value
            """,
            (DECIMAL_PLACES_KEY, str(desired)),
        )
    if stored != desired:
        _rescale_cash_db(stored, desired)
    _apply_decimal_places(desired)
    return decimal_places


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


_update_decimal_places(DEFAULT_DECIMAL_PLACES)


BASE_DIR = Path(__file__).resolve().parent
with open(BASE_DIR / "lang.yml", encoding="utf-8") as f:
    LANG = yaml.safe_load(f)


QUEST_DEFINITIONS: Dict[str, Dict[str, str]] = {
    "shop_create": {"message_key": "quest.complete.shop_create"},
    "shop_buy": {"message_key": "quest.complete.shop_buy"},
    "shop_sell": {"message_key": "quest.complete.shop_sell"},
}

QUEST_REWARD_CURRENCY = "thy"
QUEST_REWARD_SYMBOL: Optional[str] = None
QUEST_REWARD_POINTS_UNITS = 100


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


def get_lang_tx(cur: sqlite3.Cursor, uuid: str) -> str:
    row = cur.execute("SELECT lang FROM player_lang WHERE uuid=?", (uuid,)).fetchone()
    return row["lang"] if row and row["lang"] else "en"


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
    sell_price: Optional[int] = None
    buy_price: Optional[int] = None


class ShopTakeStockPayload(BaseModel):
    owner_uuid: str
    shop_id: str
    item_key: Optional[str] = None
    item_keys: Optional[List[str]] = None
    qty: int


class ShopSetPricePayload(BaseModel):
    owner_uuid: str
    shop_id: str
    item_key: Optional[str] = None
    sale_name: Optional[str] = None
    currency: str
    price: int
    price_kind: str = "sell"


class ShopAutoPricePayload(BaseModel):
    owner_uuid: str
    shop_id: str
    item_key: Optional[str] = None
    sale_name: Optional[str] = None
    currency: Optional[str] = None
    lower_threshold: int
    high_price: int
    upper_threshold: int
    low_price: int


class ShopAutoPriceDisablePayload(BaseModel):
    owner_uuid: str
    shop_id: str
    item_key: Optional[str] = None
    sale_name: Optional[str] = None
    currency: Optional[str] = None


class ShopPingPayload(BaseModel):
    shop_id: str
    timestamp: int


class ShopReopenPayload(BaseModel):
    owner_uuid: str
    shop_id: str
    timestamp: int


class CashEvent(BaseModel):
    player_uuid: str
    action: str
    currency: str
    amount: int
    quantity: int = 1
    location: Optional[str] = None


class DecimalConfigPayload(BaseModel):
    decimal_places: int


class ShopRemovePayload(BaseModel):
    owner_uuid: Optional[str] = None
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


class ShopAccountPayload(BaseModel):
    owner_uuid: str
    shop_id: str
    account_id: str


class AccountEnsurePayload(BaseModel):
    player_uuid: str


class ShopModePayload(BaseModel):
    owner_uuid: str
    shop_id: str
    mode: str


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


def get_autoprice_config(
    cur: sqlite3.Cursor, shop_id: str, item_key: str, currency: str
) -> Optional[sqlite3.Row]:
    return cur.execute(
        """
        SELECT lower_threshold, upper_threshold, high_price, low_price
        FROM shop_autoprice
        WHERE shop_id=? AND item_key=? AND currency=?
        """,
        (shop_id, item_key, currency),
    ).fetchone()


def apply_autoprice(
    cur: sqlite3.Cursor, shop_id: str, item_key: str, currency: str
) -> Optional[Tuple[int, Optional[int]]]:
    config = get_autoprice_config(cur, shop_id, item_key, currency)
    if not config:
        return None
    stock_row = cur.execute(
        "SELECT stock FROM shop_stock WHERE shop_id=? AND item_key=?",
        (shop_id, item_key),
    ).fetchone()
    stock = stock_row["stock"] if stock_row else 0
    new_price = autoprice_price_after(
        stock,
        config["lower_threshold"],
        config["upper_threshold"],
        config["high_price"],
        config["low_price"],
    )
    existing = cur.execute(
        "SELECT price, buy_price FROM shop_prices WHERE shop_id=? AND item_key=? AND currency=?",
        (shop_id, item_key, currency),
    ).fetchone()
    if existing:
        buy_price = existing["buy_price"]
        old_price = existing["price"]
        update_buy = buy_price is None or buy_price == old_price
        cur.execute(
            "UPDATE shop_prices SET price=?, buy_price=? WHERE shop_id=? AND item_key=? AND currency=?",
            (
                new_price,
                new_price if update_buy else buy_price,
                shop_id,
                item_key,
                currency,
            ),
        )
        return new_price, new_price if update_buy else buy_price
    cur.execute(
        "INSERT INTO shop_prices(shop_id, item_key, currency, price, buy_price) VALUES(?,?,?,?,?)",
        (shop_id, item_key, currency, new_price, new_price),
    )
    return new_price, new_price


def get_uuid(name: str) -> Optional[str]:
    with closing(conn.cursor()) as cur:
        row = cur.execute("SELECT uuid FROM name_index WHERE name=?", (name.lower(),)).fetchone()
        return row["uuid"] if row else None


def get_name(uuid: str) -> Optional[str]:
    with closing(conn.cursor()) as cur:
        row = cur.execute("SELECT name FROM name_index WHERE uuid=? LIMIT 1", (uuid,)).fetchone()
        return row["name"] if row else None


def purge_shop(cur: sqlite3.Cursor, shop_id: str) -> None:
    cur.execute("DELETE FROM shop_locations WHERE shop_id=?", (shop_id,))
    cur.execute("DELETE FROM shop_stock WHERE shop_id=?", (shop_id,))
    cur.execute("DELETE FROM shop_prices WHERE shop_id=?", (shop_id,))
    cur.execute("DELETE FROM shop_autoprice WHERE shop_id=?", (shop_id,))
    cur.execute("DELETE FROM shop_tx WHERE shop_id=?", (shop_id,))
    cur.execute("DELETE FROM shop_owners WHERE shop_id=?", (shop_id,))
    cur.execute("DELETE FROM shop_visits WHERE shop_id=?", (shop_id,))
    cur.execute("DELETE FROM shops WHERE shop_id=?", (shop_id,))


def purge_orphan_shops() -> None:
    with transaction() as cur:
        rows = cur.execute(
            "SELECT shop_id FROM shops WHERE shop_id NOT IN (SELECT shop_id FROM shop_owners)"
        ).fetchall()
        for r in rows:
            purge_shop(cur, r["shop_id"])


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


class TaxConfig(NamedTuple):
    rate: int
    enabled: bool
    treasury: Optional[str]


def compute_tax_amount(amount: int, tax: TaxConfig) -> int:
    if not tax.enabled or tax.rate <= 0 or amount <= 0:
        return 0
    return max((amount * tax.rate + 500) // 1000, 0)


def tax_info(cur: sqlite3.Cursor, currency: str, kind: str) -> TaxConfig:
    row = cur.execute(
        """
        SELECT treasury, trade_tax_rate, trade_tax_enabled,
               transfer_tax_rate, transfer_tax_enabled
        FROM currencies WHERE name=?
        """,
        (currency,),
    ).fetchone()
    if not row:
        return TaxConfig(0, False, None)
    treasury = row["treasury"]
    if kind == "trade":
        return TaxConfig(row["trade_tax_rate"], bool(row["trade_tax_enabled"]), treasury)
    return TaxConfig(
        row["transfer_tax_rate"], bool(row["transfer_tax_enabled"]), treasury
    )


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


def increment_quest_progress(
    cur: sqlite3.Cursor, player_uuid: str, quest_id: str, amount: int
) -> bool:
    """Increment quest progress when the optional quest tables exist.

    Some deployments omit the quest schema entirely. In that case we should
    ignore the missing table instead of aborting the surrounding shop
    transaction.
    """

    try:
        cur.execute(
            "UPDATE quest_progress SET progress=progress+? WHERE player_uuid=? AND quest_id=?",
            (amount, player_uuid, quest_id),
        )
        return True
    except sqlite3.OperationalError as exc:
        if "no such table" in str(exc).lower() and "quest_progress" in str(exc).lower():
            return False
        raise


def format_amount(cur: sqlite3.Cursor, amount: int, currency: str) -> str:
    row = cur.execute("SELECT symbol FROM currencies WHERE name=?", (currency,)).fetchone()
    symbol = ""
    if row and row["symbol"] and row["symbol"] != currency:
        symbol = row["symbol"]
    sign = "-" if amount < 0 else ""
    amt = abs(amount)
    if decimal_places > 0:
        whole, frac = divmod(amt, AMOUNT_SCALE)
        if frac:
            frac_str = f"{frac:0{decimal_places}d}"
            return f"§e{sign}{symbol}{whole:,}.{frac_str}§r"
        return f"§e{sign}{symbol}{whole:,}§r"
    return f"§e{sign}{symbol}{amt:,}§r"


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


def complete_quest(
    cur: sqlite3.Cursor,
    player_uuid: Optional[str],
    quest_id: str,
    timestamp: int,
    *,
    messages: Optional[List[Dict[str, str]]] = None,
    scoreboards: Optional[Dict[str, Dict[str, int]]] = None,
) -> None:
    if not player_uuid or quest_id not in QUEST_DEFINITIONS:
        return
    if cur.execute(
        "SELECT 1 FROM player_quests WHERE player_uuid=? AND quest_id=?",
        (player_uuid, quest_id),
    ).fetchone():
        return
    cur.execute(
        "INSERT INTO player_quests(player_uuid, quest_id, completed_at) VALUES(?,?,?)",
        (player_uuid, quest_id, timestamp),
    )
    ensure_currency(cur, QUEST_REWARD_CURRENCY, QUEST_REWARD_SYMBOL)
    add_balance(
        cur,
        player_uuid,
        QUEST_REWARD_CURRENCY,
        QUEST_REWARD_POINTS_UNITS * AMOUNT_SCALE,
    )
    if scoreboards is not None:
        scoreboards[player_uuid] = get_scoreboard(cur, player_uuid)
    lang = get_lang_tx(cur, player_uuid)
    text = t(QUEST_DEFINITIONS[quest_id]["message_key"], lang=lang)
    msg = {"target": "chat", "player": player_uuid, "text": text}
    if messages is not None:
        messages.append(msg)
    else:
        queue_message(cur, msg)


@app.get("/api/quests/recommended")
def recommended_quests(
    player_uuid: str,
    _auth: None = Depends(ensure_plugin_request),
):
    completed = {
        row["quest_id"]
        for row in conn.execute(
            "SELECT quest_id FROM player_quests WHERE player_uuid=?",
            (player_uuid,),
        )
    }
    quests = [qid for qid in QUEST_DEFINITIONS.keys() if qid not in completed]
    return {"quests": quests}


@app.get("/api/shops/recommended")
def recommended_shops(
    limit: int = 5,
    _auth: None = Depends(ensure_plugin_request),
):
    capped_limit = max(1, min(limit, 7))
    shop_rows = conn.execute(
        """
        SELECT shop_id, trade_mode
        FROM shops
        WHERE status='active' AND listed=1
        ORDER BY RANDOM()
        LIMIT ?
        """,
        (capped_limit,),
    ).fetchall()
    shops: List[Dict[str, object]] = []
    for shop in shop_rows:
        shop_id = shop["shop_id"]
        trade_mode = (shop["trade_mode"] or "both").lower()
        location_rows = conn.execute(
            """
            SELECT world, x, y, z
            FROM shop_locations
            WHERE shop_id=?
            ORDER BY rowid
            """,
            (shop_id,),
        ).fetchall()
        locations: List[Dict[str, object]] = []
        for location_row in location_rows:
            if not location_row:
                continue
            location_entry = {
                "world": location_row["world"],
                "x": location_row["x"],
                "y": location_row["y"],
                "z": location_row["z"],
            }
            locations.append(location_entry)
        location = locations[0] if locations else None
        price_rows = conn.execute(
            """
            SELECT ss.sale_name, sp.currency, sp.price, sp.buy_price
            FROM shop_stock ss
            JOIN shop_prices sp
              ON sp.shop_id = ss.shop_id AND sp.item_key = ss.item_key
            WHERE ss.shop_id = ?
            ORDER BY ss.sale_name COLLATE NOCASE, sp.currency COLLATE NOCASE
            """,
            (shop_id,),
        ).fetchall()
        grouped: Dict[str, Dict[str, object]] = {}
        for row in price_rows:
            sale_name = row["sale_name"]
            entry = grouped.setdefault(
                sale_name,
                {"name": sale_name, "sell": [], "buy": []},
            )
            price = row["price"]
            buy_price = row["buy_price"]
            currency = row["currency"]
            if price is not None and price > 0 and trade_mode in ("sell", "both"):
                entry["sell"].append({"currency": currency, "amount": int(price)})
            if buy_price is not None and buy_price > 0 and trade_mode in ("buy", "both"):
                entry["buy"].append({"currency": currency, "amount": int(buy_price)})
        listings: List[Dict[str, object]] = []
        for listing in grouped.values():
            if listing["sell"] or listing["buy"]:
                listings.append(listing)
        listings.sort(key=lambda item: str(item.get("name", "")).lower())
        if listings:
            listings = listings[:5]
        shops.append(
            {
                "shop_id": shop_id,
                "trade_mode": trade_mode,
                "location": location,
                "locations": locations,
                "listings": listings,
            }
        )
    return {"shops": shops}


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
EVENT_LOOP: Optional[asyncio.AbstractEventLoop] = None


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
    try:
        loop = asyncio.get_running_loop()
    except RuntimeError:
        loop = EVENT_LOOP
        if loop is None:
            loop = asyncio.get_event_loop()
        if loop.is_running():
            future = asyncio.run_coroutine_threadsafe(
                _broadcast_tile_update(world, tx, tz), loop
            )
            with suppress(Exception):
                future.result(timeout=2)
        else:
            loop.run_until_complete(_broadcast_tile_update(world, tx, tz))
        return
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


@app.get("/api/admin/bypass")
async def get_bypass_list():
    return {"users": sorted(BYPASS_USERS)}


@app.post("/api/admin/add")
async def add_admin(payload: AdminUserPayload):
    with conn:
        conn.execute(
            "INSERT OR IGNORE INTO admin_users(name) VALUES(?)", (payload.name,)
        )
    return {"status": "success"}


@app.post("/api/admin/remove")
async def remove_admin(payload: AdminUserPayload):
    if payload.name.lower() in BYPASS_USERS:
        return {"status": "skipped", "reason": "bypass_protected"}
    with conn:
        conn.execute("DELETE FROM admin_users WHERE name=?", (payload.name,))
    return {"status": "success"}


@app.get("/api/config")
async def get_config():
    return {
        "timeout": 2000,
        "sync_interval": 1,
        "decimal_places": decimal_places,
        "allowed_decimal_places": list(ALLOWED_DECIMAL_PLACES),
    }


@app.post("/api/config/decimal")
async def set_decimal_config(
    payload: DecimalConfigPayload,
    _auth: None = Depends(ensure_plugin_request),
):
    new_value = _update_decimal_places(payload.decimal_places)
    return {"status": "ok", "decimal_places": new_value}


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
            amt_dec *= Decimal(AMOUNT_SCALE)
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
            is_exec_admin = has_admin_access(payload.executor, cur)
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
                    elif sub == "tax" and len(cmd) >= 5:
                        cname = resolve_currency(cur, cmd[2])
                        mode = cmd[3].lower()
                        if mode not in {"trade", "transfer"}:
                            success = False
                            error_text = t("error.invalid_args", lang=exec_lang)
                        elif not (
                            is_exec_admin
                            or is_currency_manager(cur, exec_uuid, cname)
                        ):
                            success = False
                            error_text = t("error.no_permission", lang=exec_lang)
                        else:
                            value_token = cmd[4].lower()
                            explicit_toggle: Optional[int] = None
                            if len(cmd) >= 6:
                                toggle = cmd[5].lower()
                                if toggle in {"on", "enable"}:
                                    explicit_toggle = 1
                                elif toggle in {"off", "disable"}:
                                    explicit_toggle = 0
                                else:
                                    success = False
                                    error_text = t("error.invalid_args", lang=exec_lang)
                            if success:
                                if value_token == "off":
                                    rate = 0
                                    enabled = 0
                                else:
                                    try:
                                        rate_dec = Decimal(value_token)
                                    except InvalidOperation:
                                        success = False
                                        error_text = t("error.invalid_args", lang=exec_lang)
                                    else:
                                        rate = int(
                                            (rate_dec * 10).to_integral_value(
                                                rounding=ROUND_HALF_UP
                                            )
                                        )
                                        rate = max(rate, 0)
                                        enabled = 1 if rate > 0 else 0
                                if success:
                                    if explicit_toggle is not None:
                                        enabled = explicit_toggle
                                    column_rate = (
                                        "trade_tax_rate"
                                        if mode == "trade"
                                        else "transfer_tax_rate"
                                    )
                                    column_enabled = (
                                        "trade_tax_enabled"
                                        if mode == "trade"
                                        else "transfer_tax_enabled"
                                    )
                                    cur.execute(
                                        f"UPDATE currencies SET {column_rate}=?, {column_enabled}=? WHERE name=?",
                                        (rate, enabled, cname),
                                    )
                                    mode_label = t(
                                        f"currency.tax_mode_{mode}", lang=exec_lang
                                    )
                                    if enabled:
                                        messages.append(
                                            {
                                                "target": "chat",
                                                "text": t(
                                                    "currency.tax_set",
                                                    lang=exec_lang,
                                                    currency=cname,
                                                    rate=f"{rate/10:.1f}",
                                                    mode=mode_label,
                                                ),
                                            }
                                        )
                                    else:
                                        messages.append(
                                            {
                                                "target": "chat",
                                                "text": t(
                                                    "currency.tax_disabled",
                                                    lang=exec_lang,
                                                    currency=cname,
                                                    mode=mode_label,
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
                            transfer_tax = tax_info(cur, currency, "transfer")
                            tax_amt = min(compute_tax_amount(amt, transfer_tax), amt)
                            net_amt = amt - tax_amt
                            treasury = transfer_tax.treasury
                            required = amt
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
                                    credited = True
                                    if net_amt > 0:
                                        credited = add_balance(
                                            cur, dst_uuid, currency, net_amt
                                        )
                                    if not credited:
                                        add_balance(cur, src_uuid, currency, required)
                                        success = False
                                        error_text = t("error.pay_failed", lang=exec_lang)
                                    else:
                                        tax_recorded = True
                                        if tax_amt > 0 and transfer_tax.enabled and treasury:
                                            tax_recorded = add_balance(
                                                cur, treasury, currency, tax_amt
                                            )
                                            if tax_recorded:
                                                record_transaction(
                                                    cur,
                                                    payload.timestamp,
                                                    src_uuid,
                                                    treasury,
                                                    currency,
                                                    tax_amt,
                                                    "tax",
                                                )
                                                scoreboards[treasury] = get_scoreboard(
                                                    cur, treasury
                                                )
                                        if not tax_recorded:
                                            if net_amt > 0:
                                                add_balance(
                                                    cur,
                                                    dst_uuid,
                                                    currency,
                                                    -net_amt,
                                                )
                                            add_balance(
                                                cur, src_uuid, currency, required
                                            )
                                            success = False
                                            error_text = t("error.pay_failed", lang=exec_lang)
                                        else:
                                            messages.append(
                                                {
                                                    "target": "chat",
                                                    "text": t(
                                                        "money.pay",
                                                        lang=exec_lang,
                                                        src=src_name,
                                                        dst=dst_name,
                                                        amount=format_amount(
                                                            cur, amt, currency
                                                        ),
                                                    ),
                                                }
                                            )
                                            messages.append(
                                                {
                                                    "target": "chat",
                                                    "player": dst_uuid,
                                                    "text": t(
                                                        "receive",
                                                        lang=get_lang(dst_uuid),
                                                        src=src_name,
                                                        amount=format_amount(
                                                            cur, net_amt, currency
                                                        ),
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
                                            actions.append(
                                                {
                                                    "src": src_uuid,
                                                    "dst": dst_uuid,
                                                    "currency": currency,
                                                    "amount": amt,
                                                }
                                            )
                                            scoreboards[src_uuid] = get_scoreboard(
                                                cur, src_uuid
                                            )
                                            scoreboards[dst_uuid] = get_scoreboard(
                                                cur, dst_uuid
                                            )
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
                    transfer_tax = tax_info(cur, currency, "transfer")
                    tax_amt = min(compute_tax_amount(amt, transfer_tax), amt)
                    net_amt = amt - tax_amt
                    treasury = transfer_tax.treasury
                    required = amt
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
                        credited = True
                        if net_amt > 0:
                            credited = add_balance(cur, dst_uuid, currency, net_amt)
                        if not credited:
                            add_balance(cur, src_uuid, currency, required)
                            success = False
                            error_text = t("error.pay_failed", lang=exec_lang)
                        else:
                            tax_recorded = True
                            if tax_amt > 0 and transfer_tax.enabled and treasury:
                                tax_recorded = add_balance(
                                    cur, treasury, currency, tax_amt
                                )
                                if tax_recorded:
                                    record_transaction(
                                        cur,
                                        payload.timestamp,
                                        src_uuid,
                                        treasury,
                                        currency,
                                        tax_amt,
                                        "tax",
                                    )
                                    scoreboards[treasury] = get_scoreboard(
                                        cur, treasury
                                    )
                            if not tax_recorded:
                                if net_amt > 0:
                                    add_balance(
                                        cur,
                                        dst_uuid,
                                        currency,
                                        -net_amt,
                                    )
                                add_balance(cur, src_uuid, currency, required)
                                success = False
                                error_text = t("error.pay_failed", lang=exec_lang)
                            else:
                                messages.append(
                                    {
                                        "target": "chat",
                                        "text": t(
                                            "money.pay",
                                            lang=exec_lang,
                                            src=payload.executor.lower(),
                                            dst=dst_name,
                                            amount=format_amount(cur, amt, currency),
                                        ),
                                    }
                                )
                                messages.append(
                                    {
                                        "target": "chat",
                                        "player": dst_uuid,
                                        "text": t(
                                            "receive",
                                            lang=get_lang(dst_uuid),
                                            src=payload.executor.lower(),
                                            amount=format_amount(
                                                cur, net_amt, currency
                                            ),
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
                                actions.append(
                                    {
                                        "src": src_uuid,
                                        "dst": dst_uuid,
                                        "currency": currency,
                                        "amount": amt,
                                    }
                                )
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
                        transfer_tax = tax_info(cur, currency, "transfer")
                        tax_amt = compute_tax_amount(amt, transfer_tax)
                        treasury = transfer_tax.treasury
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
                            if tax_amt > 0 and transfer_tax.enabled and treasury:
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
                if not is_exec_admin:
                    success = False
                    error_text = t("error.no_permission", lang=exec_lang)
                else:
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
                if not is_exec_admin:
                    success = False
                    error_text = t("error.no_permission", lang=exec_lang)
                else:
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


@app.post("/api/account/ensure")
async def ensure_account(
    payload: AccountEnsurePayload, _auth: None = Depends(ensure_plugin_request)
):
    player_uuid = payload.player_uuid.strip()
    if not player_uuid:
        raise HTTPException(status_code=400, detail="invalid player_uuid")
    created: List[str] = []
    with transaction() as cur:
        rows = cur.execute("SELECT name FROM currencies").fetchall()
        currencies = [row["name"] for row in rows if row and row["name"]]
        if not currencies:
            currencies = [get_default_currency(cur)]
        seen: Set[str] = set()
        for currency in currencies:
            if not currency or currency in seen:
                continue
            seen.add(currency)
            cur.execute(
                "INSERT OR IGNORE INTO accounts(uuid, currency, balance) VALUES (?,?,0)",
                (player_uuid, currency),
            )
            if cur.rowcount > 0:
                created.append(currency)
    return {"status": "ok", "created": created}


@app.post("/api/shop/place")
async def shop_place(
    payload: ShopPlacePayload, _auth: None = Depends(ensure_plugin_request)
):
    start = time.time()
    result = "ok"
    scoreboards: Dict[str, Dict[str, int]] = {}
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
            updated = cur.execute(
                "UPDATE shop_locations SET world=?, x=?, y=?, z=? WHERE shop_id=?",
                (payload.world, payload.x, payload.y, payload.z, payload.shop_id),
            ).rowcount
            if updated == 0:
                cur.execute(
                    "INSERT INTO shop_locations(shop_id, world, x, y, z) VALUES(?,?,?,?,?)",
                    (
                        payload.shop_id,
                        payload.world,
                        payload.x,
                        payload.y,
                        payload.z,
                    ),
                )
            cur.execute(
                "UPDATE shops SET last_activity_at=? WHERE shop_id=?",
                (payload.timestamp, payload.shop_id),
            )
        else:
            if payload.owner_uuid != payload.placer_uuid:
                raise HTTPException(status_code=403, detail="owner mismatch")
            cur.execute(
                "INSERT INTO shops(shop_id, owner_uuid, account_uuid, status, created_at, last_activity_at) VALUES(?,?,?,?,?,?)",
                (
                    payload.shop_id,
                    payload.owner_uuid,
                    payload.owner_uuid,
                    "active",
                    payload.timestamp,
                    payload.timestamp,
                ),
            )
            cur.execute(
                "INSERT INTO shop_locations(shop_id, world, x, y, z) VALUES(?,?,?,?,?)",
                (payload.shop_id, payload.world, payload.x, payload.y, payload.z),
            )
            cur.execute(
                "INSERT INTO shop_owners(shop_id, owner_uuid) VALUES(?,?)",
                (payload.shop_id, payload.owner_uuid),
            )
            complete_quest(
                cur,
                payload.owner_uuid,
                "shop_create",
                payload.timestamp,
                scoreboards=scoreboards,
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
    res: Dict[str, Any] = {"status": result}
    if scoreboards:
        res["scoreboards"] = scoreboards
    return res


@app.get("/api/shop/ids")
async def shop_ids(owner_uuid: Optional[str] = None):
    purge_orphan_shops()
    start = time.time()
    with transaction() as cur:
        if owner_uuid:
            rows = cur.execute(
                "SELECT shop_id FROM shop_owners WHERE owner_uuid=?",
                (owner_uuid,),
            ).fetchall()
        else:
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
            "SELECT owner_uuid,status,last_activity_at,trade_mode,listed,account_uuid FROM shops WHERE shop_id=?",
            (shop_id,),
        ).fetchone()
        owners: List[str] = []
        if srow:
            owners = [
                r["owner_uuid"]
                for r in cur.execute(
                    "SELECT owner_uuid FROM shop_owners WHERE shop_id=?",
                    (shop_id,),
                ).fetchall()
            ]
            primary_owner = srow["owner_uuid"]
            if primary_owner:
                if primary_owner not in owners:
                    cur.execute(
                        "INSERT OR IGNORE INTO shop_owners(shop_id, owner_uuid) VALUES(?,?)",
                        (shop_id, primary_owner),
                    )
                    owners.insert(0, primary_owner)
                else:
                    owners = [primary_owner] + [o for o in owners if o != primary_owner]
        if not srow:
            purge_shop(cur, shop_id)
            result = {"status": "error", "reason": "shop_not_found"}
        elif not owners:
            purge_shop(cur, shop_id)
            result = {"status": "error", "reason": "shop_not_found"}
        elif srow["status"] != "active":
            result = {
                "status": srow["status"],
                "last_activity_at": srow["last_activity_at"],
                "owner_uuid": srow["owner_uuid"],
                "owners": owners,
                "listed": bool(srow["listed"]),
                "account_uuid": srow["account_uuid"],
            }
        else:
            rows = cur.execute(
                "SELECT st.item_key, st.sale_name, st.stock, it.material, it.display_name, it.nbt_blob FROM shop_stock st JOIN shop_items it ON st.item_key=it.item_key WHERE st.shop_id=?",
                (shop_id,),
            ).fetchall()
            items = []
            sale = cur.execute(
                "SELECT pct,end_ts FROM sale_events WHERE active=1 AND start_ts<=? AND end_ts>=?",
                (int(time.time()), int(time.time())),
            ).fetchone()
            for r in rows:
                autoprice_rows = cur.execute(
                    """
                    SELECT currency, lower_threshold, upper_threshold, high_price, low_price
                    FROM shop_autoprice
                    WHERE shop_id=? AND item_key=?
                    """,
                    (shop_id, r["item_key"]),
                ).fetchall()
                autoprice_map = {
                    ap["currency"]: {
                        "lower_threshold": ap["lower_threshold"],
                        "upper_threshold": ap["upper_threshold"],
                        "high_price": ap["high_price"],
                        "low_price": ap["low_price"],
                    }
                    for ap in autoprice_rows
                }
                price_rows = cur.execute(
                    "SELECT currency, price, buy_price FROM shop_prices WHERE shop_id=? AND item_key=?",
                    (shop_id, r["item_key"]),
                ).fetchall()
                prices = {}
                for pr in price_rows:
                    sell_price = pr["price"]
                    buy_price = pr["buy_price"]
                    if sale and sell_price is not None:
                        sell_price = int(sell_price * (100 - sale["pct"]) / 100)
                    price_entry = {}
                    if sell_price is not None:
                        price_entry["sell"] = sell_price
                    if buy_price is not None:
                        price_entry["buy"] = buy_price
                    auto_cfg = autoprice_map.get(pr["currency"])
                    if auto_cfg:
                        price_entry["autoprice"] = auto_cfg.copy()
                    prices[pr["currency"]] = price_entry
                for currency, cfg in autoprice_map.items():
                    price_entry = prices.setdefault(currency, {})
                    if "autoprice" not in price_entry:
                        price_entry["autoprice"] = cfg.copy()
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
                "trade_mode": srow["trade_mode"] if srow["trade_mode"] else "both",
                "listed": bool(srow["listed"]),
                "account_uuid": srow["account_uuid"],
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
    tax_amount = 0
    tax_account: Optional[str] = None
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
            tax_amount = (
                existing["tax_amount"]
                if "tax_amount" in existing.keys()
                else 0
            )
            tax_account = (
                existing["tax_account"]
                if "tax_account" in existing.keys()
                else None
            )
            grant_token = existing["grant_token"]
            shop = cur.execute(
                "SELECT owner_uuid, account_uuid FROM shops WHERE shop_id=?",
                (existing["shop_id"],),
            ).fetchone()
            owner = shop["owner_uuid"] if shop else None
            account = (
                shop["account_uuid"] if shop and shop["account_uuid"] else owner
            )
            if success and grant_token:
                stock_row = cur.execute(
                    "SELECT stock FROM shop_stock WHERE shop_id=? AND item_key=?",
                    (existing["shop_id"], existing["item_key"]),
                ).fetchone()
                remaining = stock_row["stock"] if stock_row else 0
                net_amount = max(total_price - tax_amount, 0)
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
                if account and account != owner:
                    scoreboards[account] = get_scoreboard(cur, account)
                buyer_name = get_name(existing["buyer_uuid"]) or existing["buyer_uuid"]
                buyer_text = (
                    f"Bought x{existing['qty']} for {existing['currency']} {total_price}"
                )
                if tax_amount > 0:
                    buyer_text += f" (tax {tax_amount})"
                buyer_text += f" (left {remaining})"
                owner_text = (
                    f"Sold x{existing['qty']} to {buyer_name} for {existing['currency']} {net_amount}"
                )
                if tax_amount > 0:
                    owner_text += f" (tax {tax_amount})"
                owner_text += f" (left {remaining})"
                buyer_msg = {
                    "target": "chat",
                    "player": existing["buyer_uuid"],
                    "text": buyer_text,
                }
                owner_msg = {
                    "target": "chat",
                    "player": owner,
                    "text": owner_text,
                }
                messages.append(buyer_msg)
                if owner:
                    messages.append(owner_msg)
                if tax_account:
                    scoreboards[tax_account] = get_scoreboard(cur, tax_account)
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
                "SELECT owner_uuid, account_uuid, status, trade_mode FROM shops WHERE shop_id=?",
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
            elif shop["trade_mode"] == "buy":
                reason = "shop_not_selling"
            else:
                owner = shop["owner_uuid"]
                account = shop["account_uuid"] or owner
                stock_row = cur.execute(
                    "SELECT stock FROM shop_stock WHERE shop_id=? AND item_key=?",
                    (payload.shop_id, payload.item_key),
                ).fetchone()
                if (
                    not stock_row
                    or stock_row["stock"] < payload.qty
                    or stock_row["stock"] - payload.qty < 1
                ):
                    reason = "insufficient_stock"
                else:
                    price_row = cur.execute(
                        "SELECT price FROM shop_prices WHERE shop_id=? AND item_key=? AND currency=?",
                        (payload.shop_id, payload.item_key, payload.currency),
                    ).fetchone()
                    if not price_row or price_row["price"] is None:
                        reason = "invalid_currency"
                    elif price_row["price"] <= 0:
                        reason = "invalid_currency"
                    else:
                        auto_cfg = get_autoprice_config(
                            cur,
                            payload.shop_id,
                            payload.item_key,
                            payload.currency,
                        )
                        if auto_cfg:
                            base_price = autoprice_total_buy(
                                stock_row["stock"],
                                payload.qty,
                                auto_cfg["lower_threshold"],
                                auto_cfg["upper_threshold"],
                                auto_cfg["high_price"],
                                auto_cfg["low_price"],
                            )
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
                        trade_tax = tax_info(cur, payload.currency, "trade")
                        tax_amount = min(
                            compute_tax_amount(total_price, trade_tax), total_price
                        )
                        net_amount = total_price - tax_amount
                        required = total_price
                        tax_account = trade_tax.treasury if trade_tax.enabled else None
                        if is_frozen(
                            cur, payload.player_uuid, payload.currency
                        ) or is_frozen(cur, account, payload.currency):
                            reason = "account_frozen"
                        elif (
                            get_balance(cur, payload.player_uuid, payload.currency)
                            < required
                        ):
                            reason = "insufficient_funds"
                        elif not add_balance(
                            cur, payload.player_uuid, payload.currency, -required
                        ):
                            reason = "transfer_failed"
                        else:
                            credited_shop = True
                            if net_amount > 0:
                                credited_shop = add_balance(
                                    cur, account, payload.currency, net_amount
                                )
                            if not credited_shop:
                                add_balance(
                                    cur, payload.player_uuid, payload.currency, required
                                )
                                reason = "transfer_failed"
                            else:
                                tax_recorded = True
                                if tax_amount > 0 and trade_tax.enabled and tax_account:
                                    tax_recorded = add_balance(
                                        cur, tax_account, payload.currency, tax_amount
                                    )
                                    if tax_recorded:
                                        record_transaction(
                                            cur,
                                            payload.timestamp,
                                            payload.player_uuid,
                                            tax_account,
                                            payload.currency,
                                            tax_amount,
                                            "tax",
                                        )
                                if not tax_recorded:
                                    if net_amount > 0:
                                        add_balance(
                                            cur,
                                            account,
                                            payload.currency,
                                            -net_amount,
                                        )
                                    add_balance(
                                        cur, payload.player_uuid, payload.currency, required
                                    )
                                    reason = "transfer_failed"
                                    tax_account = None
                                if reason is None:
                                    grant_token = secrets.token_hex(8)
                                    cur.execute(
                                        "UPDATE shop_stock SET stock=stock-? WHERE shop_id=? AND item_key=?",
                                        (payload.qty, payload.shop_id, payload.item_key),
                                    )
                                    cur.execute(
                                        "UPDATE shops SET last_activity_at=? WHERE shop_id=?",
                                        (payload.timestamp, payload.shop_id),
                                    )
                                    rows = cur.execute(
                                        "SELECT currency FROM shop_autoprice WHERE shop_id=? AND item_key=?",
                                        (payload.shop_id, payload.item_key),
                                    ).fetchall()
                                    for cfg in rows:
                                        apply_autoprice(
                                            cur, payload.shop_id, payload.item_key, cfg["currency"]
                                        )
                                    cur.execute(
                                        "INSERT INTO shop_tx(client_tx_id,shop_id,buyer_uuid,item_key,qty,currency,total_price,tax_amount,tax_account,timestamp,result,grant_token,world,x,y,z,tx_type) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                                        (
                                            payload.client_tx_id,
                                            payload.shop_id,
                                            payload.player_uuid,
                                            payload.item_key,
                                            payload.qty,
                                            payload.currency,
                                            total_price,
                                            tax_amount,
                                            tax_account,
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
                                    buyer_text = (
                                        f"Bought x{payload.qty} for {payload.currency} {total_price}"
                                    )
                                    if tax_amount > 0:
                                        buyer_text += f" (tax {tax_amount})"
                                    buyer_text += f" (left {remaining})"
                                    owner_text = (
                                        f"Sold x{payload.qty} to {buyer_name} for {payload.currency} {net_amount}"
                                    )
                                    if tax_amount > 0:
                                        owner_text += f" (tax {tax_amount})"
                                    owner_text += f" (left {remaining})"
                                    buyer_msg = {
                                        "target": "chat",
                                        "player": payload.player_uuid,
                                        "text": buyer_text,
                                    }
                                    owner_msg = {
                                        "target": "chat",
                                        "player": owner,
                                        "text": owner_text,
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
                                    if account and account != owner:
                                        scoreboards[account] = get_scoreboard(cur, account)
                                    if tax_account:
                                        scoreboards[tax_account] = get_scoreboard(
                                            cur, tax_account
                                        )
                                    grant.append(
                                        {
                                            "item_key": payload.item_key,
                                            "qty": payload.qty,
                                            "grant_token": grant_token,
                                        }
                                    )
                                    complete_quest(
                                        cur,
                                        payload.player_uuid,
                                        "shop_buy",
                                        payload.timestamp,
                                        messages=messages,
                                        scoreboards=scoreboards,
                                    )
            if not success:
                cur.execute(
                    "INSERT INTO shop_tx(client_tx_id,shop_id,buyer_uuid,item_key,qty,currency,total_price,tax_amount,tax_account,timestamp,result,reason,world,x,y,z,tx_type) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    (
                        payload.client_tx_id,
                        payload.shop_id,
                        payload.player_uuid,
                        payload.item_key,
                        payload.qty,
                        payload.currency,
                        total_price,
                        tax_amount,
                        tax_account,
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
        "tax_amount": tax_amount,
        "tax_account": tax_account,
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
        "reason": reason,
    }


@app.post("/api/shop/sell")
async def shop_sell(payload: ShopSellPayload):
    start = time.time()
    messages: List[Dict[str, str]] = []
    scoreboards: Dict[str, Dict[str, int]] = {}
    success = False
    reason: Optional[str] = None
    total_price = 0
    tax_amount = 0
    tax_account: Optional[str] = None
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
            tax_amount = (
                existing["tax_amount"]
                if "tax_amount" in existing.keys()
                else 0
            )
            tax_account = (
                existing["tax_account"]
                if "tax_account" in existing.keys()
                else None
            )
            shop = cur.execute(
                "SELECT owner_uuid, account_uuid FROM shops WHERE shop_id=?",
                (existing["shop_id"],),
            ).fetchone()
            owner = shop["owner_uuid"] if shop else None
            account = (
                shop["account_uuid"] if shop and shop["account_uuid"] else owner
            )
            if success:
                if owner:
                    scoreboards[payload.player_uuid] = get_scoreboard(cur, payload.player_uuid)
                    scoreboards[owner] = get_scoreboard(cur, owner)
                if account and account != owner:
                    scoreboards[account] = get_scoreboard(cur, account)
                if tax_account:
                    scoreboards[tax_account] = get_scoreboard(cur, tax_account)
                player_name = get_name(payload.player_uuid) or payload.player_uuid
                net_amount = max(total_price - tax_amount, 0)
                player_text = (
                    f"Sold x{existing['qty']} for {existing['currency']} {net_amount}"
                )
                if tax_amount > 0:
                    player_text += f" (- tax {tax_amount})"
                owner_text = (
                    f"Bought x{existing['qty']} from {player_name} for {existing['currency']} {total_price}"
                )
                if tax_amount > 0:
                    owner_text += f" (tax {tax_amount})"
                buyer_msg = {
                    "target": "chat",
                    "player": payload.player_uuid,
                    "text": player_text,
                }
                owner_msg = {
                    "target": "chat",
                    "player": owner,
                    "text": owner_text,
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
                "SELECT owner_uuid, account_uuid, status, trade_mode FROM shops WHERE shop_id=?",
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
            elif shop["trade_mode"] == "sell":
                reason = "shop_not_buying"
            else:
                owner = shop["owner_uuid"]
                account = shop["account_uuid"] or owner
                stock_row = cur.execute(
                    "SELECT stock FROM shop_stock WHERE shop_id=? AND item_key=?",
                    (payload.shop_id, payload.item_key),
                ).fetchone()
                current_stock = stock_row["stock"] if stock_row else 0
                price_row = cur.execute(
                    "SELECT price, buy_price FROM shop_prices WHERE shop_id=? AND item_key=? AND currency=?",
                    (payload.shop_id, payload.item_key, payload.currency),
                ).fetchone()
                if not price_row:
                    reason = "invalid_currency"
                else:
                    buy_price = (
                        price_row["buy_price"]
                        if price_row["buy_price"] is not None
                        else price_row["price"]
                    )
                    if buy_price is None or buy_price <= 0:
                        reason = "invalid_currency"
                    else:
                        auto_cfg = get_autoprice_config(
                            cur,
                            payload.shop_id,
                            payload.item_key,
                            payload.currency,
                        )
                        if (
                            auto_cfg
                            and price_row["price"] is not None
                            and price_row["price"] == buy_price
                        ):
                            total_price = autoprice_total_sell(
                                current_stock,
                                payload.qty,
                                auto_cfg["lower_threshold"],
                                auto_cfg["upper_threshold"],
                                auto_cfg["high_price"],
                                auto_cfg["low_price"],
                            )
                        else:
                            total_price = buy_price * payload.qty
                        trade_tax = tax_info(cur, payload.currency, "trade")
                        tax_amount = compute_tax_amount(total_price, trade_tax)
                        if tax_amount > total_price:
                            tax_amount = total_price
                        net_amount = total_price - tax_amount
                        tax_account = trade_tax.treasury if trade_tax.enabled else None
                        if is_frozen(cur, account, payload.currency) or is_frozen(
                            cur, payload.player_uuid, payload.currency
                        ):
                            reason = "account_frozen"
                        elif get_balance(cur, account, payload.currency) < total_price:
                            reason = "insufficient_funds"
                        elif not add_balance(
                            cur, account, payload.currency, -total_price
                        ):
                            reason = "transfer_failed"
                        else:
                            credited_player = True
                            if net_amount > 0:
                                credited_player = add_balance(
                                    cur, payload.player_uuid, payload.currency, net_amount
                                )
                            if not credited_player:
                                add_balance(cur, account, payload.currency, total_price)
                                reason = "transfer_failed"
                            else:
                                if tax_amount > 0 and trade_tax.enabled:
                                    if tax_account:
                                        if not add_balance(
                                            cur, tax_account, payload.currency, tax_amount
                                        ):
                                            if net_amount > 0:
                                                add_balance(
                                                    cur,
                                                    payload.player_uuid,
                                                    payload.currency,
                                                    -net_amount,
                                                )
                                            add_balance(
                                                cur, account, payload.currency, total_price
                                            )
                                            reason = "transfer_failed"
                                            tax_account = None
                                        else:
                                            record_transaction(
                                                cur,
                                                payload.timestamp,
                                                account,
                                                tax_account,
                                                payload.currency,
                                                tax_amount,
                                                "tax",
                                            )
                                    else:
                                        tax_account = None
                                if reason is None:
                                    cur.execute(
                                        "UPDATE shop_stock SET stock=stock+? WHERE shop_id=? AND item_key=?",
                                        (payload.qty, payload.shop_id, payload.item_key),
                                    )
                                    cur.execute(
                                        "UPDATE shops SET last_activity_at=? WHERE shop_id=?",
                                        (payload.timestamp, payload.shop_id),
                                    )
                                    rows = cur.execute(
                                        "SELECT currency FROM shop_autoprice WHERE shop_id=? AND item_key=?",
                                        (payload.shop_id, payload.item_key),
                                    ).fetchall()
                                    for cfg in rows:
                                        apply_autoprice(
                                            cur, payload.shop_id, payload.item_key, cfg["currency"]
                                        )
                                    cur.execute(
                                        "INSERT INTO shop_tx(client_tx_id,shop_id,buyer_uuid,item_key,qty,currency,total_price,tax_amount,tax_account,timestamp,result,world,x,y,z,tx_type) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                                        (
                                            payload.client_tx_id,
                                            payload.shop_id,
                                            payload.player_uuid,
                                            payload.item_key,
                                            payload.qty,
                                            payload.currency,
                                            total_price,
                                            tax_amount,
                                            tax_account,
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
                                    increment_quest_progress(
                                        cur,
                                        payload.player_uuid,
                                        "shop_sell",
                                        payload.qty,
                                    )
                                    player_name = get_name(payload.player_uuid) or payload.player_uuid
                                    net_display = net_amount
                                    player_text = (
                                        f"Sold x{payload.qty} for {payload.currency} {net_display}"
                                    )
                                    if tax_amount > 0:
                                        player_text += f" (- tax {tax_amount})"
                                    owner_text = (
                                        f"Bought x{payload.qty} from {player_name} for {payload.currency} {total_price}"
                                    )
                                    if tax_amount > 0:
                                        owner_text += f" (tax {tax_amount})"
                                    buyer_msg = {
                                        "target": "chat",
                                        "player": payload.player_uuid,
                                        "text": player_text,
                                    }
                                    owner_msg = {
                                        "target": "chat",
                                        "player": owner,
                                        "text": owner_text,
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
                                    if account and account != owner:
                                        scoreboards[account] = get_scoreboard(cur, account)
                                    if tax_account:
                                        scoreboards[tax_account] = get_scoreboard(
                                            cur, tax_account
                                        )
                                    complete_quest(
                                        cur,
                                        payload.player_uuid,
                                        "shop_sell",
                                        payload.timestamp,
                                        messages=messages,
                                        scoreboards=scoreboards,
                                    )
        if not success and not existing:
            cur.execute(
                "INSERT INTO shop_tx(client_tx_id,shop_id,buyer_uuid,item_key,qty,currency,total_price,tax_amount,tax_account,timestamp,result,reason,world,x,y,z,tx_type) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (
                    payload.client_tx_id,
                    payload.shop_id,
                    payload.player_uuid,
                    payload.item_key,
                    payload.qty,
                    payload.currency,
                    total_price,
                    tax_amount,
                    tax_account,
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
        "tax_amount": tax_amount,
        "tax_account": tax_account,
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
        "reason": reason,
    }


@app.post("/api/shop/add_stock")
async def shop_add_stock(
    payload: ShopAddStockPayload, _auth: None = Depends(ensure_plugin_request)
):
    start = time.time()
    blob = base64.b64decode(payload.nbt_blob)
    item_key = hashlib.sha256(blob).hexdigest()
    result = "success"
    reason: Optional[str] = None
    sale_name = payload.sale_name or payload.display_name or payload.material
    with transaction() as cur:
        shop = cur.execute(
            "SELECT status, trade_mode FROM shops WHERE shop_id=?",
            (payload.shop_id,),
        ).fetchone()
        if not shop or not is_shop_owner(cur, payload.shop_id, payload.owner_uuid) or shop["status"] != "active":
            result = "error"
            reason = "not_owner"
        else:
            ts = int(time.time())
            sell_price = (
                payload.sell_price
                if payload.sell_price is not None
                else payload.price
            )
            buy_price = (
                payload.buy_price
                if payload.buy_price is not None
                else payload.price
            )
            if sell_price is None or sell_price < 0 or buy_price is None or buy_price < 0:
                result = "error"
                reason = "invalid_price"
            elif shop["trade_mode"] == "both" and buy_price > sell_price:
                result = "error"
                reason = "buy_exceeds_sell"
            else:
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
                    INSERT INTO shop_prices(shop_id, item_key, currency, price, buy_price)
                    VALUES(?,?,?,?,?)
                    ON CONFLICT(shop_id,item_key,currency) DO UPDATE SET
                        price=excluded.price,
                        buy_price=excluded.buy_price
                    """,
                    (payload.shop_id, item_key, currency, sell_price, buy_price),
                )
                applied = apply_autoprice(cur, payload.shop_id, item_key, currency)
                if applied:
                    sell_price = applied[0]
                    buy_price = applied[1] if applied[1] is not None else buy_price
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
        "price": sell_price if 'sell_price' in locals() else payload.price,
        "result": result,
        "reason": reason,
        "latency_ms": latency_ms,
    }
    append_log(log_entry)
    if result == "success":
        return {"status": "success", "item_key": item_key}
    return {"status": "error", "reason": reason}


@app.post("/api/shop/take_stock")
async def shop_take_stock(
    payload: ShopTakeStockPayload, _auth: None = Depends(ensure_plugin_request)
):
    start = time.time()
    grant: List[Dict[str, str]] = []
    result = "success"
    reason: Optional[str] = None
    chosen_key: Optional[str] = None
    requested_first: Optional[str] = None
    with transaction() as cur:
        shop = cur.execute(
            "SELECT status FROM shops WHERE shop_id=?",
            (payload.shop_id,),
        ).fetchone()
        if not shop or not is_shop_owner(cur, payload.shop_id, payload.owner_uuid) or shop["status"] != "active":
            result = "error"
            reason = "not_owner"
        else:
            requested: List[str] = []
            if payload.item_keys:
                for key in payload.item_keys:
                    if key:
                        requested.append(key)
            if payload.item_key:
                requested.append(payload.item_key)
            seen: Set[str] = set()
            ordered: List[str] = []
            for key in requested:
                if key not in seen:
                    seen.add(key)
                    ordered.append(key)
            if not ordered:
                result = "error"
                reason = "missing_item_key"
            else:
                requested_first = ordered[0]
                for candidate in ordered:
                    row = cur.execute(
                        "SELECT stock FROM shop_stock WHERE shop_id=? AND item_key=?",
                        (payload.shop_id, candidate),
                    ).fetchone()
                    if not row or row["stock"] < payload.qty:
                        continue
                    ts = int(time.time())
                    cur.execute(
                        "UPDATE shop_stock SET stock=stock-?, updated_at=? WHERE shop_id=? AND item_key=?",
                        (payload.qty, ts, payload.shop_id, candidate),
                    )
                    cur.execute(
                        "UPDATE shops SET last_activity_at=? WHERE shop_id=?",
                        (ts, payload.shop_id),
                    )
                    rows = cur.execute(
                        "SELECT currency FROM shop_autoprice WHERE shop_id=? AND item_key=?",
                        (payload.shop_id, candidate),
                    ).fetchall()
                    for cfg in rows:
                        apply_autoprice(cur, payload.shop_id, candidate, cfg["currency"])
                    item = cur.execute(
                        "SELECT nbt_blob FROM shop_items WHERE item_key=?", (candidate,)
                    ).fetchone()
                    remaining_row = cur.execute(
                        "SELECT stock FROM shop_stock WHERE shop_id=? AND item_key=?",
                        (payload.shop_id, candidate),
                    ).fetchone()
                    if remaining_row and remaining_row["stock"] <= 0:
                        cur.execute(
                            "DELETE FROM shop_stock WHERE shop_id=? AND item_key=?",
                            (payload.shop_id, candidate),
                        )
                        cur.execute(
                            "DELETE FROM shop_prices WHERE shop_id=? AND item_key=?",
                            (payload.shop_id, candidate),
                        )
                        cur.execute(
                            "DELETE FROM shop_autoprice WHERE shop_id=? AND item_key=?",
                            (payload.shop_id, candidate),
                        )
                    if item:
                        grant.append(
                            {
                                "item_key": candidate,
                                "qty": payload.qty,
                                "nbt_blob": base64.b64encode(item["nbt_blob"]).decode("ascii"),
                                "grant_token": secrets.token_hex(8),
                            }
                        )
                    chosen_key = candidate
                    break
                if chosen_key is None:
                    result = "error"
                    reason = "insufficient_stock"
    latency_ms = int((time.time() - start) * 1000)
    log_entry = {
        "type": "shop_take_stock",
        "timestamp": int(time.time()),
        "shop_id": payload.shop_id,
        "owner": payload.owner_uuid,
        "item_key": chosen_key or requested_first,
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
async def shop_set_price(
    payload: ShopSetPricePayload, _auth: None = Depends(ensure_plugin_request)
):
    start = time.time()
    result = "success"
    reason: Optional[str] = None
    with transaction() as cur:
        shop = cur.execute(
            "SELECT status, trade_mode FROM shops WHERE shop_id=?",
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
                    kind = (payload.price_kind or "sell").lower()
                    if kind not in {"sell", "buy"}:
                        result = "error"
                        reason = "invalid_price_kind"
                    elif payload.price < 0:
                        result = "error"
                        reason = "invalid_price"
                    else:
                        existing = cur.execute(
                            "SELECT price, buy_price FROM shop_prices WHERE shop_id=? AND item_key=? AND currency=?",
                            (payload.shop_id, item_key, payload.currency),
                        ).fetchone()
                        current_sell = existing["price"] if existing else None
                        current_buy = existing["buy_price"] if existing else None
                        ts = int(time.time())
                        if kind == "buy":
                            if shop["trade_mode"] == "sell":
                                result = "error"
                                reason = "shop_not_buying"
                            elif shop["trade_mode"] == "both" and current_sell is not None and payload.price > current_sell:
                                result = "error"
                                reason = "buy_exceeds_sell"
                            else:
                                if existing:
                                    cur.execute(
                                        "UPDATE shop_prices SET buy_price=? WHERE shop_id=? AND item_key=? AND currency=?",
                                        (payload.price, payload.shop_id, item_key, payload.currency),
                                    )
                                else:
                                    base_sell = current_sell if current_sell is not None else payload.price
                                    cur.execute(
                                        "INSERT INTO shop_prices(shop_id, item_key, currency, price, buy_price) VALUES(?,?,?,?,?)",
                                        (
                                            payload.shop_id,
                                            item_key,
                                            payload.currency,
                                            base_sell,
                                            payload.price,
                                        ),
                                    )
                                cur.execute(
                                    "UPDATE shops SET last_activity_at=? WHERE shop_id=?",
                                    (ts, payload.shop_id),
                                )
                        else:
                            if shop["trade_mode"] == "buy":
                                result = "error"
                                reason = "shop_not_selling"
                            elif shop["trade_mode"] == "both" and current_buy is not None and current_buy > payload.price:
                                result = "error"
                                reason = "buy_exceeds_sell"
                            else:
                                if existing:
                                    cur.execute(
                                        "UPDATE shop_prices SET price=? WHERE shop_id=? AND item_key=? AND currency=?",
                                        (payload.price, payload.shop_id, item_key, payload.currency),
                                    )
                                else:
                                    initial_buy = current_buy if current_buy is not None else payload.price
                                    cur.execute(
                                        "INSERT INTO shop_prices(shop_id, item_key, currency, price, buy_price) VALUES(?,?,?,?,?)",
                                        (
                                            payload.shop_id,
                                            item_key,
                                            payload.currency,
                                            payload.price,
                                            initial_buy,
                                        ),
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
        "price_kind": payload.price_kind,
        "result": result,
        "reason": reason,
        "latency_ms": latency_ms,
    }
    append_log(log_entry)
    if result == "success":
        return {"status": "success"}
    return {"status": "error", "reason": reason}


@app.post("/api/shop/autoprice")
async def shop_set_autoprice(
    payload: ShopAutoPricePayload, _auth: None = Depends(ensure_plugin_request)
):
    start = time.time()
    result = "success"
    reason: Optional[str] = None
    response_price: Optional[int] = None
    response_buy: Optional[int] = None
    response_currency: Optional[str] = None
    with transaction() as cur:
        shop = cur.execute(
            "SELECT status FROM shops WHERE shop_id=?",
            (payload.shop_id,),
        ).fetchone()
        if (
            not shop
            or shop["status"] != "active"
            or not is_shop_owner(cur, payload.shop_id, payload.owner_uuid)
        ):
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
                currency = (
                    resolve_currency(cur, payload.currency)
                    if payload.currency
                    else get_default_currency(cur)
                )
                if not is_currency(cur, currency):
                    result = "error"
                    reason = "invalid_currency"
                elif payload.lower_threshold < 0 or payload.upper_threshold < 0:
                    result = "error"
                    reason = "invalid_threshold"
                elif payload.upper_threshold <= payload.lower_threshold:
                    result = "error"
                    reason = "invalid_threshold"
                elif payload.high_price < 0 or payload.low_price < 0:
                    result = "error"
                    reason = "invalid_price"
                else:
                    cur.execute(
                        """
                        INSERT INTO shop_autoprice(
                            shop_id, item_key, currency,
                            lower_threshold, upper_threshold,
                            high_price, low_price
                        ) VALUES(?,?,?,?,?,?,?)
                        ON CONFLICT(shop_id, item_key, currency) DO UPDATE SET
                            lower_threshold=excluded.lower_threshold,
                            upper_threshold=excluded.upper_threshold,
                            high_price=excluded.high_price,
                            low_price=excluded.low_price
                        """,
                        (
                            payload.shop_id,
                            item_key,
                            currency,
                            payload.lower_threshold,
                            payload.upper_threshold,
                            payload.high_price,
                            payload.low_price,
                        ),
                    )
                    ts = int(time.time())
                    cur.execute(
                        "UPDATE shops SET last_activity_at=? WHERE shop_id=?",
                        (ts, payload.shop_id),
                    )
                    applied = apply_autoprice(cur, payload.shop_id, item_key, currency)
                    if applied:
                        response_price, response_buy = applied
                        response_currency = currency
    latency_ms = int((time.time() - start) * 1000)
    log_entry = {
        "type": "shop_set_autoprice",
        "timestamp": int(time.time()),
        "shop_id": payload.shop_id,
        "owner": payload.owner_uuid,
        "sale_name": payload.sale_name,
        "item_key": payload.item_key,
        "currency": payload.currency,
        "lower_threshold": payload.lower_threshold,
        "upper_threshold": payload.upper_threshold,
        "high_price": payload.high_price,
        "low_price": payload.low_price,
        "result": result,
        "reason": reason,
        "latency_ms": latency_ms,
    }
    append_log(log_entry)
    if result == "success":
        body: Dict[str, Any] = {"status": "success"}
        if response_price is not None:
            body["price"] = response_price
        if response_buy is not None:
            body["buy_price"] = response_buy
        if response_currency is not None:
            body["currency"] = response_currency
        return body
    return {"status": "error", "reason": reason}


@app.post("/api/shop/autoprice_disable")
async def shop_autoprice_disable(
    payload: ShopAutoPriceDisablePayload,
    _auth: None = Depends(ensure_plugin_request),
):
    start = time.time()
    result = "success"
    reason: Optional[str] = None
    removed = 0
    resolved_currency: Optional[str] = None
    with transaction() as cur:
        shop = cur.execute(
            "SELECT status FROM shops WHERE shop_id=?",
            (payload.shop_id,),
        ).fetchone()
        if (
            not shop
            or shop["status"] != "active"
            or not is_shop_owner(cur, payload.shop_id, payload.owner_uuid)
        ):
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
            if payload.currency:
                resolved_currency = resolve_currency(cur, payload.currency)
                if not is_currency(cur, resolved_currency):
                    result = "error"
                    reason = "invalid_currency"
            if result == "success":
                if resolved_currency and item_key:
                    cur.execute(
                        "DELETE FROM shop_autoprice WHERE shop_id=? AND item_key=? AND currency=?",
                        (payload.shop_id, item_key, resolved_currency),
                    )
                elif resolved_currency:
                    cur.execute(
                        "DELETE FROM shop_autoprice WHERE shop_id=? AND currency=?",
                        (payload.shop_id, resolved_currency),
                    )
                elif item_key:
                    cur.execute(
                        "DELETE FROM shop_autoprice WHERE shop_id=? AND item_key=?",
                        (payload.shop_id, item_key),
                    )
                else:
                    cur.execute(
                        "DELETE FROM shop_autoprice WHERE shop_id=?",
                        (payload.shop_id,),
                    )
                removed = cur.rowcount if cur.rowcount != -1 else 0
                if removed > 0:
                    ts = int(time.time())
                    cur.execute(
                        "UPDATE shops SET last_activity_at=? WHERE shop_id=?",
                        (ts, payload.shop_id),
                    )
                else:
                    result = "error"
                    reason = "autoprice_not_found"
    latency_ms = int((time.time() - start) * 1000)
    log_entry = {
        "type": "shop_autoprice_disable",
        "timestamp": int(time.time()),
        "shop_id": payload.shop_id,
        "owner": payload.owner_uuid,
        "sale_name": payload.sale_name,
        "item_key": payload.item_key,
        "currency": resolved_currency if resolved_currency else payload.currency,
        "result": result,
        "reason": reason,
        "removed": removed,
        "latency_ms": latency_ms,
    }
    append_log(log_entry)
    if result == "success":
        return {"status": "success", "removed": removed}
    return {"status": "error", "reason": reason}


@app.post("/api/shop/remove_item")
async def shop_remove_item(
    payload: ShopRemoveItemPayload, _auth: None = Depends(ensure_plugin_request)
):
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
                cur.execute(
                    "DELETE FROM shop_autoprice WHERE shop_id=? AND item_key=?",
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


@app.post("/api/shop/account")
async def shop_account(
    payload: ShopAccountPayload, _auth: None = Depends(ensure_plugin_request)
):
    start = time.time()
    result = "success"
    reason: Optional[str] = None
    account_uuid = payload.owner_uuid
    account_id = payload.account_id.strip().lower()
    with transaction() as cur:
        if not is_shop_owner(cur, payload.shop_id, payload.owner_uuid):
            result = "error"
            reason = "not_owner"
        else:
            if account_id and account_id not in {"self", "owner", "personal"}:
                looked = get_uuid(account_id)
                if looked is None:
                    looked = account_id
                account_uuid = looked
            if account_uuid != payload.owner_uuid:
                row = cur.execute(
                    "SELECT 1 FROM system_accounts WHERE uuid=?",
                    (account_uuid,),
                ).fetchone()
                if row is None:
                    result = "error"
                    reason = "invalid_account"
                else:
                    exec_name = get_name(payload.owner_uuid)
                    is_admin = False
                    if exec_name:
                        is_admin = has_admin_access(exec_name, cur)
                    if not is_admin and not has_link(
                        cur, payload.owner_uuid, account_uuid
                    ):
                        result = "error"
                        reason = "no_access"
            if result == "success":
                cur.execute(
                    "UPDATE shops SET account_uuid=? WHERE shop_id=?",
                    (account_uuid, payload.shop_id),
                )
    latency_ms = int((time.time() - start) * 1000)
    append_log(
        {
            "type": "shop_account",
            "timestamp": int(time.time()),
            "shop_id": payload.shop_id,
            "actor": payload.owner_uuid,
            "account": account_uuid,
            "result": result,
            "reason": reason,
            "latency_ms": latency_ms,
        }
    )
    return {"status": result, "reason": reason}


@app.post("/api/shop/add_owner")
async def shop_add_owner(
    payload: ShopAddOwnerPayload, _auth: None = Depends(ensure_plugin_request)
):
    start = time.time()
    result = "success"
    reason: Optional[str] = None
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
            "reason": reason,
            "latency_ms": latency_ms,
        }
    )
    return {"status": result, "reason": reason}


@app.post("/api/shop/remove_owner")
async def shop_remove_owner(
    payload: ShopRemoveOwnerPayload, _auth: None = Depends(ensure_plugin_request)
):
    start = time.time()
    result = "success"
    reason: Optional[str] = None
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
            "reason": reason,
            "latency_ms": latency_ms,
        }
    )
    return {"status": result, "reason": reason}


@app.post("/api/shop/listing")
async def shop_listing(
    payload: ShopListingPayload, _auth: None = Depends(ensure_plugin_request)
):
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


@app.post("/api/shop/mode")
async def shop_mode(
    payload: ShopModePayload, _auth: None = Depends(ensure_plugin_request)
):
    start = time.time()
    result = "success"
    reason: Optional[str] = None
    mode = payload.mode.lower()
    with transaction() as cur:
        if mode not in {"buy", "sell", "both"}:
            result = "error"
            reason = "invalid_mode"
        elif not is_shop_owner(cur, payload.shop_id, payload.owner_uuid):
            result = "error"
            reason = "not_owner"
        else:
            ts = int(time.time())
            cur.execute(
                "UPDATE shops SET trade_mode=?, last_activity_at=? WHERE shop_id=?",
                (mode, ts, payload.shop_id),
            )
    latency_ms = int((time.time() - start) * 1000)
    log_entry = {
        "type": "shop_mode",
        "timestamp": int(time.time()),
        "shop_id": payload.shop_id,
        "owner": payload.owner_uuid,
        "mode": mode,
        "result": result,
        "reason": reason,
        "latency_ms": latency_ms,
    }
    append_log(log_entry)
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
async def shop_reopen(
    payload: ShopReopenPayload, _auth: None = Depends(ensure_plugin_request)
):
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
async def shop_remove(
    payload: ShopRemovePayload, _auth: None = Depends(ensure_plugin_request)
):
    start = time.time()
    grants: List[Dict[str, str]] = []
    location: Optional[Dict[str, Union[str, float]]] = None
    result = "success"
    reason: Optional[str] = None
    with transaction() as cur:
        authorized = False
        if payload.owner_uuid:
            authorized = is_shop_owner(cur, payload.shop_id, payload.owner_uuid)
        else:
            authorized = (
                cur.execute(
                    "SELECT 1 FROM shop_owners WHERE shop_id=?", (payload.shop_id,)
                ).fetchone()
                is None
            )
        if not authorized:
            result = "error"
            reason = "not_owner"
        else:
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
            cur.execute(
                "UPDATE shops SET status='suspended' WHERE shop_id=?",
                (payload.shop_id,),
            )
            cur.execute("DELETE FROM shop_owners WHERE shop_id=?", (payload.shop_id,))
    latency_ms = int((time.time() - start) * 1000)
    log_entry = {
        "type": "shop_remove",
        "timestamp": int(time.time()),
        "shop_id": payload.shop_id,
        "refund": payload.refund,
        "owner": payload.owner_uuid,
        "result": result,
        "reason": reason,
        "latency_ms": latency_ms,
    }
    append_log(log_entry)
    return {"status": result, "reason": reason, "grant": grants, "location": location}


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
            "INSERT INTO cash_events(player_uuid, action, currency, amount, quantity, location, ts) VALUES(?,?,?,?,?,?,?)",
            (
                ev.player_uuid,
                ev.action,
                ev.currency,
                ev.amount,
                ev.quantity,
                ev.location,
                ts,
            ),
        )
        if ev.player_uuid:
            delta = 0
            if ev.action in ("issue", "pickup", "retrieve"):
                delta = ev.quantity
            elif ev.action in ("drop", "store"):
                delta = -ev.quantity
            if delta:
                cash_conn.execute(
                    "INSERT INTO notes(owner_uuid, currency, amount, quantity) VALUES(?,?,?,?) "
                    "ON CONFLICT(owner_uuid, currency, amount) DO UPDATE SET quantity = quantity + ?",
                    (ev.player_uuid, ev.currency, ev.amount, delta, delta),
                )
    return {"status": "ok"}


class CashHolding(BaseModel):
    currency: str
    amount: int


class CashRewrite(BaseModel):
    player_uuid: str
    notes: List[CashHolding]


@app.post("/api/cash/rewrite")
def cash_rewrite(payload: CashRewrite, token: None = Depends(verify_token)):
    with cash_conn:
        cash_conn.execute("DELETE FROM notes WHERE owner_uuid=?", (payload.player_uuid,))
        for n in payload.notes:
            cash_conn.execute(
                "INSERT INTO notes(owner_uuid, currency, amount) VALUES(?,?,?)",
                (payload.player_uuid, n.currency, n.amount),
            )
    return {"status": "ok"}


@app.get("/api/cash/holdings/{player_uuid}")
def cash_holdings(player_uuid: str, token: None = Depends(verify_token)):
    cur = cash_conn.execute(
        "SELECT currency, amount FROM notes WHERE owner_uuid=?",
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
    tile_store.process_dirty(0)
    tile_store.process_queue()
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
async def mapcolor_palette(token: None = Depends(verify_token_optional)):
    """Return the static map colour palette."""

    return {
        "palette": PALETTE,
        "world_info": [],
        "server_version": "python",
    }


@app.post("/api/mapcolor/resolve")
@app.post("/mapcolor/resolve")
@app.post("/plugin/mapcolor/resolve")
async def mapcolor_resolve(req: Request, token: None = Depends(verify_token_optional)):
    body = await req.json()
    blocks = body.get("blocks", [])
    indices = [resolve_block(name) for name in blocks]
    return {"indices": indices}


@app.get("/tiles/worlds")
@app.get("/api/tiles/worlds")
@app.get("/plugin/tiles/worlds")
def list_worlds(token: None = Depends(verify_token_optional)):
    worlds = set()
    try:
        names = os.listdir(tile_store.base_dir)
    except FileNotFoundError:
        names = []
    for name in names:
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
    token: None = Depends(verify_token_optional),
):
    data = tile_store.load_tile(world, tx, tz)
    if data is None:
        root = os.environ.get("WORLD_DIR")
        if root:
            region_dir = os.path.join(root, world, "region")
            if os.path.isdir(region_dir):
                from mca_import import PaletteClient, generate_world_tiles
                generate_world_tiles(world, region_dir, PaletteClient(), tile_store)
                data = tile_store.load_tile(world, tx, tz)
    if data is None:
        return Response(status_code=404)
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
        "WHERE s.listed=1 AND s.trade_mode!='buy' AND sp.price>0",
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


@app.get("/logs/world_dir")
def logs_world_dir(token: None = Depends(verify_token)) -> List[Dict[str, Union[str, int, bool]]]:
    """Return world directory detection log entries."""
    entries: List[Dict[str, Union[str, int, bool]]] = []
    if os.path.exists(LOG_PATH):
        with open(LOG_PATH, "r", encoding="utf-8") as f:
            for line in f:
                try:
                    obj = json.loads(line)
                except Exception:
                    continue
                if obj.get("type") == "world_dir":
                    entries.append(obj)
    return entries


@app.get("/api/world_dir")
def world_dir_status() -> Dict[str, Union[str, bool, None]]:
    root = os.environ.get("WORLD_DIR")
    exists = bool(root and os.path.isdir(root))
    return {"world_dir": root, "exists": exists}


@app.websocket("/ws/tiles")
async def ws_tiles(ws: WebSocket):
    global EVENT_LOOP
    EVENT_LOOP = asyncio.get_running_loop()
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


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=int(os.getenv("PORT", "8000")))
