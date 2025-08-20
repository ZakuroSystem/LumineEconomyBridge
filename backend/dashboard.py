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


@app.route("/")
def index():
    with get_db() as db:
        currencies = [r["name"] for r in db.execute("SELECT name FROM currencies ORDER BY name").fetchall()]
        rows = db.execute("SELECT uuid, currency, balance FROM accounts").fetchall()
    accounts = {}
    for row in rows:
        accounts.setdefault(row["uuid"], {})[row["currency"]] = row["balance"]
    return render_template("index.html", currencies=currencies, accounts=accounts)


@app.route("/transactions")
def transactions():
    with get_db() as db:
        txs = db.execute(
            "SELECT id, timestamp, from_account, to_account, currency, amount, reason FROM transactions ORDER BY id DESC LIMIT 50"
        ).fetchall()
    return render_template("transactions.html", txs=txs)


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
