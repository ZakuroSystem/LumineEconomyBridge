# LumineEconomyBridge Usage

LumineEconomyBridge bridges a Minecraft server's scoreboard economy with a Python backend. All economy logic lives in Python while the plugin simply forwards commands and applies responses.

## Commands
Use `/le` followed by a subcommand. Common commands:

 - `/le balance [currency] [player]` – Show your or another player's balance.
- `/le money give <player> <currency> <amount>` – Mint funds for a player.
- `/le money take <player> <currency> <amount>` – Remove funds from a player.
 - `/le money pay [src] <dst> [currency] <amount>` – Pay from one player to another. Omitting `src` uses the executor; omitting `currency` uses the default.
 - `/le money top [currency] [page]` – List top balances, 10 players per page.
- `/le deposit <src> <dst> <currency> <amount>` – Move funds from src to dst (labelled deposit).
- `/le withdraw <src> <dst> <currency> <amount>` – Move funds from src to dst (labelled withdraw).
- `/le transfer <src> <dst> <currency> <amount>` – Transfer funds between players.
- `/le setbalance <player> <currency> <amount>` – Set a player's balance.
- `/le history <player> [limit]` – View recent transactions.
- `/le currency create <id> [symbol]` – Create a new currency.
- `/le currency supply [id]` – Show total supply for all currencies or a specific one.
- `/le currency default <id>` – Set the default currency used when a command omits one.
- `/le account create <id>` – Create a system account.
- `/le backup` / `/le restore <file>` – Backup or restore the database.
- `/le undo` / `/le redo` – Undo or redo recent operations.
- `/le help` – Show in‑game help.
- `/le weblink` – Generate a token to link your account with the web dashboard.
- `/le search <item> [currency] [min] [max]` – Search public shops for an item.
- `/le admin add <player>` – Grant a player access to the admin dashboard.

## Notes
- All command validation and economy processing happen on the Python backend.
- Unknown or invalid commands return an error followed by a suggestion to use `/le help`.
- Scoreboards for `currency1` and `currency2` are synchronized with the backend every 10 seconds.
- The web dashboard (run `python backend/dashboard.py`) summarizes total accounts, per-currency supply, recent transaction stats, and active players. It also provides account search/editing, currency management tools, and a filterable transaction history page for auditing.
  Additional tools include a command log viewer with filters and download button, and a backup manager to create, restore, and schedule automatic backups.

### Dashboard Login

The dashboard now requires registration and login. Navigate to `/register` to create a user (a default `admin`/`admin` account is available) and log in via `/login`. Regular users can view only their own balances and recent transactions, while admins access all management pages.

 To link a Minecraft account with a web user, run `/le weblink` in game to receive a one‑time token. Enter this token during registration to bind the web account to your player UUID and view your own balances on the dashboard.

For developer details, see `plugin.yml` and the Python sources under `backend/`.
