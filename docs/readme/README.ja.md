<!-- translated-from: README.md @ 7b09b8113580 -->
<div align="center">

<img src="../media/logo.png" alt="Mika" width="88" />

# Mika

**スマホの中の AI アシスタント。ひと言伝えれば、アプリやウェブを操作して用事を片づけてくれます。**

作業は見えないバックグラウンド画面で進むので、その間もスマホはいつもどおり使えます。

<a href="https://github.com/wangzan101/Mika"><img src="../media/star-banner-v2.png" alt="Mika が気に入ったら Star を —— 右上の Star ボタンをクリック" width="620" /></a>

<br/>
<a href="https://github.com/wangzan101/Mika/releases/latest"><img src="https://img.shields.io/badge/APK%20%E3%82%92%E3%83%80%E3%82%A6%E3%83%B3%E3%83%AD%E3%83%BC%E3%83%89-Android%2011%2B-2ea043?style=for-the-badge&logo=android&logoColor=white" alt="APK をダウンロード" /></a> <a href="https://github.com/wangzan101/Mika"><img src="https://img.shields.io/github/stars/wangzan101/Mika?style=for-the-badge&logo=github&label=Star&color=FFD700&logoColor=white" alt="Star this repo" /></a>

<a href="https://github.com/wangzan101/Mika/releases"><img src="https://img.shields.io/github/downloads/wangzan101/Mika/total?label=%E3%83%80%E3%82%A6%E3%83%B3%E3%83%AD%E3%83%BC%E3%83%89&color=2ea043" alt="downloads" /></a>
<a href="https://github.com/wangzan101/Mika/releases/latest"><img src="https://img.shields.io/github/v/release/wangzan101/Mika?include_prereleases&label=%E3%83%90%E3%83%BC%E3%82%B8%E3%83%A7%E3%83%B3" alt="version" /></a>
<a href="../../LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-lightgrey" alt="license" /></a>

<br/>

<a href="https://trendshift.io/repositories/326940#achievements"><img src="https://trendshift.io/api/badge/trendshift/repositories/326940/daily?language=Kotlin" alt="#1 Kotlin Repository of the Day" width="250" height="55" /></a>
<a href="https://trendshift.io/repositories/326940#achievements"><img src="https://trendshift.io/api/badge/trendshift/repositories/326940/weekly?language=Kotlin" alt="#1 Kotlin Repository of the Week" width="250" height="55" /></a>
<a href="https://trendshift.io/repositories/326940#achievements"><img src="https://trendshift.io/api/badge/trendshift/repositories/326940/daily" alt="#5 Repository of the Day" width="250" height="55" /></a>

<br/>

[English](../../README.md) · [简体中文](README.zh-CN.md) · [繁體中文](README.zh-Hant.md) · 日本語 · [한국어](README.ko.md) · [Español](README.es.md) · [Türkçe](README.tr.md)

<sub>この日本語版は中国語（簡体字）版からの翻訳です。不自然な表現や原文とのずれに気づいたら、ぜひ PR で直してください。</sub>

<br/>

<img src="../media/wechat-send-side.gif" alt="ひと言で、Mika がバックグラウンド画面で WeChat を開き、相手を探してメッセージを送る様子" width="640" />

<sub>「ファイル転送アシスタントにメッセージを送って」のひと言で：左は Mika とのチャット、右はバックグラウンド画面での一つひとつの操作（4 倍速、チャット一覧はぼかし処理済み）</sub>

</div>

## ひと言で、用事がひとつ片づく

<table>
<tr>
<td align="center" width="33%"><img src="../media/wechat-group.gif" width="230" alt="WeChat のグループを読み通して、今日の話題をまとめる" /><br/><b>「グループで今日なにを話してたかまとめて」</b><br/><sub>バックグラウンドで履歴を読み通してから要約</sub></td>
<td align="center" width="33%"><img src="../media/amazon-cart.gif" width="230" alt="通販サイトで充電器を選んでカートに入れる" /><br/><b>「30 ドル以内で星 4.5 以上の充電器」</b><br/><sub>選んでカートに入れ、決済の手前で止まる</sub></td>
<td align="center" width="33%"><img src="../media/xhs-summary.gif" width="230" alt="あるクリエイターの最近の投稿をまとめる" /><br/><b>「このクリエイターの最近の投稿をまとめて」</b><br/><sub>アプリを開いて一件ずつ読み、整理する</sub></td>
</tr>
</table>

<details>
<summary>ほかのデモ</summary>

<table>
<tr>
<td align="center" width="33%"><img src="../media/boss-greet.gif" width="230" alt="求人サイトで 3 件の求人に挨拶を送る" /><br/>求人サイトで 3 件の求人に挨拶を送る</td>
<td align="center" width="33%"><img src="../media/google-flights.gif" width="230" alt="いちばん安い直行便を探す" /><br/>サンフランシスコ発ニューヨーク行きの最安直行便を探す</td>
<td align="center" width="33%"><img src="../media/hacker-news.gif" width="230" alt="トップ 5 件の記事とスコアを読み上げる" /><br/>ニュースサイトのトップ 5 件を読み上げる</td>
</tr>
<tr>
<td align="center"><img src="../media/job-greet.jpg" width="230" alt="求人を探して挨拶を送る" /><br/>求人を探して挨拶を送る</td>
<td align="center"><img src="../media/music-vip.jpg" width="230" alt="広告を見て音楽アプリの特典をもらう" /><br/>広告を見て音楽アプリの特典をもらう</td>
<td align="center"><img src="../media/food-order.jpg" width="230" alt="割引条件を満たすようにデリバリーを注文し、支払い前に確認する" /><br/>割引を組み合わせてデリバリーを注文、支払い前に確認</td>
</tr>
</table>

</details>

<sub>デモに出てくる WeChat はメッセージアプリ、小紅書（RED）はライフスタイル系の SNS です。Mika は特定のアプリ専用ではなく、スマホに入っているどのアプリやサイトでも使えます。</sub>

## Mika を選ぶ理由

| | |
|---|---|
| 📱 **画面を取られない** | 別のバックグラウンド画面でアプリを開き、タップ、入力、スクロールをこなします。その間もスマホは普段どおり。様子を見たいときは小さなプレビューをタップ |
| 🧩 **どのアプリでも使える** | 人と同じように画面を見てボタンを探すので、アプリ側の API も事前の対応作業もいりません |
| 🌐 **ウェブページからそのまま頼める** | 内蔵ブラウザで見ているページに向かってひと言いえば、そのページ上で作業します。ページに触れればすぐ止まります |
| 🧠 **使うほど賢くなる** | 伝えた好みを覚え、うまくいった作業は自動でスキルにまとめて、次からはより速く確実に |
| 🔒 **データはスマホの中だけ** | Mika のサーバーはありません。自分のモデル API キーを使うか、ChatGPT・xAI・OpenRouter のアカウントでログイン |
| 🆓 **オープンソースで無料** | Apache-2.0、コードはすべて公開 |

## 3 ステップで始める

1. **インストール**：[Releases](https://github.com/wangzan101/Mika/releases/latest) から APK をダウンロード。
2. **モデルをつなぐ**：設定 › モデル でプロバイダーを選んで API キーを入力するか、アカウントでログイン（[モデルとログイン](../models.en.md)）。
3. **スマホのアプリを操作させたいとき**：設定の「Set up phone control」ウィザードに沿って、ワイヤレスデバッグを一度だけペアリング（[セットアップガイド](../setup-guide.en.md)）。チャット、ウェブ、ファイル処理だけならこの手順は不要です。

あとは話しかけるだけ：「明日の朝 9:40 と 10:00 にアラームをセットして」「毎朝 8 時に天気を見て、雨のときだけ教えて」。

> 💡 パソコンが手元にあるなら、この一文をパソコンの AI エージェントに送れば、代わりにセットアップしてくれます：<br/>
> `https://github.com/wangzan101/Mika/blob/main/docs/install-with-agent.en.md の手順に従って、USB で接続している Android スマホに Mika をインストールしてください。`

## できること

| | |
|---|---|
| **アプリを操作** | 長いリストやチャット履歴を読む、タップや入力、アプリへのテキスト共有。ログイン、認証コード、支払いはあなたに任せます |
| **ウェブを使う** | ログイン状態を保つ PC 版の内蔵ブラウザで、ページを読み、ダウンロードやアップロードも。ページ送りはペースを守り、ブロックされたらあなたに渡します |
| **いま見ている画面** | フローティングカプセルをダブルタップすると、見ている画面を Mika に送れます：「これなんて返せばいい？」「メモしておいて」 |
| **時間と条件** | 「明日の朝 9 時にリマインドして」「毎週金曜にこれをやって」。時間になると元の会話の中で実行します |
| **いつでも口をはさめる、止められる** | 作業中でも話しかけて要望を足せます。まだ取り込まれていないものは長押しで取り消しや編集も可能。止めたときはどこまで進んだかを伝え、「続けて」でそこから再開します |
| **押して話す** | 入力欄を押したまま話すと文字が入り、直してから送れます。フローティングカプセルは長押しで話し、指を離すとそのまま送信。端末の音声認識を使い、ない場合はプロバイダーの音声モデルを選べます |
| **ファイルと計算** | 会話ごとに Linux サンドボックスがあり、Python、Node、シェルで送ったファイルを処理します（arm64） |
| **パソコンからも使える** | パソコンの AI ツールから CLI + Skill または MCP で Mika に作業を頼めます（[パソコンから Mika を使う](../computer.en.md)） |

初期設定は**確認モード**です。お金がかかること（注文、支払い）だけは先に確認し、それ以外は最後までこなします。**自律モード**に切り替えることもできます。詳しくは[確認モードと自律モード](../modes.en.md)へ。

それぞれの使い方や細かい点は[機能の詳細](../features.en.md)をご覧ください。

<details>
<summary>使い方の知識が用意されているアプリとサイト（載っていないものも普通に使えます）</summary>

一覧は英語版 README の [What it can do](../../README.md#what-it-can-do) にあり、内蔵の知識に合わせて自動で更新されます。

</details>

<details>
<summary>対応しているモデルプロバイダー</summary>

内蔵のプロバイダープリセットと、「Custom」で接続できる API の種類は、英語版 README の [What it can do](../../README.md#what-it-can-do) に一覧があり、コードに合わせて自動で更新されます。

API キーなしで、ChatGPT（Plus / Pro）、xAI / Grok、OpenRouter のアカウントでログインすることもできます。モデルの追加手順や、各サービスでログインするときの注意点は[モデルとログイン](../models.en.md)にまとめています。

</details>

## 対応スマホ

| | 必要な環境 |
|---|---|
| チャット、ウェブ、ファイル | Android 11 以降 |
| スマホのアプリ操作 | Android 12 以降。**Android 14 以降を推奨**（バックグラウンド画面が独自のフォーカスを持つので、メイン画面での入力を邪魔しません） |
| Linux サンドボックス | arm64 端末 |

自分のスマホで動くか不安なら、設定 › 復旧と修復 › **互換性セルフチェック** を実行してください。1 分ほどで 12 項目を確認でき、結果はワンタップで[私たちに送れます](https://github.com/wangzan101/Mika/issues/new?template=device-report.yml)。

## プライバシーと安全

- **Mika のサーバーはありません**：話した内容や画面の内容は、あなたが設定したモデルプロバイダーにだけ送られます。
- API キーと記憶は暗号化してスマホに保存。会話と実行記録は端末内だけに残り、アップデートの確認で接続するのは GitHub だけです。
- 他社のアプリやサイトを自動で操作すると、その利用規約に反する場合があります。ご自身で判断してください。大事な用事は確認モードで行い、結果を確かめてください。詳しくは[免責事項](../disclaimer.en.md)へ。

<details>
<summary><b>よくある質問</b></summary>

**ワイヤレスデバッグが勝手にオフになる？** Android は Wi-Fi を切り替えたときや再起動したときにオフにします。許可しておけば Mika が自分でオンに戻します。それでもつながらなければ、「Set up phone control」に戻ってペアリングし直してください。

**ワイヤレスデバッグの項目が見つからない？** 項目そのものがないスマホもあります。その場合はパソコンから USB 経由でスマホアシスタントを起動できます。[セットアップガイド](../setup-guide.en.md)を参照してください。

**読み取れない、キャプチャできない画面がある？** 支払いやパスワード入力などの画面はスクリーンショットが禁止されているので、Mika はあなたに操作を代わってもらいます。

**自分のパソコンで動かしているモデルは使える？** 使えます。プロバイダーで「Custom」を選び、ローカルネットワーク上のアドレスを入力してください。

**アプリは日本語に対応している？** はい。画面は日本語のほか英語、アラビア語、簡体字中国語、繁体字中国語、韓国語、スペイン語、ポルトガル語、ロシア語、インドネシア語に対応し、システムの言語に合わせて切り替わります（設定 › 言語 でも変更可）。Mika には日本語で話しかけて大丈夫です。内蔵のアプリ・サイト知識は中国語で書かれていますが、モデルは問題なく読めます。

**アップデートはどこから？** このリポジトリの [Releases](https://github.com/wangzan101/Mika/releases) だけで公開しています。アプリの「Mika について › アップデートを確認」からもそのまま更新できます。

ほかの質問と回答は [FAQ](../faq.en.md) にあります。

</details>

## コミュニティと貢献

- **ドキュメント**：すべてのドキュメントとおすすめの読む順番は[ドキュメント一覧](../README.en.md)へ
- **コミュニティ**：[X のグループチャット](https://x.com/i/chat/group_join/g2116926829198246025/48B52j5svL)に参加できます。WeChat で作者に連絡するには、アプリの 「Mika について › 作者に連絡」 にある QR コードを読み取ってください（友達申請に「Mika」と書いてください）
- **作者をフォロー**：X [@wangzan101](https://x.com/wangzan101)
- **問題を報告**：[Issue を作成](https://github.com/wangzan101/Mika/issues/new/choose)。アプリの 「Mika について › フィードバックを送信」 から診断情報を添えられます
- **貢献する**：アプリやサイトの使い方の知識を書き足す（プレーンテキストで、コードは不要）、またはコードで貢献。[コントリビューションガイド](../../.github/CONTRIBUTING.md)をご覧ください

<p align="center">⭐ Mika が時間を節約してくれたら、Star を付けてより多くの人に見つけてもらいましょう。</p>

## Star History

<a href="https://star-history.com/#wangzan101/Mika&Date">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=wangzan101/Mika&type=Date&theme=dark&v=20261110-2017" />
    <img alt="Star History Chart" src="https://api.star-history.com/svg?repos=wangzan101/Mika&type=Date&v=20261110-2017" width="600" />
  </picture>
</a>

## コントリビューター

Mika に関わってくれたすべての方に感謝します：

<a href="https://github.com/wangzan101/Mika/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=wangzan101/Mika&max=100&columns=12" alt="Contributors" />
</a>

## ライセンス

[Apache-2.0](../../LICENSE) · [NOTICE](../../NOTICE) · [サードパーティコンポーネント](../../licenses/THIRD_PARTY_NOTICES.md)
