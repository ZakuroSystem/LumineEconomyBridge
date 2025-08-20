from fastapi import FastAPI
from pydantic import BaseModel
from typing import Dict

app = FastAPI()

scoreboards: Dict[str, Dict[str, int]] = {}


class MessagePayload(BaseModel):
    player: str
    command: str
    timestamp: int


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
    sb = scoreboards.setdefault(payload.player, {"currency1": 0, "currency2": 0})
    return {
        "status": "success",
        "messages": [{"target": "chat", "text": f"Echo: {payload.command}"}],
        "scoreboard": sb,
    }


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
