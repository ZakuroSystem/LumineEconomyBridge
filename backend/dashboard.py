from flask import (
    Flask,
    render_template,
    request,
    redirect,
    url_for,
    flash,
    send_file,
    session,
    g,
    abort,
)
import sqlite3
import time
import os
import json
import yaml
import shutil
import requests
from datetime import datetime
from functools import wraps
from werkzeug.security import generate_password_hash, check_password_hash
from typing import Callable, Dict, Iterable, Tuple, Optional, List
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP

app = Flask(__name__)
app.secret_key = "lumineeconomy"
app.config.update(
    SESSION_COOKIE_HTTPONLY=True,
    SESSION_COOKIE_SAMESITE="Lax",
    SESSION_COOKIE_SECURE=True,
)
DB_PATH = "economy.db"
LOG_PATH = "economy_commands.log"
BACKUP_DIR = "backups"
os.makedirs(BACKUP_DIR, exist_ok=True)
API_TOKEN = os.environ.get("LE_TOKEN", "devtoken")

with open("lang.yml", encoding="utf-8") as f:
    LANG = yaml.safe_load(f)


def wt(key: str) -> str:
    lang = session.get("ui_lang", "en")
    data = LANG.get(lang, {}).get("webui", {})
    for part in key.split('.'):
        if isinstance(data, dict):
            data = data.get(part, {})
        else:
            return key
    return data if isinstance(data, str) else key


def get_db():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn


def parse_amount_field(value: str) -> int:
    try:
        dec = Decimal(value)
    except (InvalidOperation, ValueError):
        raise ValueError
    return int((dec * 1000).to_integral_value(rounding=ROUND_HALF_UP))


def is_currency_manager(db: sqlite3.Connection, uuid: str, currency: str) -> bool:
    row = db.execute(
        "SELECT 1 FROM currency_managers WHERE currency=? AND uuid=?",
        (currency, uuid),
    ).fetchone()
    return row is not None


def init_db() -> None:
    with get_db() as db:
        db.executescript(
            """
            CREATE TABLE IF NOT EXISTS accounts (
                uuid TEXT NOT NULL,
                currency TEXT NOT NULL,
                balance INTEGER NOT NULL,
                frozen INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(uuid, currency)
            );
            CREATE TABLE IF NOT EXISTS transactions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                from_account TEXT,
                to_account TEXT,
                currency TEXT NOT NULL,
                amount INTEGER NOT NULL,
                reason TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS currencies (
                name TEXT PRIMARY KEY,
                symbol TEXT,
                description TEXT,
                active INTEGER NOT NULL DEFAULT 1,
                treasury TEXT,
                tax_rate INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE IF NOT EXISTS currency_managers (
                currency TEXT NOT NULL,
                uuid TEXT NOT NULL,
                PRIMARY KEY(currency, uuid)
            );
            CREATE TABLE IF NOT EXISTS name_index (
                name TEXT PRIMARY KEY,
                uuid TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS players (
                uuid TEXT PRIMARY KEY,
                last_seen INTEGER NOT NULL,
                lang_hint INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE IF NOT EXISTS settings (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS users (
                username TEXT PRIMARY KEY,
                password TEXT NOT NULL,
                is_admin INTEGER NOT NULL DEFAULT 0,
                uuid TEXT
            );
            CREATE TABLE IF NOT EXISTS link_tokens (
                token TEXT PRIMARY KEY,
                uuid TEXT NOT NULL,
                expires INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS admin_users (
                name TEXT PRIMARY KEY
            );
            CREATE TABLE IF NOT EXISTS account_links (
                user_uuid TEXT NOT NULL,
                system_uuid TEXT NOT NULL,
                PRIMARY KEY(user_uuid, system_uuid)
            );
            """
        )
        try:
            db.execute("ALTER TABLE accounts ADD COLUMN frozen INTEGER NOT NULL DEFAULT 0")
        except sqlite3.OperationalError:
            pass
        try:
            db.execute("ALTER TABLE currencies ADD COLUMN description TEXT")
        except sqlite3.OperationalError:
            pass
        try:
            db.execute("ALTER TABLE currencies ADD COLUMN active INTEGER NOT NULL DEFAULT 1")
        except sqlite3.OperationalError:
            pass
        try:
            db.execute("ALTER TABLE currencies ADD COLUMN treasury TEXT")
        except sqlite3.OperationalError:
            pass
        try:
            db.execute("ALTER TABLE currencies ADD COLUMN tax_rate INTEGER NOT NULL DEFAULT 0")
        except sqlite3.OperationalError:
            pass
        db.execute(
            "CREATE TABLE IF NOT EXISTS currency_managers (currency TEXT NOT NULL, uuid TEXT NOT NULL, PRIMARY KEY(currency, uuid))"
        )
        try:
            db.execute("ALTER TABLE players ADD COLUMN lang_hint INTEGER NOT NULL DEFAULT 0")
        except sqlite3.OperationalError:
            pass
        try:
            db.execute("ALTER TABLE users ADD COLUMN uuid TEXT")
        except sqlite3.OperationalError:
            pass

        db.execute(
            "INSERT OR IGNORE INTO users(username, password, is_admin) VALUES(?,?,1)",
            ("admin", generate_password_hash("admin")),
        )
        db.commit()


init_db()


@app.before_request
def load_user():
    username = session.get("user")
    if username:
        with get_db() as db:
            row = db.execute(
                "SELECT username, uuid, is_admin FROM users WHERE username=?",
                (username,),
            ).fetchone()
            if row:
                is_admin = bool(row["is_admin"])
                if not is_admin:
                    is_admin = (
                        db.execute(
                            "SELECT 1 FROM admin_users WHERE name=?",
                            (row["username"],),
                        ).fetchone()
                        is not None
                    )
                g.user = {
                    "username": row["username"],
                    "uuid": row["uuid"],
                    "is_admin": is_admin,
                }
                if is_admin:
                    session.setdefault("admin_mode", True)
                else:
                    session.pop("admin_mode", None)
                with get_db() as db2:
                    g.user["is_currency_manager"] = (
                        db2.execute(
                            "SELECT 1 FROM currency_managers WHERE uuid=? LIMIT 1",
                            (row["uuid"],),
                        ).fetchone()
                        is not None
                    )
            else:
                g.user = None
    else:
        g.user = None




@app.after_request
def add_security_headers(resp):
    resp.headers["X-Content-Type-Options"] = "nosniff"
    resp.headers["X-Frame-Options"] = "DENY"
    resp.headers[
        "Content-Security-Policy"
    ] = (
        "default-src 'self' https://cdn.jsdelivr.net https://fonts.googleapis.com https://fonts.gstatic.com; "
        "style-src 'self' https://cdn.jsdelivr.net https://fonts.googleapis.com 'unsafe-inline'; "
        "font-src 'self' https://cdn.jsdelivr.net https://fonts.gstatic.com; "
        "script-src 'self' https://cdn.jsdelivr.net"
    )
    return resp


def login_required(view):
    @wraps(view)
    def wrapped(*args, **kwargs):
        if g.user is None:
            return redirect(url_for("login"))
        return view(*args, **kwargs)

    return wrapped


def admin_required(view):
    @wraps(view)
    def wrapped(*args, **kwargs):
        if g.user is None or not g.user["is_admin"] or not session.get("admin_mode", False):
            flash("Admin login required")
            return redirect(url_for("index") if g.user else url_for("login"))
        return view(*args, **kwargs)

    return wrapped


@app.context_processor
def inject_user():
    return {"user": g.user, "admin_mode": session.get("admin_mode", False), "wt": wt, "ui_lang": session.get("ui_lang", "en")}


def get_setting(key: str, default: int) -> int:
    with get_db() as db:
        row = db.execute("SELECT value FROM settings WHERE key=?", (key,)).fetchone()
    return int(row["value"]) if row else default


def set_setting(key: str, value: int) -> None:
    with get_db() as db:
        db.execute(
            "INSERT INTO settings(key, value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
            (key, str(value)),
        )
        db.commit()


def list_backups():
    files = []
    for name in os.listdir(BACKUP_DIR):
        if name.endswith(".db"):
            path = os.path.join(BACKUP_DIR, name)
            stat = os.stat(path)
            files.append({"name": name, "size": stat.st_size, "mtime": int(stat.st_mtime)})
    files.sort(key=lambda x: x["mtime"], reverse=True)
    return files


def backup_db() -> str:
    ts = time.strftime("%Y%m%d%H%M%S")
    dest = os.path.join(BACKUP_DIR, f"economy-{ts}.db")
    with get_db() as db:
        dest_conn = sqlite3.connect(dest)
        with dest_conn:
            db.backup(dest_conn)
        dest_conn.close()
    return dest


def restore_db(path: str) -> None:
    if not os.path.exists(path):
        raise FileNotFoundError(path)
    shutil.copy2(path, DB_PATH)


def trim_backups(keep: int) -> None:
    files = list_backups()
    for info in files[keep:]:
        os.remove(os.path.join(BACKUP_DIR, info["name"]))


@app.template_filter("fmt_ts")
def fmt_ts(ts: int) -> str:
    return time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(ts))


@app.route("/login", methods=["GET", "POST"])
def login():
    if request.method == "POST":
        username = request.form["username"].strip()
        password = request.form["password"]
        with get_db() as db:
            row = db.execute(
                "SELECT username, password, is_admin, uuid FROM users WHERE username=?",
                (username,),
            ).fetchone()
        if row and check_password_hash(row["password"], password):
            session["user"] = row["username"]
            return redirect(url_for("index"))
        flash("Invalid credentials")
    return render_template("login.html")


@app.route("/register", methods=["GET", "POST"])
def register():
    if request.method == "POST":
        username = request.form.get("username", "").strip()
        token = request.form.get("token", "").strip()
        raw_password = request.form.get("password", "")
        if not username or not token or not raw_password:
            flash("All fields are required")
        else:
            password = generate_password_hash(raw_password)
            now = int(time.time())
            with get_db() as db:
                row = db.execute(
                    "SELECT uuid FROM link_tokens WHERE token=? AND expires >= ?",
                    (token, now),
                ).fetchone()
                if not row:
                    flash("Invalid or expired token")
                else:
                    try:
                        db.execute(
                            "INSERT INTO users(username, password, is_admin, uuid) VALUES(?,?,0,?)",
                            (username, password, row["uuid"]),
                        )
                        db.execute("DELETE FROM link_tokens WHERE token=?", (token,))
                        db.commit()
                        flash("Registered, please login")
                        return redirect(url_for("login"))
                    except sqlite3.IntegrityError:
                        flash("Username already exists")
    return render_template("register.html")


@app.route("/logout")
def logout():
    session.pop("user", None)
    session.pop("admin_mode", None)
    return redirect(url_for("login"))


@app.route("/mode-toggle")
@login_required
def toggle_mode():
    if not g.user["is_admin"]:
        return redirect(url_for("index"))
    session["admin_mode"] = not session.get("admin_mode", False)
    return redirect(request.referrer or url_for("index"))


@app.get("/ui/lang/<code>")
def set_ui_lang(code: str):
    if code in {"en", "jp"}:
        session["ui_lang"] = code
    return redirect(request.referrer or url_for("index"))


@app.route("/me")
@login_required
def profile():
    with get_db() as db:
        balances = db.execute(
            "SELECT currency, balance FROM accounts WHERE uuid=?",
            (g.user["uuid"],),
        ).fetchall()
        txs = db.execute(
            """
            SELECT timestamp, from_account, to_account, currency, amount, reason
            FROM transactions
            WHERE from_account=? OR to_account=?
            ORDER BY id DESC LIMIT 50
            """,
            (g.user["uuid"], g.user["uuid"]),
        ).fetchall()
    return render_template("profile.html", balances=balances, txs=txs)


@app.route("/")
@login_required
def index():
    if not g.user["is_admin"] or not session.get("admin_mode", False):
        return redirect(url_for("profile"))
    with get_db() as db:
        currencies = [r["name"] for r in db.execute("SELECT name FROM currencies WHERE active=1 ORDER BY name").fetchall()]
        rows = db.execute("SELECT uuid, currency, balance FROM accounts").fetchall()
        total_accounts = db.execute("SELECT COUNT(DISTINCT uuid) AS c FROM accounts").fetchone()["c"]
        since = int(time.time()) - 86400
        rec = db.execute(
            "SELECT COUNT(*) AS cnt, COALESCE(SUM(amount),0) AS amt FROM transactions WHERE timestamp >= ?",
            (since,),
        ).fetchone()
        recent_tx_count = rec["cnt"]
        recent_tx_amount = rec["amt"]
        active_players = db.execute(
            "SELECT COUNT(*) AS c FROM players WHERE last_seen >= ?",
            (since,),
        ).fetchone()["c"]
    accounts = {}
    totals = {c: 0 for c in currencies}
    for row in rows:
        accounts.setdefault(row["uuid"], {})[row["currency"]] = row["balance"]
        totals[row["currency"]] += row["balance"]
    stats = {
        "total_accounts": total_accounts,
        "recent_tx_count": recent_tx_count,
        "recent_tx_amount": recent_tx_amount,
        "active_players": active_players,
    }
    return render_template("index.html", currencies=currencies, accounts=accounts, totals=totals, stats=stats)


@app.route("/transactions")
@admin_required
def transactions():
    player = request.args.get("player", "").strip()
    currency = request.args.get("currency", "").strip()
    min_amt = request.args.get("min", "").strip()
    max_amt = request.args.get("max", "").strip()
    start = request.args.get("start", "").strip()
    end = request.args.get("end", "").strip()

    conditions = []
    params = []
    with get_db() as db:
        # resolve player name to uuid
        if player:
            row = db.execute("SELECT uuid FROM name_index WHERE name=?", (player,)).fetchone()
            uid = row["uuid"] if row else player
            conditions.append("(t.from_account=? OR t.to_account=?)")
            params.extend([uid, uid])
        if currency:
            conditions.append("t.currency=?")
            params.append(currency)
        if min_amt.isdigit():
            conditions.append("t.amount>=?")
            params.append(int(min_amt))
        if max_amt.isdigit():
            conditions.append("t.amount<=?")
            params.append(int(max_amt))
        if start:
            try:
                ts = int(time.mktime(time.strptime(start, "%Y-%m-%d")))
                conditions.append("t.timestamp>=?")
                params.append(ts)
            except ValueError:
                pass
        if end:
            try:
                ts = int(time.mktime(time.strptime(end, "%Y-%m-%d"))) + 86400
                conditions.append("t.timestamp<?")
                params.append(ts)
            except ValueError:
                pass

        where = "WHERE " + " AND ".join(conditions) if conditions else ""
        sql = f"""
            SELECT t.id, t.timestamp, t.from_account, t.to_account,
                   fn.name AS from_name, tn.name AS to_name,
                   t.currency, t.amount, t.reason
            FROM transactions t
            LEFT JOIN name_index fn ON fn.uuid = t.from_account
            LEFT JOIN name_index tn ON tn.uuid = t.to_account
            {where}
            ORDER BY t.id DESC LIMIT 200
        """
        txs = db.execute(sql, params).fetchall()
        currencies = [r["name"] for r in db.execute("SELECT name FROM currencies WHERE active=1 ORDER BY name").fetchall()]

    labels = [time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(tx["timestamp"])) for tx in txs]
    amounts = [tx["amount"] for tx in txs]
    return render_template(
        "transactions.html",
        txs=txs,
        labels=labels,
        amounts=amounts,
        currencies=currencies,
        filter={
            "player": player,
            "currency": currency,
            "min": min_amt,
            "max": max_amt,
            "start": start,
            "end": end,
        },
    )


@app.route("/logs")
@admin_required
def logs():
    exec_q = request.args.get("executor", "").strip()
    type_q = request.args.get("type", "").strip()
    result_q = request.args.get("result", "").strip()
    entries = []
    if os.path.exists(LOG_PATH):
        with open(LOG_PATH, encoding="utf-8") as f:
            lines = f.readlines()[-500:]
        for line in reversed(lines):
            try:
                item = json.loads(line)
            except json.JSONDecodeError:
                continue
            if exec_q and exec_q.lower() not in item.get("executor", "").lower():
                continue
            if type_q and not item.get("command", "").lower().startswith(type_q.lower()):
                continue
            if result_q == "success" and not item.get("success", False):
                continue
            if result_q == "failure" and item.get("success", False):
                continue
            entries.append(item)
    return render_template(
        "logs.html",
        logs=entries,
        filter={"executor": exec_q, "type": type_q, "result": result_q},
    )


@app.route("/logs/download")
@admin_required
def download_logs():
    return send_file(LOG_PATH, as_attachment=True)


@app.route("/logstats")
@admin_required
def logstats():
    stats = {}
    if os.path.exists(LOG_PATH):
        with open(LOG_PATH, encoding="utf-8") as f:
            for line in f:
                try:
                    obj = json.loads(line)
                except json.JSONDecodeError:
                    continue
                key = (obj.get("command", ""), obj.get("success", False))
                stats[key] = stats.get(key, 0) + 1
    chart = {
        "labels": [f"{k[0]} {'✔' if k[1] else '✖'}" for k in stats],
        "datasets": [{"label": "Count", "data": [stats[k] for k in stats]}],
    }
    return render_template("logstats.html", stats=stats, chart_json=json.dumps(chart))


@app.route("/map")
@login_required
def map_view():
    return render_template("map.html", token=API_TOKEN)


@app.route("/backups", methods=["GET", "POST"])
@admin_required
def backups():
    interval = get_setting("auto_backup_interval", 3600)
    keep = get_setting("auto_backup_keep", 10)
    if request.method == "POST":
        action = request.form["action"]
        if action == "create":
            backup_db()
            trim_backups(keep)
            flash("Backup created")
        elif action == "settings":
            try:
                interval = int(request.form.get("interval", interval))
                keep = int(request.form.get("keep", keep))
                set_setting("auto_backup_interval", interval)
                set_setting("auto_backup_keep", keep)
                flash("Settings updated")
            except ValueError:
                flash("Invalid settings")
        return redirect(url_for("backups"))
    files = list_backups()
    return render_template(
        "backups.html",
        backups=files,
        interval=interval,
        keep=keep,
    )


@app.route("/backups/restore/<name>")
@admin_required
def restore_backup(name: str):
    path = os.path.join(BACKUP_DIR, name)
    try:
        restore_db(path)
        flash("Backup restored")
    except FileNotFoundError:
        flash("Backup not found")
    return redirect(url_for("backups"))


@app.route("/issuance")
@admin_required
def issuance():
    with get_db() as db:
        totals = db.execute(
            "SELECT currency, SUM(balance) AS total FROM accounts GROUP BY currency"
        ).fetchall()
        txs = db.execute(
            """
            SELECT t.timestamp, COALESCE(n.name, t.to_account, t.from_account) AS account,
                   t.currency, t.amount, t.reason
            FROM transactions t
            LEFT JOIN name_index n ON n.uuid = COALESCE(t.to_account, t.from_account)
            WHERE t.reason IN ('mint','burn','setbalance')
            ORDER BY t.id DESC LIMIT 100
            """
        ).fetchall()
    return render_template("issuance.html", totals=totals, txs=txs)


@app.route("/adjust", methods=["GET", "POST"])
@admin_required
def adjust():
    preset_uuid = request.args.get("uuid", "")
    if request.method == "POST":
        uuid = request.form["uuid"].strip()
        currency = request.form["currency"].strip()
        action = request.form.get("action", "grant")
        try:
            amount = parse_amount_field(request.form.get("amount", "0"))
        except ValueError:
            flash("Amount must be a number")
            return redirect(url_for("adjust", uuid=uuid))
        reason = request.form.get("reason", "adjust")
        ts = int(time.time())
        with get_db() as db:
            cur = db.cursor()
            cur.execute(
                "INSERT OR IGNORE INTO accounts(uuid, currency, balance) VALUES (?,?,0)",
                (uuid, currency),
            )
            if action == "reset":
                row = cur.execute(
                    "SELECT balance FROM accounts WHERE uuid=? AND currency=?",
                    (uuid, currency),
                ).fetchone()
                delta = -row["balance"] if row else 0
            else:
                delta = amount if action == "grant" else -amount
            cur.execute(
                "UPDATE accounts SET balance = balance + ? WHERE uuid=? AND currency=?",
                (delta, uuid, currency),
            )
            from_acc = None if delta >= 0 else uuid
            to_acc = uuid if delta >= 0 else None
            cur.execute(
                "INSERT INTO transactions(timestamp, from_account, to_account, currency, amount, reason) VALUES (?,?,?,?,?,?)",
                (ts, from_acc, to_acc, currency, abs(delta), reason),
            )
            db.commit()
        flash("Balance adjusted")
        return redirect(url_for("accounts"))
    return render_template("adjust.html", uuid=preset_uuid)


@app.route("/accounts")
@admin_required
def accounts():
    q = request.args.get("q", "").strip()
    with get_db() as db:
        currencies = [r["name"] for r in db.execute("SELECT name FROM currencies WHERE active=1 ORDER BY name").fetchall()]
        if q:
            rows = db.execute(
                """
                SELECT a.uuid, n.name, a.currency, a.balance, a.frozen
                FROM accounts a LEFT JOIN name_index n ON n.uuid=a.uuid
                WHERE a.uuid=? OR n.name LIKE ?
                """,
                (q, f"%{q}%"),
            ).fetchall()
        else:
            rows = db.execute(
                """
                SELECT a.uuid, n.name, a.currency, a.balance, a.frozen
                FROM accounts a LEFT JOIN name_index n ON n.uuid=a.uuid
                """,
            ).fetchall()
    accs = {}
    for r in rows:
        info = accs.setdefault(
            r["uuid"], {"uuid": r["uuid"], "name": r["name"], "frozen": r["frozen"], "balances": {}}
        )
        info["balances"][r["currency"]] = r["balance"]
        if r["frozen"]:
            info["frozen"] = 1
    return render_template("accounts.html", accounts=accs.values(), currencies=currencies, query=q)


@app.route("/accounts/freeze/<uuid>/<int:state>")
@admin_required
def toggle_freeze(uuid: str, state: int):
    with get_db() as db:
        db.execute("UPDATE accounts SET frozen=? WHERE uuid=?", (state, uuid))
        db.commit()
    flash("Account frozen" if state else "Account activated")
    return redirect(url_for("accounts"))


@app.route("/currencies", methods=["GET", "POST"])
@admin_required
def currencies():
    with get_db() as db:
        if request.method == "POST":
            action = request.form["action"]
            if action == "create":
                name = request.form["name"].strip()
                symbol = request.form.get("symbol", "").strip() or None
                desc = request.form.get("description", "").strip()
                db.execute(
                    "INSERT INTO currencies(name, symbol, description, active) VALUES (?,?,?,1)",
                    (name, symbol, desc),
                )
            elif action == "edit":
                name = request.form["name"].strip()
                symbol = request.form.get("symbol", "").strip() or None
                desc = request.form.get("description", "").strip()
                db.execute(
                    "UPDATE currencies SET symbol=?, description=? WHERE name=?",
                    (symbol, desc, name),
                )
            elif action == "toggle":
                name = request.form["name"].strip()
                db.execute(
                    "UPDATE currencies SET active = 1 - active WHERE name=?",
                    (name,),
                )
            db.commit()
        rows = db.execute(
            """
            SELECT c.name, c.symbol, c.description, c.active,
                   COALESCE(SUM(a.balance),0) AS supply
            FROM currencies c
            LEFT JOIN accounts a ON a.currency = c.name
            GROUP BY c.name
            ORDER BY c.name
            """,
        ).fetchall()
    return render_template("currencies.html", currencies=rows)


@app.route("/currency/manage", methods=["GET", "POST"])
@login_required
def currency_manage():
    with get_db() as db:
        if request.method == "POST":
            op = request.form.get("op", "")
            cname = request.form.get("currency", "").strip()
            if op in {"add_manager", "remove_manager"}:
                if not g.user["is_admin"] or not session.get("admin_mode", False):
                    abort(403)
                name = request.form.get("manager", "").strip().lower()
                row = db.execute("SELECT uuid FROM name_index WHERE name=?", (name,)).fetchone()
                if row:
                    if op == "add_manager":
                        db.execute(
                            "INSERT OR IGNORE INTO currency_managers(currency, uuid) VALUES(?,?)",
                            (cname, row["uuid"]),
                        )
                    else:
                        db.execute(
                            "DELETE FROM currency_managers WHERE currency=? AND uuid=?",
                            (cname, row["uuid"]),
                        )
                    db.commit()
                return redirect(url_for("currency_manage"))
            elif op == "set":
                if not (
                    g.user["is_admin"]
                    and session.get("admin_mode", False)
                    or is_currency_manager(db, g.user["uuid"], cname)
                ):
                    abort(403)
                try:
                    tax = Decimal(request.form.get("tax", "0"))
                except InvalidOperation:
                    tax = Decimal(0)
                tax_int = int((tax * 10).to_integral_value(rounding=ROUND_HALF_UP))
                tre_name = request.form.get("treasury", "").strip().lower()
                tre_uuid = None
                if tre_name:
                    row = db.execute("SELECT uuid FROM name_index WHERE name=?", (tre_name,)).fetchone()
                    if row:
                        tre_uuid = row["uuid"]
                db.execute(
                    "UPDATE currencies SET tax_rate=?, treasury=? WHERE name=?",
                    (tax_int, tre_uuid, cname),
                )
                db.commit()
                return redirect(url_for("currency_manage"))
        if g.user["is_admin"] and session.get("admin_mode", False):
            rows = db.execute(
                "SELECT c.name, c.tax_rate, n.name AS treasury_name FROM currencies c LEFT JOIN name_index n ON n.uuid=c.treasury"
            ).fetchall()
        else:
            rows = db.execute(
                "SELECT c.name, c.tax_rate, n.name AS treasury_name FROM currencies c JOIN currency_managers m ON m.currency=c.name AND m.uuid=? LEFT JOIN name_index n ON n.uuid=c.treasury",
                (g.user["uuid"],),
            ).fetchall()
        currencies = []
        for r in rows:
            mgrs = db.execute(
                "SELECT n.name FROM currency_managers m LEFT JOIN name_index n ON n.uuid=m.uuid WHERE m.currency=?",
                (r["name"],),
            ).fetchall()
            currencies.append(
                {
                    "name": r["name"],
                    "tax_rate": r["tax_rate"],
                    "treasury_name": r["treasury_name"],
                    "managers": [m["name"] for m in mgrs],
                }
            )
    return render_template("currency_manage.html", currencies=currencies)


@app.route("/systems", methods=["GET", "POST"])
@admin_required
def systems():
    if request.method == "POST":
        name = request.form.get("name", "").strip()
        if name:
            with get_db() as db:
                db.execute("INSERT OR IGNORE INTO system_accounts(uuid) VALUES(?)", (name,))
                db.execute(
                    "INSERT OR REPLACE INTO name_index(name, uuid) VALUES(?,?)",
                    (name, name),
                )
                db.commit()
            flash("Created system account")
        return redirect(url_for("systems"))
    with get_db() as db:
        rows = db.execute(
            "SELECT sa.uuid, COALESCE(ni.name, sa.uuid) AS name FROM system_accounts sa LEFT JOIN name_index ni ON sa.uuid=ni.uuid ORDER BY name"
        ).fetchall()
        balances = {}
        for r in rows:
            bal_rows = db.execute(
                "SELECT currency, balance FROM accounts WHERE uuid=?", (r["uuid"],)
            ).fetchall()
            balances[r["uuid"]] = {b["currency"]: b["balance"] for b in bal_rows}
    return render_template("systems.html", systems=rows, accounts=balances)


@app.route("/shops")
@admin_required
def shops():
    with get_db() as db:
        rows = db.execute("SELECT s.shop_id, GROUP_CONCAT(o.owner_uuid) AS owners, s.status, s.listed, s.last_activity_at FROM shops s LEFT JOIN shop_owners o ON s.shop_id=o.shop_id GROUP BY s.shop_id").fetchall()
        stats_rows = db.execute(
            """
            SELECT shop_id, COUNT(*) AS cnt, COALESCE(SUM(total_price),0) AS total
            FROM shop_tx WHERE result='success' GROUP BY shop_id
            """
        ).fetchall()
    stats = {r["shop_id"]: r for r in stats_rows}
    shops = []
    for r in rows:
        info = dict(r)
        s = stats.get(r["shop_id"], {"cnt": 0, "total": 0})
        info["sales"] = s["cnt"]
        info["revenue"] = s["total"]
        shops.append(info)
    return render_template("shops.html", shops=shops)


@app.route("/shops/<shop_id>", methods=["GET", "POST"])
@admin_required
def shop_detail(shop_id: str):
    with get_db() as db:
        if request.method == "POST":
            action = request.form.get("action")
            cur = db.cursor()
            if action == "set_stock":
                item_key = request.form["item_key"]
                try:
                    stock = int(request.form["stock"])
                except ValueError:
                    stock = 0
                ts = int(time.time())
                cur.execute(
                    """
                    INSERT INTO shop_stock(shop_id,item_key,stock,updated_at)
                    VALUES (?,?,?,?)
                    ON CONFLICT(shop_id,item_key)
                    DO UPDATE SET stock=excluded.stock, updated_at=excluded.updated_at
                    """,
                    (shop_id, item_key, stock, ts),
                )
            elif action == "set_price":
                item_key = request.form["item_key"]
                currency = request.form["currency"].strip()
                try:
                    price = parse_amount_field(request.form["price"])
                except ValueError:
                    price = 0
                cur.execute(
                    """
                    INSERT INTO shop_prices(shop_id,item_key,currency,price)
                    VALUES (?,?,?,?)
                    ON CONFLICT(shop_id,item_key,currency)
                    DO UPDATE SET price=excluded.price
                    """,
                    (shop_id, item_key, currency, price),
                )
            db.commit()
            flash("Updated")
            return redirect(url_for("shop_detail", shop_id=shop_id))

        shop = db.execute(
            "SELECT s.shop_id, GROUP_CONCAT(o.owner_uuid) AS owners, s.status, s.last_activity_at FROM shops s LEFT JOIN shop_owners o ON s.shop_id=o.shop_id WHERE s.shop_id=? GROUP BY s.shop_id",
            (shop_id,),
        ).fetchone()
        item_rows = db.execute(
            """
            SELECT si.item_key, si.material, si.display_name, ss.stock
            FROM shop_stock ss JOIN shop_items si ON ss.item_key=si.item_key
            WHERE ss.shop_id=?
            """,
            (shop_id,),
        ).fetchall()
        items = []
        for r in item_rows:
            price_rows = db.execute(
                "SELECT currency, price FROM shop_prices WHERE shop_id=? AND item_key=?",
                (shop_id, r["item_key"]),
            ).fetchall()
            items.append(
                {
                    "item_key": r["item_key"],
                    "material": r["material"],
                    "display_name": r["display_name"],
                    "stock": r["stock"],
                    "prices": {p["currency"]: p["price"] / 1000 for p in price_rows},
                }
            )
        sales = db.execute(
            """
            SELECT timestamp, buyer_uuid, item_key, qty, currency, total_price
            FROM shop_tx WHERE shop_id=? AND result='success'
            ORDER BY id DESC LIMIT 100
            """,
            (shop_id,),
        ).fetchall()
    sales_fmt = [
        {**dict(r), "total_price": r["total_price"] / 1000} for r in sales
    ]
    return render_template("shop_detail.html", shop=shop, items=items, sales=sales_fmt)


@app.route("/portal")
@login_required
def portal_index():
    if not g.user["uuid"]:
        flash("Link your account first")
        return redirect(url_for("index"))
    with get_db() as db:
        rows = db.execute(
            "SELECT shop_id, status, last_activity_at, listed FROM shops WHERE shop_id IN (SELECT shop_id FROM shop_owners WHERE owner_uuid=?)",
            (g.user["uuid"],),
        ).fetchall()
        stats_rows = db.execute(
            """
            SELECT shop_id, COUNT(*) AS cnt, COALESCE(SUM(total_price),0) AS total
            FROM shop_tx WHERE result='success' AND shop_id IN (
                SELECT shop_id FROM shop_owners WHERE owner_uuid=?
            )
            GROUP BY shop_id
            """,
            (g.user["uuid"],),
        ).fetchall()
        sys_rows = db.execute(
            """
            SELECT l.system_uuid AS uuid, COALESCE(n.name, l.system_uuid) AS name
            FROM account_links l LEFT JOIN name_index n ON n.uuid=l.system_uuid
            WHERE l.user_uuid=?
            """,
            (g.user["uuid"],),
        ).fetchall()
        systems = []
        for r in sys_rows:
            bals = db.execute(
                "SELECT currency, balance FROM accounts WHERE uuid=?",
                (r["uuid"],),
            ).fetchall()
            systems.append({"uuid": r["uuid"], "name": r["name"], "balances": bals})
    stats = {r["shop_id"]: r for r in stats_rows}
    shops = []
    for r in rows:
        info = dict(r)
        s = stats.get(r["shop_id"], {"cnt": 0, "total": 0})
        info["sales"] = s["cnt"]
        info["revenue"] = s["total"]
        shops.append(info)
    return render_template("my_shops.html", shops=shops, systems=systems, user=g.user)


@app.route("/portal/pay", methods=["POST"])
@login_required
def portal_pay():
    if not g.user["uuid"]:
        flash("Link your account first")
        return redirect(url_for("portal_index"))
    src = request.form.get("src", g.user["uuid"]).strip()
    dst = request.form.get("dst", "").strip().lower()
    amount = request.form.get("amount", "").strip()
    currency = request.form.get("currency", "").strip()
    if not dst or not amount:
        flash("Missing fields")
        return redirect(url_for("portal_index"))
    with get_db() as db:
        if src != g.user["uuid"]:
            row = db.execute(
                "SELECT 1 FROM account_links WHERE user_uuid=? AND system_uuid=?",
                (g.user["uuid"], src),
            ).fetchone()
            if row is None:
                flash("Access denied")
                return redirect(url_for("portal_index"))
    cmd = f"pay {src} {dst} {currency} {amount}" if src != g.user["uuid"] else f"pay {dst} {amount} {currency}".strip()
    payload = {
        "player": g.user["uuid"],
        "executor": g.user["username"],
        "command": cmd.strip(),
        "timestamp": int(time.time()),
        "location": {"world": "world", "x": 0, "y": 0, "z": 0},
    }
    try:
        requests.post("http://127.0.0.1:5100/api/message", json=payload, timeout=5)
    except Exception as e:
        flash(str(e))
    return redirect(url_for("portal_index"))


@app.route("/portal/<shop_id>", methods=["GET", "POST"])
@login_required
def portal_shop(shop_id: str):
    if not g.user["uuid"]:
        flash("Link your account first")
        return redirect(url_for("portal_index"))
    with get_db() as db:
        shop = db.execute(
            "SELECT shop_id, status, last_activity_at, listed FROM shops WHERE shop_id=?",
            (shop_id,),
        ).fetchone()
        owner_check = db.execute(
            "SELECT 1 FROM shop_owners WHERE shop_id=? AND owner_uuid=?",
            (shop_id, g.user["uuid"]),
        ).fetchone()
        if not shop or not owner_check:
            flash("Access denied")
            return redirect(url_for("portal_index"))
        if request.method == "POST":
            action = request.form.get("action")
            cur = db.cursor()
            if action == "set_stock":
                item_key = request.form["item_key"]
                try:
                    stock = int(request.form["stock"])
                except ValueError:
                    stock = 0
                ts = int(time.time())
                cur.execute(
                    """
                    INSERT INTO shop_stock(shop_id,item_key,stock,updated_at)
                    VALUES (?,?,?,?)
                    ON CONFLICT(shop_id,item_key)
                    DO UPDATE SET stock=excluded.stock, updated_at=excluded.updated_at
                    """,
                    (shop_id, item_key, stock, ts),
                )
            elif action == "set_price":
                item_key = request.form["item_key"]
                currency = request.form["currency"].strip()
                try:
                    price = parse_amount_field(request.form["price"])
                except ValueError:
                    price = 0
                cur.execute(
                    """
                    INSERT INTO shop_prices(shop_id,item_key,currency,price)
                    VALUES (?,?,?,?)
                    ON CONFLICT(shop_id,item_key,currency)
                    DO UPDATE SET price=excluded.price
                    """,
                    (shop_id, item_key, currency, price),
                )
            elif action == "set_listing":
                listed = 1 if request.form.get("listed") == "1" else 0
                cur.execute("UPDATE shops SET listed=? WHERE shop_id=?", (listed, shop_id))
            db.commit()
            flash("Updated")
            return redirect(url_for("portal_shop", shop_id=shop_id))
        item_rows = db.execute(
            """
            SELECT si.item_key, si.material, si.display_name, ss.stock
            FROM shop_stock ss JOIN shop_items si ON ss.item_key=si.item_key
            WHERE ss.shop_id=?
            """,
            (shop_id,),
        ).fetchall()
        items = []
        for r in item_rows:
            price_rows = db.execute(
                "SELECT currency, price FROM shop_prices WHERE shop_id=? AND item_key=?",
                (shop_id, r["item_key"]),
            ).fetchall()
            items.append(
                {
                    "item_key": r["item_key"],
                    "material": r["material"],
                    "display_name": r["display_name"],
                    "stock": r["stock"],
                    "prices": {p["currency"]: p["price"] / 1000 for p in price_rows},
                }
            )
        sales = db.execute(
            """
            SELECT timestamp, buyer_uuid, item_key, qty, currency, total_price
            FROM shop_tx WHERE shop_id=? AND result='success'
            ORDER BY id DESC LIMIT 100
            """,
            (shop_id,),
        ).fetchall()
        sales = [{**dict(r), "total_price": r["total_price"] / 1000} for r in sales]
        series = db.execute(
            """
            SELECT strftime('%Y-%m-%d', timestamp, 'unixepoch') AS day, SUM(total_price) total
            FROM shop_tx WHERE shop_id=? AND result='success'
            GROUP BY day ORDER BY day
            """,
            (shop_id,),
        ).fetchall()
    labels = [r["day"] for r in series]
    data = [r["total"] / 1000 for r in series]
    return render_template(
        "my_shop_detail.html",
        shop=shop,
        items=items,
        sales=sales,
        chart_labels=json.dumps(labels),
        chart_data=json.dumps(data),
    )


@app.route("/shopsearch")
@login_required
def shop_search():
    item = request.args.get("item")
    currency = request.args.get("currency")
    min_price = request.args.get("min_price", type=int)
    max_price = request.args.get("max_price", type=int)
    results: List[sqlite3.Row] = []
    if item or currency or min_price is not None or max_price is not None:
        q = [
            "SELECT s.shop_id, si.display_name, ss.sale_name, sp.currency, sp.price, sl.world, sl.x, sl.y, sl.z",
            "FROM shop_prices sp",
            "JOIN shop_stock ss ON sp.shop_id=ss.shop_id AND sp.item_key=ss.item_key",
            "JOIN shop_items si ON sp.item_key=si.item_key",
            "JOIN shops s ON sp.shop_id=s.shop_id",
            "JOIN shop_locations sl ON s.shop_id=sl.shop_id",
            "WHERE s.listed=1",
        ]
        params: List[object] = []
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
        with get_db() as db:
            results = db.execute(" ".join(q), params).fetchall()
    return render_template("shop_search.html", results=results)


@app.route("/shopstats")
@login_required
def shop_stats():
    with get_db() as db:
        rows = db.execute(
            """
            SELECT s.shop_id,
                   COALESCE(v.visits,0) AS visits,
                   COALESCE(r.revenue,0) AS revenue,
                   CASE WHEN r.qty>0 THEN r.revenue*1.0/r.qty ELSE 0 END AS avg_price,
                   CASE WHEN ub.uniques>0 THEN COALESCE(rp.repeaters,0)*1.0/ub.uniques ELSE 0 END AS repeat_rate
            FROM shops s
            LEFT JOIN (SELECT shop_id, COUNT(*) AS visits FROM shop_visits GROUP BY shop_id) v ON s.shop_id=v.shop_id
            LEFT JOIN (SELECT shop_id, SUM(total_price) AS revenue, SUM(qty) AS qty FROM shop_tx WHERE result='success' GROUP BY shop_id) r ON s.shop_id=r.shop_id
            LEFT JOIN (SELECT shop_id, COUNT(DISTINCT buyer_uuid) AS uniques FROM shop_tx WHERE result='success' GROUP BY shop_id) ub ON s.shop_id=ub.shop_id
            LEFT JOIN (
                SELECT shop_id, COUNT(*) AS repeaters FROM (
                    SELECT shop_id, buyer_uuid FROM shop_tx WHERE result='success' GROUP BY shop_id, buyer_uuid HAVING COUNT(*)>1
                ) GROUP BY shop_id
            ) rp ON s.shop_id=rp.shop_id
            ORDER BY revenue DESC LIMIT 10
            """
        ).fetchall()
    labels = [r["shop_id"] for r in rows]
    revenue = [r["revenue"] for r in rows]
    return render_template(
        "shop_stats.html",
        rows=rows,
        chart_labels=json.dumps(labels),
        chart_data=json.dumps(revenue),
    )


@app.route("/sales", methods=["GET", "POST"])
@admin_required
def sales():
    message = None
    if request.method == "POST":
        start = int(datetime.fromisoformat(request.form.get("start")).timestamp())
        end = int(datetime.fromisoformat(request.form.get("end")).timestamp())
        pct = float(request.form.get("pct"))
        account = request.form.get("account", "").strip()
        with get_db() as db:
            db.execute("UPDATE sale_events SET active=0")
            db.execute(
                "INSERT INTO sale_events(start_ts,end_ts,pct,account,active) VALUES(?,?,?,?,1)",
                (start, end, pct, account),
            )
        message = "Sale scheduled"
    with get_db() as db:
        current = db.execute(
            "SELECT start_ts,end_ts,pct,account FROM sale_events WHERE active=1"
        ).fetchone()
    return render_template("sales.html", current=current, message=message)

@app.route("/command", methods=["GET", "POST"])
@admin_required
def command():
    output = None
    if request.method == "POST":
        cmd = request.form.get("command", "").strip()
        if cmd:
            try:
                resp = requests.post(
                    "http://127.0.0.1:5100/api/message",
                    json={
                        "player": "Server",
                        "executor": g.user["username"],
                        "command": cmd,
                        "timestamp": int(time.time()),
                        "location": {"world": "world", "x": 0, "y": 0, "z": 0},
                    },
                    timeout=5,
                )
                data = resp.json()
                output = "\n".join(
                    m.get("text", "") for m in data.get("messages", [])
                )
            except Exception as e:
                output = str(e)
    return render_template("command.html", output=output)


@app.route("/analytics")
@admin_required
def analytics():
    with get_db() as db:
        supply_rows = db.execute(
            """
            SELECT date(timestamp,'unixepoch') AS day, currency,
                   SUM(CASE WHEN from_account IS NULL THEN amount ELSE -amount END) AS delta
            FROM transactions
            WHERE from_account IS NULL OR to_account IS NULL
            GROUP BY day, currency
            ORDER BY day
            """
        ).fetchall()
        days = sorted({r["day"] for r in supply_rows})
        currencies = sorted({r["currency"] for r in supply_rows})
        day_idx = {d: i for i, d in enumerate(days)}
        cum = {c: 0 for c in currencies}
        data = {c: [0] * len(days) for c in currencies}
        for r in supply_rows:
            i = day_idx[r["day"]]
            c = r["currency"]
            cum[c] += r["delta"]
            data[c][i] = cum[c]
        supply = {
            "labels": days,
            "datasets": [
                {"label": c, "data": data[c]} for c in currencies
            ],
        }
        tx_rows = db.execute(
            "SELECT date(timestamp,'unixepoch') AS day, COUNT(*) cnt, SUM(amount) total FROM transactions GROUP BY day ORDER BY day"
        ).fetchall()
        tx = {
            "labels": [r["day"] for r in tx_rows],
            "datasets": [
                {"label": "Amount", "data": [r["total"] for r in tx_rows]},
                {"label": "Count", "data": [r["cnt"] for r in tx_rows]},
            ],
        }
        top_rows = db.execute(
            """
            SELECT COALESCE(ni.name, a.uuid) AS name, SUM(a.balance) AS total
            FROM accounts a
            LEFT JOIN system_accounts sa ON sa.uuid=a.uuid
            LEFT JOIN name_index ni ON ni.uuid=a.uuid
            WHERE sa.uuid IS NULL
            GROUP BY a.uuid
            ORDER BY total DESC
            LIMIT 10
            """
        ).fetchall()
        top = {
            "labels": [r["name"] for r in top_rows],
            "datasets": [
                {"label": "Balance", "data": [r["total"] for r in top_rows]}
            ],
        }
        heat = [[0] * 24 for _ in range(7)]
        heat_rows = db.execute(
            "SELECT strftime('%w',timestamp,'unixepoch') d, strftime('%H',timestamp,'unixepoch') h, COUNT(*) c FROM transactions GROUP BY d,h"
        ).fetchall()
        max_heat = 0
        for r in heat_rows:
            d = int(r["d"])
            h = int(r["h"])
            c = r["c"]
            heat[d][h] = c
            if c > max_heat:
                max_heat = c
    return render_template(
        "analytics.html",
        supply_json=json.dumps(supply),
        tx_json=json.dumps(tx),
        top_json=json.dumps(top),
        heat=heat,
        max_heat=max_heat,
    )


class PaletteClient:
    """HTTP client for the Java mapcolor plugin."""

    def __init__(self, base_url: str, token: str) -> None:
        self._session = requests.Session()
        self._session.headers.update({"X-LE-Token": token})
        self.base_url = base_url.rstrip("/")

    def palette(self) -> Dict:
        resp = self._session.get(f"{self.base_url}/plugin/mapcolor/palette", timeout=10)
        resp.raise_for_status()
        return resp.json()

    def resolve(self, blocks: Iterable[str]) -> Iterable[int]:
        resp = self._session.post(
            f"{self.base_url}/plugin/mapcolor/resolve",
            json={"blocks": list(blocks)},
            timeout=10,
        )
        resp.raise_for_status()
        return resp.json().get("indices", [])


def _chunk_exists(region, cx: int, cz: int) -> bool:
    """Check whether a chunk exists in ``region`` across anvil versions."""

    if hasattr(region, "chunk_data_exists"):
        return region.chunk_data_exists(cx, cz)  # type: ignore[attr-defined]
    off, _ = region.chunk_location(cx, cz)
    return off != 0


def generate_world_tiles(
    world: str,
    region_dir: str,
    client: PaletteClient,
    store: "TileStore",
    log_fn: Callable[[Dict], None] | None = None,
) -> None:
    """Process all region files under ``region_dir`` and output tiles."""

    from collections import defaultdict

    import anvil
    from tile_format import PIXEL_COUNT, TILE_SIZE

    client.palette()  # ensure palette sync
    cache: Dict[str, int] = {}
    tiles: Dict[Tuple[int, int], list[int]] = defaultdict(lambda: [0] * PIXEL_COUNT)

    for fname in os.listdir(region_dir):
        if not fname.endswith(".mca"):
            continue
        r = anvil.Region.from_file(os.path.join(region_dir, fname))
        for cx in range(32):
            for cz in range(32):
                if not _chunk_exists(r, cx, cz):
                    continue
                try:
                    chunk = r.get_chunk(cx, cz)
                except Exception:
                    continue
                global_cx = r.x * 32 + cx
                global_cz = r.z * 32 + cz
                tx, tz = global_cx // 4, global_cz // 4
                tile = tiles[tx, tz]
                for lx in range(16):
                    for lz in range(16):
                        def resolve(name: str) -> int:
                            if name not in cache:
                                cache[name] = next(iter(client.resolve([name]) or [0]))
                            return cache[name]

                        idx = _top_index(chunk, lx, lz, resolve)
                        px = (global_cx % 4) * 16 + lx
                        pz = (global_cz % 4) * 16 + lz
                        tile[pz * TILE_SIZE + px] = idx

    for (tx, tz), indices in tiles.items():
        store.save_tile(world, tx, tz, indices)
        if log_fn:
            log_fn(
                {
                    "type": "tile_generation",
                    "world": world,
                    "tx": tx,
                    "tz": tz,
                }
            )


def _top_index(chunk: "anvil.Chunk", x: int, z: int, resolver: Callable[[str], int]) -> int:
    """Return the colour index for the column at (x,z)."""

    for y in range(250, -64, -1):
        block = chunk.get_block(x, y, z)
        name = getattr(block, "id", "minecraft:air")
        if name != "minecraft:air":
            return resolver(name)
    return 0


if __name__ == "__main__":
    app.run(debug=True)
