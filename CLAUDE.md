# PlayTimeRewards

放置を除いたプレイ時間を記録して表示し、プレイ時間に応じて LuckPerms のグループを変える Paper 用プラグイン(Paper 1.21.11 以降・Java 21 必須)。
リポジトリ: https://github.com/gorogoro-space/PlayTimeRewards (GPL-3.0)

## 作業の進め方(必ず守ること)

- **設計が確定するまで実装しない。** 機能追加や仕様変更は、まず設計案(何を・なぜ・どう変えるか、影響範囲)を提示し、承認を得てからコードを書く。
- 判断が必要な点は、選択肢を示して質問する。勝手に決めない。
- やり取りは日本語で行う。新しく書くコードのコメントは日本語でよい。
- 変更は必要最小限にする。頼まれていないリファクタリングや機能追加はしない。
- 作業後は、変更・追加・削除したファイルの一覧と変更内容を報告する。
- 仕様を変えたら README.md と CLAUDE.md も合わせて更新する。
- **コミットは「コミットして」と言われるまでしない。** `git push` の前には必ず確認を取る。コミットは意味のある単位で分ける。
- リポジトリへの初回の `git push` の前には、「GitHub で Watch 設定(Custom → Issues と Pull requests)はしましたか?」と日本語で確認する。自動 Watch は廃止されており、設定しないと issue や PR の通知が届かない。
- コミットメッセージや PR に `Co-Authored-By: Claude` などの署名を付けない。`.claude/` と `CLAUDE.local.md` は Git に入れない(`.git/info/exclude` で除外済み)。
- サーバーや開発 PC の環境情報(プラグインの構成・バージョン、設定ファイルの内容、JDK の場所など)は内部情報なので、Git に入るファイル(README.md・CLAUDE.md・コード・コミットメッセージ・issue・PR)に書かない。`CLAUDE.local.md` にだけ書く。コミット前に `git status` で含まれていないことを確認する。
- 実装中に設計の抜けや穴に気づいたら、黙って対処せず報告して相談する。
- プルリクエストをチェックするときは、次の観点で確認して報告する。
  - 変更概要(何を・なぜ変えているか)
  - 脆弱性(権限チェックの漏れ、入力値の検証、パスの扱いなど)
  - 安全面(データの破損・消失、再読み込み時の後始末、他プラグインの妨げなど)
  - 性能(TPS など)の低下(高頻度イベントでの重い処理、メインスレッドでの同期 I/O、定期タスクの追加など。「最重要の設計方針」に沿っているか)

## 環境

- **Paper 1.21.11 以降に対応する。** `io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT`(`compileOnly`)/ `plugin.yml` の `api-version: '1.21.11'`(1.21.11 未満のサーバーでは読み込まれない)。勝手に変えない
- **Java 21 必須。** `build.gradle.kts` の toolchain で JDK 21 を使い、Java 21 の class ファイルを出力する。勝手に変えない
  - paper-api 26.x は「JVM 25 以上専用」と宣言しているため、Java 21 で出力する設定ではビルドできない(1.21 系でビルドしている理由のひとつ)
- ビルド: Gradle(Kotlin DSL、wrapper 9.7.1)。`gradlew.bat clean build`(Windows)。**JDK 21 で実行する**(JDK の場所は `CLAUDE.local.md`)。「ビルドして」と言われたら常にクリーンビルドする。成果物は `build/libs/PlayTimeRewards-<version>.jar`
  - IntelliJ の「アーティファクトのビルド」はクラスファイルが入らないことがある。必ず Gradle でビルドする
- バージョンは `gradle.properties` の `version` だけで管理する。`plugin.yml` の `version: '${version}'` は `processResources` でビルド時に置き換わる
- パッケージ: `space.gorogoro.playtimerewards`(**すべて小文字**。大文字が混ざると plugin.yml の main と一致せず起動しない)
- 動作確認はサーバーを再起動して行う。PlugManX での読み込みは権限やコマンドの登録が不完全になることがある。jar を差し替えるときは先に古い jar を消す(同じ名前のプラグインが 2 つあると、どちらが読まれるかわからない)
- サーバーと開発 PC の環境情報は `CLAUDE.local.md`(Git に入れない)を参照する

## 最重要の設計方針

### TPS に影響させない
- サーバー全体で TPS 20 を維持し、19.0 以下にさせない。この方針を守れない機能は、削除も含めて検討する
- 高頻度イベント(PlayerInteractEvent、PlayerMoveEvent、AsyncPlayerChatEvent など)を使うときは、安い判定を先に行い、対象外なら即座に抜ける
- BlockPhysicsEvent、VehicleMoveEvent など発生頻度が極端に高いイベントは使わない
- メインスレッドでファイルや DB の同期 I/O をしない(起動時・リロード時に一度だけ行う小さなファイルの読み込みは除く)
- 設定ファイルは起動時・リロード時に一度だけ解析して保持する
- 定期タスクは最小限にし、追加するときは頻度と理由を設計案に書く

### データの保存
- ファイルの書き込みはメインスレッド以外(専用スレッド)で行い、まとめて反映する
- プラグインフォルダ以外には何も書き込まない
- 保存データが壊れたり消えたりしないようにする(一時ファイルに書いてから置き換える、バックアップを残す など)

### 他プラグインとの関係
- 既存の機能を妨げないこと。イベントをキャンセルする範囲は必要最小限にする

## 機能仕様

### コマンドと権限
- `/playtime` — 自分の情報(サーバー時刻、今回のログイン(離席は除く)、今日・今週・今月・合計、グループ、昇格の対象なら昇格までの残り)。全員が使える
- `/playtime <プレイヤー名>` — 他の人の情報(初参加からの日数、ログイン日数、最終ログイン、今回のログインと離席中か、今回の離席時間、今日・今週・今月・合計、投票数、グループ)。`playtimerewards.others`(既定 false)
- `/playtime top` — 合計プレイ時間の上位 10 人(全期間)。全員が使える
- `/playtime reload` — config.yml と messages.yml を読み直す(プレイヤーデータは読み直さない)。`playtimerewards.reload`(既定 OP)
- 補完するのは `top`・`reload`(権限があれば)と、ログイン中の人の名前(`others` の権限があれば)だけ

### プレイ時間と離席
- 毎秒は数えない。ログイン・離席・戻り・ログアウトの時刻の差で計算する(`PlayTimeTracker`)
- 操作のイベント(移動・視点・チャット・コマンド・クリック・ブロックの破壊と設置・攻撃・インベントリ操作)では、最後に操作した時刻を記録するだけ。乗り物に乗っている間の移動は数えない(視点の変更は数える)。水流などで流される移動は、歩く移動と区別できないため操作に数える
- 1 分ごとの定期タスクで、操作が `afk-minutes` 分ない人を離席にし、最後に操作した時点までさかのぼってプレイ時間から除く。戻りはイベントで即時に判定する。離席・戻りは全員に通知する
- 日付が変わると今日・今週(月曜始まり)・今月の時間を 0 に戻す。日をまたいでログインし続けている人もログイン日数を 1 日増やす

### 昇格
- config.yml の `promotions` を上から判定する。LuckPerms のプライマリグループが `from` の人の合計時間が `hours` に達したら、`from` を外して `to` を付ける(LuckPerms の API で非同期に書き換える)。それ以外のグループの人には何もしない
- 判定はログイン時と 1 分ごと。昇格したら本人と全員に通知する

### 投票
- Votifier(softdepend)の投票イベントを、クラスを直接参照せずに登録し、記録にある名前なら投票数を 1 増やす。記録に無い名前は無視する

### 保存
- `plugins/PlayTimeRewards/players.yml` に全員分を保存する。起動時に一度だけ読み込み、壊れていて読めないときはプラグインを止める(上書きしない)
- `save-interval-minutes` 分ごとに、メインスレッドで写しを取り、専用スレッドで一時ファイルに書いてから置き換える。前のファイルは `players.yml.bak` として残す。ランキングも同じスレッドで作り直して保持する
- サーバーの停止時だけはメインスレッドで保存する
- 名前で探すときは、自前の「名前 → UUID」の表を使う(Bukkit の名前検索は Mojang への問い合わせで待たされることがあるため使わない)

### 初参加日時
- 初回の起動時に一度だけ、別スレッドでワールドの `playerdata/<UUID>.dat` を自前で読み(Bukkit の API は使わない)、`players.yml` に保存する。読み終えたら `first-played-scan-done: true` を書き、以後は読まない。途中で止めたら次の起動時に続きから読む
- ログインした人は Bukkit の値をその場で記録する。読み込み中は「確認中」、.dat が無い人は「不明」と表示する

## 過去にハマった点(他プロジェクトでの経験)

- `config.getString(path, "")` のように既定値を渡すと、jar 内 config.yml の既定値が参照されない。既定値なしで取得して null を判定すること
- plugin.yml で `default: true` にした権限でも、登録されないと Bukkit は「OP のみ」として扱う。全員向けの機能を権限で縛らない

## ファイル構成

- `src/main/java/space/gorogoro/playtimerewards/PlayTimeRewards.java` — メインクラス(起動・停止、定期タスク、保存)
- `PlayTimeTracker.java` — プレイ時間の計上、離席の判定、操作のイベント
- `Session.java` / `PlayerRecord.java` — ログイン中の状態 / 1 人分の保存データ
- `PlayerDataStore.java` — players.yml の読み込み・書き出し、期間の切り替え、ランキング
- `PromotionManager.java` — LuckPerms のグループによる昇格
- `VoteListener.java` — Votifier の投票の受け取り
- `FirstPlayedScanner.java` — 初参加日時の読み込み(.dat を読む)
- `PlayTimeCommand.java` — /playtime コマンド
- `Settings.java` / `Messages.java` — config.yml / messages.yml
- `src/main/resources/plugin.yml` — プラグイン定義
- `src/main/resources/config.yml` / `messages.yml` — 設定 / 文言(日本語)
- `build.gradle.kts` / `settings.gradle.kts` / `gradle.properties` — Gradle の設定(バージョンは `gradle.properties`)
