# 構成

クラスタ + ミラーキュー



# 2重配信制御

## 2重配信の発生
Spring AMQPのデフォルト設定のacknowledgeModeの設定だと、RabbitMQは配信保証はat-least-once deliveryになるため、必ず配信はされるが、重複配信が発生する。具体的なケースは以下の通り。
* Consumer Aが処理中にRabbitMQとConsumer Aの接続が切断された場合、RabbitMQでは対象メッセージをReadyに戻す。
* このとき他に対象キューに接続するConsumer Bが存在していればConsumer BがReadyのメッセージの処理を開始する。
* Consumer Aはトランザクション成功してDBコミットした後RabbitMQにACKを返却するが接続切れてるので
エラーになる。ただしトランザクションは成功(Listener自体にTransactionManagerを設定しない場合）
* Consumer Bはトランザクション成功してRabbitMQにACKを返す。

## 防止策
Oracle DBの行ロックの仕組みを利用してConsumer Aが処理中の場合、Consumer Bをロック取得エラーで即時エラーとする。
* Publish時にRABBITMQ_MUTEXテーブルにメッセージIDを発番&登録
* RabbitMQへのメッセージヘッダにx-message-mutexとして登録したメッセージIDを設定
* Consumerではx-message-mutexを取り出してselect 1 from RABBITMQ_MUTEX where mutex=? for update nowaitする。既にロックが取られていればエラーでメッセージをDLQに移動する。
* ロックが取れたら処理を続行し、終了すればdelete from RABBITMQ_MUTEX where mutex=?でID削除
* ロック取得〜業務処理〜ID削除まで同一DBコネクション&トランザクションで実行することで、「x-message-mutexがDBに存在=未処理」を確定させる。

![rabbit.png](https://qiita-image-store.s3.amazonaws.com/0/39230/2e32ea7f-4848-0cc2-2453-2e4019f0862f.png "rabbit.png")

TransactionManagerをListenerに設定するとチャネル接続エラーでDBロールバックが実行されてしまうため、ロック取得成功しても業務処理は実行されたことにならなくなってしまう。
そのため、TransactionManagerはListenerには設定しない。

## 状態

x-message-mutexを利用した場合のパターンは以下の通り

| RABBITMQ_MUTEX | DLQ | 通常キュー | 意味 | 実行アクション |
|----------------|-----|------------|------|-----------|
| 有り           | 有り| 有り      | 重複配信が発生しConsumer Aがまだ処理中| wait |
| 有り           | 有り| 無し      | 通常配信でエラーが発生 | rerun |
| 有り           | 無し| 有り　     | 通常配信で処理中 | wait |
| 有り           | 無し| 無し       | Produce時にエラー | ログファイルを確認 |
| 無し           | 有り| 有り       | 不正な状態 | 要調査、アプリが重複配信制御してない可能性 |
| 無し           | 有り| 無し       | 重複は威信が発生しConsumer Aの処理完了 | DLQ削除 |
| 無し           | 無し| 有り       | 不正な状態 | 要調査、アプリが重複配信制御してない可能性 |
| 無し           | 無し| 無し       | 何もしていない | nothing |

