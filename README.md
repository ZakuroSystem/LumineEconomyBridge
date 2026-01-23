# LumineEconomyBridge マニュアル

LumineEconomyBridge は Minecraft のスコアボード経済を Python バックエンドと WebUI に橋渡しするプラグインです。プラグイン側はコマンド転送と GUI 表示を担い、経済ロジックは Python バックエンドで処理します。主要機能・使い方を網羅的にまとめます。

## 1. 主要コンポーネント
- **Minecraft プラグイン**: `/le` コマンド群、ショップ GUI、マーケット、保護機能、クエスト案内などを担当します。
- **FastAPI バックエンド (`backend/main.py`)**: 通貨/取引/ショップ/ログ/バックアップ/クエスト推薦/マップ API など経済ロジック全般を処理します。
- **ダッシュボード (`backend/dashboard.py`)**: WebUI で残高/履歴/ショップ/ログ/バックアップ/地図表示などを提供します。

## 2. 初期設定
1. サーバーに `LumineEconomyBridge.jar` を導入し再起動します。
2. `backend/` ディレクトリで `python main.py` を実行して FastAPI サーバーを起動します。
3. `plugins/LumineEconomyBridge/config.yml` の `api.token` とバックエンドの `LE_TOKEN` を同じ値に設定します。
4. 必要に応じて `LE_API_BASE`（API の外部公開URL）や `MAPCOLOR_URL` を環境変数で指定します。
5. Web ダッシュボードを使う場合は `python backend/dashboard.py` を起動します。

## 3. 機能一覧（全体像）
### 経済/通貨
- 口座の作成・残高表示・送金・預入/引出・履歴取得・残高ランキング。
- 通貨の作成、デフォルト通貨設定、供給量確認、通貨管理者の付与/解除、税率と国庫設定。
- 取り消し（Undo/Redo）やバックアップ/リストア。

### ショップ（ゲーム内 GUI + コマンド）
- GUI でショップ作成/在庫/価格/自動価格調整（autoprice）の編集。
- 共同オーナー・販売口座・ホッパー連携・購入上限・セール設定。
- `shop search` で座標つき検索、`/le search` で公開ショップ検索。

### マーケット
- `/market` で共有マーケットを開く。
- `/market add|remove` でショップの掲載/取り下げ（管理者は即時、プレイヤーは申請）。

### 保護（Protect）
- `/let protect add [id]` で範囲選択を開始し、Breeze Rod で2点を指定して保護範囲を確定。
- `/let protect remove <id>` で保護を解除。

### クエスト
- `/quest` でクエスト一覧表示、`/quest <questId>` で受諾。
- ガイドブックの「ショップ管理」やクエストメニューからも確認可能。

### Web ダッシュボード
- `/register` でユーザー登録（ゲーム内 `/le weblink` のワンタイムトークンが必要）。
- `/login` からログイン後、残高/取引履歴/ショップ/検索/統計/ログ閲覧/バックアップ管理を利用可能。
- `/map` でワールドマップ表示（マップタイル API を利用）。

## 4. コマンド一覧（使い方）
### コンソール/コマンドブロックからの実行
プレイヤー専用の操作は、先頭に対象プレイヤー名を付けて実行できます。

- `/le <player> <command...>` – 例: `/le Steve wallet`
- `/let <player> protect add [id]`
- `/quest <player> [questId]`
- `/market <player>`（マーケットGUIを開く） / `/market add|remove <shop>`（管理者操作）
- `/le giveaway <amount> [period_seconds] [end_seconds] [currency]`（管理者/コンソール専用。オンライン全員に配布）

### `/le` 経済コマンド
- `/le balance [currency] [player]` – 自分/他者の残高表示。
- `/le wallet` – 自分の全通貨残高表示（口座が無ければ自動作成）。
- `/le money give <player> <currency> <amount>` – 付与。
- `/le money take <player> <currency> <amount>` – 没収。
- `/le money pay [src] <dst> [currency] <amount>` – 送金（src省略時は実行者）。
- `/le pay <player> <amount> [currency] [reference]` – 自分から送金、外部決済参照IDの付与に対応。
- `/le money top [currency] [page]` – 残高ランキング。
- `/le deposit <src> <dst> <currency> <amount>` – 預入処理。
- `/le withdraw <src> <dst> <currency> <amount>` – 引出処理。
- `/le transfer <src> <dst> <currency> <amount>` – 振替。
- `/le setbalance <player> <currency> <amount>` – 残高を直接設定。
- `/le history <player> [limit]` – 取引履歴。
- `/le backup` / `/le restore <file>` – バックアップ/復元。
- `/le undo` / `/le redo` – 直近操作の取り消し/やり直し。
- `/le help` – ヘルプ。
- `/le weblink` – Web ダッシュボード連携用ワンタイムトークン発行。

### 通貨管理
- `/le currency create <id> [symbol]` – 通貨作成。
- `/le currency supply [id]` – 通貨供給量。
- `/le currency default <id>` – デフォルト通貨。
- `/le currency manager add <id> <player>` – 通貨管理者付与。
- `/le currency manager remove <id> <player>` – 通貨管理者解除。
- `/le currency tax <id> <trade|transfer> <rate|off> [on|off]` – 税率設定/無効化。
- `/le currency treasury <id> <account>` – 税の納付先設定。

### システム口座
- `/le account create <id>` – システム口座作成。
- `/le account connect <user> <system>` – ユーザーとシステム口座の連携。

### ショップ（GUI + コマンド）
- `/le shop` – ショップ管理 GUI を開く（初回はクイック作成）。
- `/le shop gui` – GUI 呼び出し専用エイリアス。
- `/le shop create <id>` – ショップ作成。
- `/le shop add <id> <qty> <price> <name>` – 在庫追加。
- `/le shop take <id> <item> <qty>` – 在庫回収。
- `/le shop price <id> <name> <currency> <amount> [<currency> <amount>...]` – 価格設定。
- `/le shop autopricedisable <id> [name] [currency]` – 自動価格調整を解除。
- `/le shop remove <id> <name> [refund]` – アイテム削除。
- `/le shop remove <id> [refund]` – ショップ撤去。
- `/le shop reopen <id>` – 停止中ショップの再開。
- `/le shop partner add <id> <player>` – 共同オーナー追加。
- `/le shop partner remove <id> <player>` – 共同オーナー削除。
- `/le shop account <id> <company>` – 取引口座設定。
- `/le shop hopper <id> <slot>` – ホッパー連携。
- `/le shop limit <id> <qty> <once|day|week|month>` – 個人購入上限。
- `/le shop sale <id> <duration> <discount_%>` – 期間限定セール。
- `/le shop search <item-id|name> [currency] [min] [max]` – 座標/在庫/価格つき検索。
- `/le search <item> [currency] [min] [max]` – 公開ショップ検索。

### マーケット
- `/market` – 共有マーケットを開く。
- `/market add <shop>` – ショップをマーケットに掲載（プレイヤーは申請、管理者は即時）。
- `/market remove <shop>` – 掲載解除（プレイヤーは申請、管理者は即時）。

### 保護
- `/let protect add [id]` – 保護範囲選択の開始（Breeze Rod で2点を選択）。
- `/let protect remove <id>` – 保護解除。

### クエスト
- `/quest` – クエスト一覧。
- `/quest <questId>` – クエスト受諾。

### 管理者
- `/le admin add <player>` – Web ダッシュボード管理者に追加。

## 5. Web ダッシュボードの使い方
1. ゲーム内で `/le weblink` を実行し表示されたワンタイムトークンを控えます。
2. ブラウザで `/register` にアクセスし、ユーザー名・パスワード・トークンを入力して登録します。
3. `/login` からログインすると、残高確認、ショップ管理、ショップ検索、ログ/統計/バックアップ管理、マップ表示が利用できます。

## 6. 権限設定
多くのコマンドは標準で管理者権限が必要です。`src/main/resources/permission_confg.txt` を編集すると初期権限を変更できます。

---
詳細な背景や実装方針は `USAGE.md` と `SHOP_HELP.md` を参照してください。
