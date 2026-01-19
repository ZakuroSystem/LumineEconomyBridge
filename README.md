# LumineEconomyBridge マニュアル

LumineEconomyBridge は Minecraft のスコアボード経済を Python バックエンドと WebUI に橋渡しするプラグインです。ここでは導入手順と主要な操作を簡潔に整理します。

## 構成
- **Minecraft プラグイン**: コマンドやGUI操作を受け付け、バックエンドへリクエストを送信します。
- **Python バックエンド**: 経済ロジック・データ管理・WebUI を提供します。

## クイックスタート
1. サーバーに `LumineEconomyBridge.jar` を導入し再起動します。
2. `backend/` ディレクトリで `python main.py` を実行して FastAPI サーバーを起動します。
3. `plugins/LumineEconomyBridge/config.yml` の `api.token` とバックエンドの `LE_TOKEN` を同じ値に設定します。
4. 必要に応じて `LE_API_BASE` や `MAPCOLOR_URL` を環境変数で指定します。

## WebUI の利用
1. ゲーム内で `/le weblink` を実行し表示されたワンタイムトークンを控えます。
2. ブラウザで `/register` にアクセスし、ユーザー名・パスワード・トークンを入力して登録します。
3. `/login` からログインすると、残高確認やショップ管理、ショップ検索などが利用できます。

## プレイヤー向けの主な操作
- `/le wallet` : 自分の口座残高を表示。口座が無い場合は自動作成されます。
- `/le pay <player> <amount> [currency] [reference]` : 他プレイヤーへ送金。`[reference]` は外部決済の識別子として任意に指定できます。
- `/le shop` : ショップ管理GUIを開き、自分のショップ一覧や在庫/価格編集メニューに移動できます。従来どおりショップ樽の受け取りやシフトクリックでの売却も可能です。
- `/le lang <code>` : 経済システムの表示言語を切り替えます (例: `/le lang ja`)。
- `/le search <item> [currency] [min] [max]` : 公開ショップを検索。

## 管理者向けの主な操作
- `/le admin add <player>` : 指定プレイヤーをダッシュボード管理者に追加。
- `/le account connect <user> <system>` : システムアカウントをユーザーへ紐付け。
- `/le currency manager add <id> <player>` : 通貨管理者の追加。管理者は発行・没収・税率/国庫設定が可能。
- `/le currency tax <id> <trade|transfer> <rate|off> [on|off]` : 売買・送金ごとに税率と有効/無効を設定 (税率は0.1%刻み)。
- `/le currency treasury <id> <account>` : 税の納付先アカウントを指定。
- ショップの掲載/非掲載や価格設定、在庫編集はゲーム内のショップ管理GUI（/le shop）から行えます。詳細なログ閲覧などは WebUI のメニューから利用してください。

## 権限設定
多くのコマンドは標準で管理者権限が必要です。`src/main/resources/permission_confg.txt` を編集すると初期権限を変更できます。

## 参考ドキュメント
- 詳細なコマンド一覧: `USAGE.md`
- ショップ運営の詳細: `SHOP_HELP.md`
