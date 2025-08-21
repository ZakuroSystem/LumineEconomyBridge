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
)
import sqlite3
import time
import os
import json
import shutil
import requests
from functools import wraps
from werkzeug.security import generate_password_hash, check_password_hash

app = Flask(__name__)
app.secret_key = "lumineeconomy"
DB_PATH = "economy.db"
LOG_PATH = "economy_commands.log"
BACKUP_DIR = "backups"
os.makedirs(BACKUP_DIR, exist_ok=True)


def get_db():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn


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
                active INTEGER NOT NULL DEFAULT 1
            );
            CREATE TABLE IF NOT EXISTS name_index (
                name TEXT PRIMARY KEY,
                uuid TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS players (
                uuid TEXT PRIMARY KEY,
                last_seen INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS settings (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS users (
                username TEXT PRIMARY KEY,
                password TEXT NOT NULL,
                is_admin INTEGER NOT NULL DEFAULT 0
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
            g.user = db.execute(
                "SELECT username, is_admin FROM users WHERE username=?",
                (username,),
            ).fetchone()
    else:
        g.user = None


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
        if g.user is None or not g.user["is_admin"]:
            flash("Admin login required")
            return redirect(url_for("index") if g.user else url_for("login"))
        return view(*args, **kwargs)

    return wrapped


@app.context_processor
def inject_user():
    return {"user": g.user}


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
                "SELECT username, password, is_admin FROM users WHERE username=?",
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
        username = request.form["username"].strip()
        password = generate_password_hash(request.form["password"])
        try:
            with get_db() as db:
                db.execute(
                    "INSERT INTO users(username, password, is_admin) VALUES(?,?,0)",
                    (username, password),
                )
                db.commit()
            flash("Registered, please login")
            return redirect(url_for("login"))
        except sqlite3.IntegrityError:
            flash("Username already exists")
    return render_template("register.html")


@app.route("/logout")
def logout():
    session.pop("user", None)
    return redirect(url_for("login"))


@app.route("/me")
@login_required
def profile():
    with get_db() as db:
        balances = db.execute(
            "SELECT currency, balance FROM accounts WHERE uuid=?",
            (g.user["username"],),
        ).fetchall()
        txs = db.execute(
            """
            SELECT timestamp, from_account, to_account, currency, amount, reason
            FROM transactions
            WHERE from_account=? OR to_account=?
            ORDER BY id DESC LIMIT 50
            """,
            (g.user["username"], g.user["username"]),
        ).fetchall()
    return render_template("profile.html", balances=balances, txs=txs)


@app.route("/")
@login_required
def index():
    if not g.user["is_admin"]:
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
            amount = int(request.form.get("amount", 0))
        except ValueError:
            flash("Amount must be an integer")
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
                        "command": cmd,
                        "timestamp": int(time.time()),
                        "world": "world",
                        "x": 0,
                        "y": 0,
                        "z": 0,
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


if __name__ == "__main__":
    app.run(debug=True)
