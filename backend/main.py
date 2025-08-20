from fastapi import FastAPI
from pydantic import BaseModel
from typing import Dict, Optional
import sqlite3
from contextlib import closing

# SQLite persistence
conn = sqlite3.connect("economy.db", check_same_thread=False)
conn.row_factory = sqlite3.Row
with conn:
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS accounts (
            player TEXT NOT NULL,
            currency TEXT NOT NULL,
            amount INTEGER NOT NULL,
            PRIMARY KEY(player, currency)
        )
        """
    )
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS currencies (
            name TEXT PRIMARY KEY
        )
        """
    )
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS name_index (
            name TEXT PRIMARY KEY,
            player TEXT NOT NULL
        )
        """
    )

app = FastAPI()


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


def ensure_currency(cur: sqlite3.Cursor, currency: str) -> None:
    cur.execute("INSERT OR IGNORE INTO currencies(name) VALUES (?)", (currency,))


def get_uuid(name: str) -> Optional[str]:
    with closing(conn.cursor()) as cur:
        row = cur.execute("SELECT player FROM name_index WHERE name=?", (name.lower(),)).fetchone()
        return row["player"] if row else None


def get_balance(cur: sqlite3.Cursor, player: str, currency: str) -> int:
    row = cur.execute(
        "SELECT amount FROM accounts WHERE player=? AND currency=?", (player, currency)
    ).fetchone()
    return row["amount"] if row else 0


def set_balance(cur: sqlite3.Cursor, player: str, currency: str, amount: int) -> None:
    cur.execute(
        """
        INSERT INTO accounts(player, currency, amount) VALUES (?,?,?)
        ON CONFLICT(player,currency) DO UPDATE SET amount=excluded.amount
        """,
        (player, currency, amount),
    )


def add_balance(cur: sqlite3.Cursor, player: str, currency: str, delta: int) -> bool:
    bal = get_balance(cur, player, currency)
    new_bal = bal + delta
    if new_bal < 0:
        return False
    set_balance(cur, player, currency, new_bal)
    return True


def transfer(cur: sqlite3.Cursor, src: str, dst: str, currency: str, amount: int) -> bool:
    if amount <= 0:
        return False
    if not add_balance(cur, src, currency, -amount):
        return False
    add_balance(cur, dst, currency, amount)
    return True


def list_balances(cur: sqlite3.Cursor, player: str) -> Dict[str, int]:
    rows = cur.execute(
        "SELECT currency, amount FROM accounts WHERE player=?", (player,)
    ).fetchall()
    return {r["currency"]: r["amount"] for r in rows}


def get_scoreboard(cur: sqlite3.Cursor, player: str) -> Dict[str, int]:
    balances = list_balances(cur, player)
    return {k: v for k, v in balances.items() if k in ("currency1", "currency2")}


@app.get("/api/config")
async def get_config():
    return {"timeout": 2000, "sync_interval": 10}


@app.post("/api/message")
async def message(payload: MessagePayload):
    # index executor name for later lookups
    with conn:
        conn.execute(
            "INSERT OR REPLACE INTO name_index(name, player) VALUES (?, ?)",
            (payload.executor.lower(), payload.player),
        )

    cmd = payload.command.lstrip("/").split()
    if not cmd:
        return {
            "status": "error",
            "messages": [{"target": "chat", "text": "コマンドが指定されていません。"}],
        }

    action = cmd[0].lower()
    messages = []
    scoreboard = None

    def parse_amount(index: int) -> Optional[int]:
        if len(cmd) <= index:
            return None
        try:
            amt = int(cmd[index])
        except ValueError:
            return None
        return amt if amt > 0 else None

    with conn:
        cur = conn.cursor()
        exec_uuid = payload.player

        if action == "currency" and len(cmd) >= 3 and cmd[1].lower() == "create":
            cname = cmd[2]
            ensure_currency(cur, cname)
            messages.append({"target": "chat", "text": f"通貨 {cname} を作成しました。"})
        elif action == "money" and len(cmd) >= 2:
            sub = cmd[1].lower()
            if sub in {"give", "take"} and len(cmd) >= 5:
                target_name = cmd[2].lower()
                currency = cmd[3]
                amt = parse_amount(4)
                target_uuid = get_uuid(target_name)
                if amt is None or target_uuid is None:
                    return {
                        "status": "error",
                        "messages": [{"target": "chat", "text": "引数が不正です。"}],
                    }
                ensure_currency(cur, currency)
                delta = amt if sub == "give" else -amt
                if not add_balance(cur, target_uuid, currency, delta):
                    return {
                        "status": "error",
                        "messages": [{"target": "chat", "text": "残高が不足しています。"}],
                    }
                messages.append({
                    "target": "chat",
                    "text": f"{target_name} の {currency} を {amt} {'付与' if sub=='give' else '減少'}しました。",
                })
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
                    return {
                        "status": "error",
                        "messages": [{"target": "chat", "text": "支払いに失敗しました。"}],
                    }
                ensure_currency(cur, currency)
                messages.append({
                    "target": "chat",
                    "text": f"{src_name} から {dst_name} へ {amt} {currency} 支払いました。",
                })
                if src_uuid == exec_uuid or dst_uuid == exec_uuid:
                    scoreboard = get_scoreboard(cur, exec_uuid)
            else:
                return {
                    "status": "error",
                    "messages": [{"target": "chat", "text": "money コマンドの形式が不正です。"}],
                }
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
                return {
                    "status": "error",
                    "messages": [{"target": "chat", "text": "引数が不正です。"}],
                }
            ensure_currency(cur, currency)
            # deposit moves src -> dst, withdraw moves dst -> src
            ok = transfer(
                cur,
                src_uuid if action == "deposit" else dst_uuid,
                dst_uuid if action == "deposit" else src_uuid,
                currency,
                amt,
            )
            if not ok:
                return {
                    "status": "error",
                    "messages": [{"target": "chat", "text": "残高が不足しています。"}],
                }
            verb = "入金" if action == "deposit" else "引き出し"
            messages.append({
                "target": "chat",
                "text": f"{src_name} から {dst_name} へ {amt} {currency} を{verb}しました。",
            })
            if exec_uuid in {src_uuid, dst_uuid}:
                scoreboard = get_scoreboard(cur, exec_uuid)
        elif action == "balance":
            currency = cmd[1] if len(cmd) >= 2 else None
            ensure_currency(cur, currency) if currency else None
            if currency:
                bal = get_balance(cur, exec_uuid, currency)
                messages.append({"target": "chat", "text": f"{currency}: {bal}"})
            else:
                bals = list_balances(cur, exec_uuid)
                text = (
                    "残高: " + ", ".join(f"{k}={v}" for k, v in bals.items())
                ) if bals else "残高は0です。"
                messages.append({"target": "chat", "text": text})
            scoreboard = get_scoreboard(cur, exec_uuid)
        else:
            messages.append({"target": "chat", "text": f"Echo: {payload.command}"})
            scoreboard = get_scoreboard(cur, exec_uuid)

    res = {"status": "success", "messages": messages}
    if scoreboard is not None:
        res["scoreboard"] = scoreboard
    return res


@app.post("/api/sync")
async def sync(payload: DeltaPayload):
    with conn:
        cur = conn.cursor()
        for k, v in payload.delta.items():
            ensure_currency(cur, k)
            add_balance(cur, payload.player, k, v)
    return {"status": "success"}


@app.post("/api/rewrite")
async def rewrite(payload: RewritePayload):
    with conn:
        cur = conn.cursor()
        for k, v in payload.scoreboard.items():
            ensure_currency(cur, k)
            set_balance(cur, payload.player, k, v)
    return {"status": "success"}
