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


@app.template_filter("fmt_ts")
def fmt_ts(ts: int) -> str:
    return time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(ts))


@app.route("/")
def index():
    with get_db() as db:
        currencies = [r["name"] for r in db.execute("SELECT name FROM currencies ORDER BY name").fetchall()]
        rows = db.execute("SELECT uuid, currency, balance FROM accounts").fetchall()
    accounts = {}
    totals = {c: 0 for c in currencies}
    for row in rows:
        accounts.setdefault(row["uuid"], {})[row["currency"]] = row["balance"]
        totals[row["currency"]] += row["balance"]
    return render_template("index.html", currencies=currencies, accounts=accounts, totals=totals)


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
    if request.method == "POST":
        uuid = request.form["uuid"].strip()
        currency = request.form["currency"].strip()
        try:
            amount = int(request.form["amount"])
        except ValueError:
            flash("Amount must be an integer")
            return redirect(url_for("adjust"))
        reason = request.form.get("reason", "adjust")
        ts = int(time.time())
        with get_db() as db:
            cur = db.cursor()
            cur.execute(
                "INSERT OR IGNORE INTO accounts(uuid, currency, balance) VALUES (?,?,0)",
                (uuid, currency),
            )
            cur.execute(
                "UPDATE accounts SET balance = balance + ? WHERE uuid=? AND currency=?",
                (amount, uuid, currency),
            )
            from_acc = None if amount >= 0 else uuid
            to_acc = uuid if amount >= 0 else None
            cur.execute(
                "INSERT INTO transactions(timestamp, from_account, to_account, currency, amount, reason) VALUES (?,?,?,?,?,?)",
                (ts, from_acc, to_acc, currency, abs(amount), reason),
            )
            db.commit()
        flash("Balance adjusted")
        return redirect(url_for("index"))
    return render_template("adjust.html")


if __name__ == "__main__":
    app.run(debug=True)
