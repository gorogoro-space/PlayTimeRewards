# PlayTimeRewards
[![Paper 1.21.11](https://img.shields.io/badge/Paper-1.21.11-brightgreen.svg)](https://fill-ui.papermc.io/projects/paper/version/1.21.11)
[![GitHub release](https://img.shields.io/github/release/gorogoro-space/PlayTimeRewards.svg)](https://github.com/gorogoro-space/PlayTimeRewards/releases)
[![contributions welcome](https://img.shields.io/badge/contributions-welcome-brightgreen.svg?style=flat)](https://github.com/gorogoro-space/PlayTimeRewards/issues)
[![License: GPL v3](https://img.shields.io/badge/License-GPL%20v3-blue.svg)](https://github.com/gorogoro-space/PlayTimeRewards/blob/main/LICENSE)

This plugin tracks players' active play time and rewards them.

放置している時間を除いたプレイ時間を記録して表示し、プレイ時間に応じて LuckPerms のグループを変えるプラグインです。(開発中)

# Requirements
- Paper 1.21.11 以降(1.21.11 未満のサーバーでは読み込まれません)
- Java 21
- LuckPerms(必須)
- Votifier(任意。入っていれば投票数を数えます)

# Features
- 放置(離席)している時間を除いたプレイ時間を記録します。操作がないまま一定時間(既定 10 分)たつと離席とみなし、最後に操作した時点までさかのぼって除きます。離席したとき・戻ったときは全員に通知します。
- 今日・今週(月曜始まり)・今月・合計のプレイ時間、ログイン日数、投票数を記録します。
- 合計のプレイ時間が設定した時間に達すると、LuckPerms のグループを置き換えます(既定では 5 時間で `default` → `moderator`)。

# Commands
| コマンド | 内容 | 権限 |
|---|---|---|
| `/playtime` | 自分のプレイ時間を表示します | なし(全員) |
| `/playtime <プレイヤー名>` | 他の人の情報を表示します | `playtimerewards.others`(既定: なし) |
| `/playtime top` | 合計プレイ時間の上位 10 人を表示します | なし(全員) |
| `/playtime reload` | config.yml と messages.yml を読み直します | `playtimerewards.reload`(既定: OP) |

# Configuration
- `config.yml` — 離席とみなすまでの分数(`afk-minutes`)、保存の間隔(`save-interval-minutes`)、昇格の段階(`promotions`)
- `messages.yml` — 表示する文言(日本語)。`&` の色記号が使えます
- `players.yml` — プレイヤーデータ。サーバーの稼働中は編集しないでください。保存のたびに前のファイルが `players.yml.bak` として残ります

# Installation method
Please place the .jar file in the Paper plugins folder.

.jar ファイルを Paper の plugins フォルダに置き、サーバーを再起動してください。

# Disclaimer
Do not assume any responsibility by use. Please use it at your own risk.

## ビルド

JDK 21 が必要です。Gradle はラッパー(`gradlew`)が自動でダウンロードするので、別途インストールする必要はありません。

```
gradlew.bat clean build
```

(macOS / Linux では `./gradlew clean build`)

## IntelliJ IDEA でのビルド手順

本プロジェクトはビルドツールに Gradle を使用しています。
IntelliJ IDEA 上で正しくプラグイン（JARファイル）を生成するには、以下の手順を実行してください。
「ビルド → アーティファクトのビルド」はクラスファイルが入らないことがあるので使わないでください。

### 🛠️ ビルド手順

1. IntelliJ IDEA の画面右端にある **「Gradle」タブ** をクリックして開きます。
2. プロジェクト名（PlayTimeRewards）を展開し、 **`Tasks`** ツリーを開きます。
3. リスト内にある **`clean`** をダブルクリックして実行します（古いビルドキャッシュを削除します）。
4. 続けてリスト内にある **`build`** をダブルクリックして実行します。

### 📦 生成されたファイルの場所
ビルドが成功すると、プロジェクトのルート直下に `build/libs` フォルダが作成（または更新）され、その中に中身の詰まった正しい JAR ファイルが生成されます。

* **生成先:** `build/libs/PlayTimeRewards-<version>.jar`(例: `PlayTimeRewards-1.0.jar`。バージョンは `gradle.properties` の `version`)

この JAR ファイルを Minecraft サーバーの `plugins` フォルダに配置してください。

## 開発(IntelliJ IDEA で Claude Code を使う)

このリポジトリには、AI コーディングツール [Claude Code](https://docs.claude.com/ja/docs/claude-code/overview) 向けの作業ルールを書いた `CLAUDE.md` があります。IntelliJ IDEA で使う手順は次のとおりです。

1. Claude の有料プラン(Pro / Max)か、Anthropic API のアカウントを用意します。
2. Windows では、先に [Git for Windows](https://git-scm.com/downloads/win) をインストールします。
3. Claude Code 本体をインストールします。Windows では PowerShell で次を実行します。
   ```
   irm https://claude.ai/install.ps1 | iex
   ```
4. IntelliJ IDEA の「設定 → プラグイン → Marketplace」で「Claude Code」を検索してインストールし、IDE を再起動します。検索結果には Anthropic 以外が作った似た名前のプラグインも表示されます。プラグイン名の下に書かれた提供元が「Anthropic」になっているものを選んでください。
5. プロジェクトを開いて、ターミナルで `claude` を実行します(`Ctrl+Esc` でも起動できます)。初回はブラウザでログインします。

起動すると `CLAUDE.md` が自動で読み込まれ、このプロジェクトの設計方針に沿って作業します。

## ライセンス

[LICENSE](LICENSE) を参照してください。

## kubotan へのメモ

リポジトリを新規作成したら、**必ず Watch を設定すること**(忘れない!)。
GitHub の自動 Watch 機能は 2025 年 5 月に廃止されたため、設定しないと他の人が立てた issue や PR の通知が届かない。

1. リポジトリのページ右上の **「Watch」** を押す
2. **「Custom」** を選び、**Issues** と **Pull requests** にチェックを入れる
