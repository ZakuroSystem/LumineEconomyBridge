from flask import Flask, render_template, request, redirect, url_for, flash
import sqlite3
import time

app = Flask(__name__)
app.secret_key = "lumineeconomy"
DB_PATH = "economy.db"


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


init_db()


@app.template_filter("fmt_ts")
def fmt_ts(ts: int) -> str:
    return time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(ts))


@app.route("/")
def index():
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
def transactions():
    with get_db() as db:
        txs = db.execute(
            "SELECT id, timestamp, from_account, to_account, currency, amount, reason FROM transactions ORDER BY id DESC LIMIT 50"
        ).fetchall()
    labels = [time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(tx["timestamp"])) for tx in txs]
    amounts = [tx["amount"] for tx in txs]
    return render_template("transactions.html", txs=txs, labels=labels, amounts=amounts)


@app.route("/issuance")
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
def toggle_freeze(uuid: str, state: int):
    with get_db() as db:
        db.execute("UPDATE accounts SET frozen=? WHERE uuid=?", (state, uuid))
        db.commit()
    flash("Account frozen" if state else "Account activated")
    return redirect(url_for("accounts"))


@app.route("/currencies", methods=["GET", "POST"])
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


if __name__ == "__main__":
    app.run(debug=True)
