from fastapi import FastAPI
from pydantic import BaseModel
from typing import Dict, Optional

# in-memory stores of player balances (uuid keyed) and known name->uuid mappings
scoreboards: Dict[str, Dict[str, int]] = {}
name_index: Dict[str, str] = {}

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


@app.get("/api/config")
async def get_config():
    return {"timeout": 2000, "sync_interval": 10}


@app.post("/api/message")
async def message(payload: MessagePayload):
    # index the executor name for later lookups
    name_index[payload.executor.lower()] = payload.player
    sb = scoreboards.setdefault(payload.player, {"currency1": 0, "currency2": 0})

    cmd = payload.command.lstrip("/").split()
    if not cmd:
        return {
            "status": "error",
            "messages": [{"target": "chat", "text": "コマンドが指定されていません。"}],
        }

    action = cmd[0].lower()
    messages = []

    def parse_amount(index: int) -> Optional[int]:
        if len(cmd) <= index:
            return None
        try:
            amt = int(cmd[index])
        except ValueError:
            return None
        return amt if amt > 0 else None

    if action == "deposit":
        amt = parse_amount(1)
        if amt is None:
            return {"status": "error", "messages": [{"target": "chat", "text": "金額が不正です。"}]}
        sb["currency1"] = sb.get("currency1", 0) + amt
        messages.append({"target": "chat", "text": f"{amt} コイン入金しました。"})
    elif action == "withdraw":
        amt = parse_amount(1)
        if amt is None or sb.get("currency1", 0) < amt:
            return {"status": "error", "messages": [{"target": "chat", "text": "残高または金額が不正です。"}]}
        sb["currency1"] = sb.get("currency1", 0) - amt
        messages.append({"target": "chat", "text": f"{amt} コイン引き出しました。"})
    elif action == "transfer":
        if len(cmd) < 3:
            return {"status": "error", "messages": [{"target": "chat", "text": "/transfer <player> <amount>"}]}
        target_name = cmd[1].lower()
        amt = parse_amount(2)
        target_uuid = name_index.get(target_name)
        if amt is None or target_uuid is None or sb.get("currency1", 0) < amt:
            return {"status": "error", "messages": [{"target": "chat", "text": "送金に失敗しました。"}]}
        sb["currency1"] -= amt
        target_sb = scoreboards.setdefault(target_uuid, {"currency1": 0, "currency2": 0})
        target_sb["currency1"] = target_sb.get("currency1", 0) + amt
        messages.append({"target": "chat", "text": f"{cmd[1]} に {amt} コイン送金しました。"})
    elif action == "balance":
        bal = sb.get("currency1", 0)
        messages.append({"target": "chat", "text": f"残高: {bal} コイン"})
    else:
        # unknown command: echo back
        messages.append({"target": "chat", "text": f"Echo: {payload.command}"})

    res = {"status": "success", "messages": messages}
    if action in {"deposit", "withdraw", "transfer"}:
        res["scoreboard"] = sb
    elif action == "balance":
        res["scoreboard"] = sb
    return res


@app.post("/api/sync")
async def sync(payload: DeltaPayload):
    sb = scoreboards.setdefault(payload.player, {"currency1": 0, "currency2": 0})
    for k, v in payload.delta.items():
        sb[k] = sb.get(k, 0) + v
    return {"status": "success"}


@app.post("/api/rewrite")
async def rewrite(payload: RewritePayload):
    scoreboards[payload.player] = payload.scoreboard
    return {"status": "success"}
