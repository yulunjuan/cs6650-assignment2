# Assignment 2 初版設計說明（中文）

> 這份文件說明初版（v1）的架構、設計理由、和作業要求的對應，以及每個需要選擇的地方選了什麼、為什麼、另一個方案為什麼相對差。
> 初版的原則是「以符合作業要求的最低限度為準，不寫過度複雜的邏輯」。程式碼註解一律英文，只有這份文件用中文。
> 繳交用的英文架構文件在 `ARCHITECTURE.md`，`AI_USAGE.md` 是草稿；需要手動做的事見 `MANUAL_STEPS_zh.md`。

---

## 1. 整體架構

```
                       HTTP POST /skiers/...                     WebSocket /leaderboard/{resortID}
 Client (A1 client) ─────────────┐                     ┌────────────── Client subscribers
                                 ▼                     ▲
                        ┌──────────────────┐           │
                        │  AWS ALB         │  sticky session (lb_cookie), idle timeout 120s
                        └──────────────────┘
                          │        │       │
                 ┌────────┘        │       └────────┐
                 ▼                 ▼                ▼
          server-v2 #1      server-v2 #2  ...  server-v2 #N     (Tomcat, ski-servlet.war)
          SkierServlet       ...                                 ← 生產者 Producer
          LeaderboardEndpoint (只持有「自己」的 WebSocket session)
          BackplaneListener  (各自一個暫存 queue 綁在 leaderboard.fanout)
                 │ publish (confirm)                     ▲ fanout 推給每一台 server
                 ▼                                       │
        ┌───────────────────────── RabbitMQ (獨立 EC2) ───────────────────────┐
        │ skitrak.exchange (topic)                                           │
        │   resort.1 ─► queue resort.1                                       │
        │   ...                                                              │
        │   resort.10 ─► queue resort.10   (durable, TTL, max-length, DLX)   │
        │ skitrak.dlx (fanout) ─► skitrak.dead   (死信 dead letter)            │
        │ leaderboard.fanout (fanout) ─► 每台 server-v2 的暫存 queue          │
        └────────────────────────────────────────────────────────────────────┘
                 │ deliver (manual ack)                  ▲ publish leaderboard
                 ▼                                       │
          consumer (獨立 EC2, consumer.jar, systemd)
          10 個 channel（每個 resort queue 一個）+ 可動態調整大小的 thread pool
          Aggregator: skierVertical / resortLeaderboards(TopN) / processedEventIds
```

### 1.1 一筆 swipe 到排行榜更新的流程（sequence）

1. Client `POST` → ALB → 任一台 server-v2 的 `SkierServlet`。
2. Servlet 驗證 URL 與 body，用 `LiftVerticals.of(liftID)` 查出垂直落差，組成作業規定格式的 `SwipeMessage`（含 UUID `eventId`、ISO-8601 `timestamp`、`serverId`）。
3. `SwipePublisher` 從 `ChannelPool` 借一個 channel → `basicPublish` 到 `skitrak.exchange`，routing key `resort.{resortID}` → `waitForConfirmsOrDie` 等 broker 確認 → 歸還 channel → 回 `201`。broker 有問題就回 `503`（client 會重試）。
4. RabbitMQ 依 routing key 把訊息放進 `resort.{id}` queue。
5. consumer 中負責該 queue 的 channel 收到訊息 → `Aggregator.apply()`：
   - eventId 處理過 → 視為重複，直接 ack（冪等 idempotent）。
   - 否則把 vertical 加到 `resort:day:skier` 的累計值，再更新 `resort:day` 的 Top-10。
6. 如果 Top-10 有變 → 把排行榜 JSON 發佈到 `leaderboard.fanout`，routing key = resortID。
7. `basicAck`（處理成功才 ack，at-least-once）。
8. fanout 把這筆更新複製給「每一台」server-v2 的暫存 queue → `BackplaneListener` → `LeaderboardEndpoint.broadcast()` → 推給這台 server 上訂閱該 resort 的 WebSocket session。

---

## 2. 程式模組與職責

| 位置 | 類別 / 檔案 | 職責 |
|---|---|---|
| server-v2 | `SkierServlet` | 驗證請求、查 vertical、組訊息、呼叫 publisher，回 201/400/503 |
| server-v2 | `LiftVerticals` | 40 個 lift → vertical（60–400 ft）對照表（教材 repo 未提供，見決策 D24） |
| server-v2 | `mq/ChannelPool` | 單一 Connection＋固定數量的 channel；借/還；斷線後自動重建 |
| server-v2 | `mq/SwipePublisher` | 借 channel → publish → 等 confirm → 還 channel |
| server-v2 | `mq/Topology` | 宣告 exchange、queue、binding、TTL、長度上限、DLX |
| server-v2 | `mq/RabbitConfig` | 從環境變數讀 RabbitMQ 設定、建 `ConnectionFactory` |
| server-v2 | `BackplaneListener` | 每台 server 一個暫存 queue 綁到 `leaderboard.fanout`，收到就交給 endpoint |
| server-v2 | `LeaderboardEndpoint` | 管理本機 WebSocket session（`resortSubscribers`），推送排行榜 |
| server-v2 | `AppLifecycle` | webapp 啟動時建立 pool／宣告 topology／啟動 backplane，關閉時釋放 |
| server-v2 | `HealthServlet` | `/health`，給 ALB 健康檢查 |
| consumer | `Main` | 建 connection（自動恢復）、thread pool、10 個 channel 各訂一個 resort queue、graceful shutdown |
| consumer | `SwipeConsumer` | 單一 resort queue 的處理：解析 → 聚合 → 有變才廣播 → ack；失敗重試、最後送死信 |
| consumer | `Aggregator` | `skierVertical`、`resortLeaderboards`、`processedEventIds`、`swipesProcessed` |
| consumer | `TopN` | 一個 (resort, day) 的前 10 名 |
| consumer | `LoadMonitor` | 每 5 秒：記錄吞吐、consumer lag、queue 深度；依深度調整 thread 數 |
| consumer | `HealthServer` | `:8081/health`，連線中回 200，否則 503 |
| consumer | `Topology` | 與 server-v2 完全相同的一份 |
| deployment | `rabbitmq/`、`server/`、`consumer/`、`alb/` | 安裝 RabbitMQ、部署 server、systemd 與健康檢查重啟、建 ALB |
| monitoring | `queue_monitor.py`、`system_monitor.sh` | 收集 queue 深度／速率、CPU／記憶體／網路 |
| client | （沿用 A1 client-part2） | 排行榜格式相容；只加了測試參數、送完後的等待、最後排行榜輸出（D27） |

---

## 3. 與作業要求的對應

| 作業要求 | 實作位置 | 狀態 |
|---|---|---|
| Part 1：合法 swipe 時查 vertical 並發佈到 queue | `SkierServlet`、`LiftVerticals`、`SwipePublisher` | 完成（教材 repo 沒有 lift 表，見 D24） |
| 規定的訊息格式（eventId…serverId） | `model/SwipeMessage` | 完成 |
| RabbitMQ 部署在獨立 EC2 | `deployment/rabbitmq/install-rabbitmq.sh` | 腳本完成，待實際部署 |
| topic exchange `skitrak.exchange`、routing key `resort.{id}`、queue `resort.1`~`resort.10` | `Topology` | 完成 |
| 設定 message TTL 與 queue 長度上限 | `Topology`（TTL 1 小時、上限 10 萬、reject-publish） | 完成 |
| 共用一個 Connection、channel 借/還，不可每次 publish 開新 channel | `ChannelPool` | 完成 |
| channel 同時只給一個 thread 用 | `ChannelPool`（`BlockingQueue` 借出即獨占） | 完成 |
| 斷線偵測並重連（重建 connection 與 pool） | `ChannelPool.borrowChannel/getConnection` | 完成 |
| Part 2：獨立的 consumer 程式 | `consumer/` | 完成 |
| consumer thread 數可設定 | `CONSUMER_MIN_THREADS` / `CONSUMER_MAX_THREADS` | 完成 |
| 每個 consumer 負責一部分 resort、公平分配 | 每個 resort 一個 channel，由共用 thread pool 輪流處理 | 完成（見 D10） |
| 依負載動態擴縮 | `LoadMonitor.scale()` | 完成（見 D11） |
| 累計 skier vertical、維護 resort Top-N、有變才廣播、最後 ack | `Aggregator`、`TopN`、`SwipeConsumer` | 完成 |
| 規定的 thread-safe 狀態（ConcurrentHashMap、AtomicLong） | `Aggregator`（`resortSubscribers` 在 server-v2，見 D1） | 完成 |
| Connection-locality 問題：選一個方案並說明取捨 | Backplane（`leaderboard.fanout`） | 完成（見 D1） |
| At-least-once（處理成功才手動 ack） | `SwipeConsumer` | 完成 |
| 用 eventId 去重（冪等聚合） | `Aggregator.processedEventIds` | 完成 |
| 每個 resort 依序處理 | 每個 queue 只有一個 consumer／channel | 完成 |
| 失敗重試 | `SwipeConsumer`（重試 3 次 → 死信） | 完成 |
| consumer 在獨立 EC2、連遠端 queue | `consumer.env` 的 `RABBIT_HOST` | 完成 |
| health check、失敗自動重啟 | `HealthServer`、systemd `Restart=always`、`healthcheck.sh` | 完成 |
| Part 3：ALB、target group、sticky session、健康檢查 30/5/2/3、idle timeout > 60s | `deployment/alb/create-alb.sh` | 腳本完成，待實際建立 |
| Part 4：調參與效能測試（1/2/4 台、500K/1M、64~512 client threads、10~80 consumer threads） | 參數都已改成環境變數；監控腳本完成 | **未做**（要在 AWS 上跑） |
| Graceful shutdown | consumer shutdown hook、`AppLifecycle.contextDestroyed` | 完成 |
| 繳交：架構文件（英文 2 頁）、測試結果、截圖、`AI_USAGE.md` | — | **未做** |

---

## 4. 設計決策（選了什麼、為什麼、另一個方案為何較差）

### D1. Connection-locality 問題：選 **廣播背板（Broadcast backplane）**，不選 **同位部署（Co-location）**

**問題**：WebSocket 連線只存在接受它的那台 server-v2 上，但算排行榜的是另一台機器上的 consumer，consumer 沒辦法直接把資料推給 client。

**選擇**：consumer 算出新排行榜後發佈到 RabbitMQ 的 fanout exchange `leaderboard.fanout`。每台 server-v2 啟動時建立自己的暫存 queue 綁上去，所以每筆更新都會送到每台 server，各台再推給自己的訂閱者。

**理由**：
- **跟 ALB 的設計一致**：ALB 用 round-robin＋sticky 把連線分散到各台。同一個 resort 的訂閱者本來就會散在不同 server 上，backplane 不在乎訂閱者在哪台，全部都收得到。
- **擴展簡單**：多加一台 server-v2，它自己綁一個 queue 就加入了，consumer 不用知道有幾台 server。
- **不用多裝東西**：直接用已經存在的 RabbitMQ，不用另外部署 Redis。

**Co-location 為何較差**：
- 要「同一個 resort 的 swipe 和 WebSocket 都進同一台」，ALB 就得依 resort 路由，例如每個 resort 一條 path-based rule 對應一個 target group。這樣每個 resort 都綁死在一台機器上，熱門 resort 沒辦法分散，某台掛了該 resort 就整個不能用。
- consumer 也得和 WebSocket server 合併在同一個程序或同一台機器，違反作業要求的「consumer 是獨立程式、跑在獨立 EC2」，也破壞關注點分離（separation of concerns）。
- 好處只有「省掉一次網路轉送」，延遲稍低，但對這個作業規模不划算。

**Backplane 的代價（文件中要誠實寫出）**：每筆更新都會送給所有 N 台 server，就算某台根本沒有該 resort 的訂閱者。訊息量是「更新數 × N」。N=4、訊息很小時沒問題；server 很多時可以改成「每個 resort 一個 routing key、server 只綁有訂閱者的 resort」（topic exchange），這是之後的優化方向。

**附註**：作業列的狀態裡有 `ConcurrentHashMap<String, Set<Session>> resortSubscribers`。在 backplane 架構下，session 存在 server-v2（`LeaderboardEndpoint`），不在 consumer，因為 consumer 根本不持有 WebSocket 連線。這正是 locality 問題的核心。

### D2. Backplane 用 **RabbitMQ fanout**，不用 **Redis pub/sub**

- **選 RabbitMQ**：已經部署了，少一個元件就少一台 EC2、少一個故障點、少一套監控。作業的 Resources 段落也直接給了 fanout 範例。
- **Redis 為何較差**：功能上可以，但要多部署、多管理一個服務，還要多寫一套連線與重連程式。初版以最少元件為原則。

### D3. Publisher 用 **同步 confirm（`waitForConfirmsOrDie`）**，不用 **不確認** 或 **非同步 confirm**

- **選擇**：每次 publish 後等 broker 確認才回 `201`。
- **理由**：作業目標之一是「No swipe loss under load」。只有 broker 確認收下了，回 201 才是真的「已保存」。如果 broker 拒收（例如 queue 滿了），我們回 503，client 會自己重試，訊息不會悄悄消失。
- **不確認為何較差**：`basicPublish` 只是把資料寫進 socket，broker 沒收到或拒收時 server 完全不知道，還是回 201，資料就這樣丟了。
- **非同步 confirm（async confirms）為何初版不用**：吞吐量較高（不必每筆都等），但要自己維護「sequence number → 未確認訊息」的對照表，還要處理 nack 後怎麼重送，邏輯複雜很多，不符合「最低限度」原則。代價是每個請求多等約一次 broker 往返（~1ms 級）。Part 4 調參時如果瓶頸在這裡，再改成 async 或批次 confirm。

### D4. 訊息 **persistent**、queue **durable**

- **理由**：broker 重啟時訊息不會不見，對應「No swipe loss」。
- **不持久化為何較差**：比較快（不寫磁碟），但 RabbitMQ 一重啟 queue 中所有訊息都會不見。調參時可以把它當成「速度 vs 可靠」的取捨來比較測試。

### D5. Queue 上限 `x-max-length=100000` 搭配 **`x-overflow=reject-publish`**，不用預設的 **drop-head**

- **選擇**：queue 滿時拒絕新的 publish。publisher 會收到 nack，`waitForConfirmsOrDie` 拋例外，server 回 503，client 重試。
- **預設 drop-head 為何較差**：queue 滿時會悄悄把「最舊」的訊息丟掉。這些訊息早就回過 201 給 client，等於已承諾保存的資料憑空消失，直接違反「No swipe loss」。
- **為什麼上限設 10 萬**：作業目標是 queue 深度 < 1000，所以正常情況碰不到上限。上限只是保護 broker 記憶體的安全網。

### D6. Message TTL 1 小時＋**死信交換器（DLX, Dead Letter Exchange）**

- **選擇**：`x-message-ttl=3600000`，過期或被 consumer 放棄的訊息轉到 `skitrak.dlx` → `skitrak.dead` queue。
- **理由**：作業要求要設 TTL。TTL 太短的話，consumer 短暫掛掉時訊息會過期，又變成資料遺失。1 小時足以涵蓋 consumer 重啟。過期的訊息進死信 queue 而不是直接消失，事後還能檢查。
- **沒有 DLX 為何較差**：過期或處理失敗的訊息會直接被丟棄，無從追查。

### D7. Channel pool 的實作：**`ArrayBlockingQueue` 固定大小＋借用時才替換壞掉的 channel**

- **選擇**：啟動時預先建好 `CHANNEL_POOL_SIZE`（預設 64）個 channel 放進 `BlockingQueue`。`borrowChannel()` 用 `poll(5 秒)` 取出；如果 channel 已關閉，就當場建一個新的（必要時先重連 connection）。`returnChannel()` 不管好壞都放回去。
- **理由**：
  - `BlockingQueue` 本身 thread-safe，取出就等於獨占，自然符合「一個 channel 同時只給一個 thread 用」。
  - 「壞的也放回去、借的時候才換」能讓 pool 大小保持固定：建新 channel 失敗時把壞的放回去占住位置，pool 不會越用越小。
- **每次 publish 都開新 channel 為何較差**：作業明確禁止；開 channel 要和 broker 來回協商，很浪費。
- **ThreadLocal channel 為何較差**：每個 Tomcat thread（預設最多 200 個）各綁一個 channel，數量跟著 thread 數走，無法控制；thread 被回收時 channel 也不容易正確關閉。
- **多個 thread 共用一個 channel 為何較差**：RabbitMQ Java client 的 channel 不保證 thread-safe，多 thread 同時 publish 可能交錯成錯誤的 frame，而且 publisher confirm 的序號也會亂掉。

### D8. 重連策略：**Producer 手動重連**，**Consumer／Backplane 用函式庫的 automatic recovery**

- **Producer（`ChannelPool`）手動**：作業的重點就是「自己實作 pool 與重連」。做法是 `getConnection()` 發現 connection 關了就重開（`synchronized`，同一時間只有一個 thread 重連）。另外加了 1 秒的退避（backoff）：broker 掛掉時，1 秒內的其他請求直接失敗（回 503），不會讓 200 個 request thread 都卡在 5 秒的連線逾時上。這算是最簡化的 circuit breaker。
  - 為何關掉 producer 的 automatic recovery：如果兩種機制同時開，函式庫會在背景復原舊 channel，我們也在替換 channel，兩邊互相干擾，行為很難推理。
- **Consumer 與 backplane 用 automatic recovery**：consumer 斷線後除了重連，還要「重新訂閱 10 個 queue、重設 prefetch」；backplane 要「重新宣告暫存 queue、重新 bind、重新訂閱」。函式庫的 topology recovery 本來就會做這些事，自己寫等於重造一遍，容易漏東西。斷線時還沒 ack 的訊息會被 broker 重新投遞，再由 eventId 去重處理掉。
- **全部都手寫為何較差（初版）**：程式碼量多好幾倍、容易出錯，而且作業只要求「發佈端」的 pool 與重連要自己做。

### D9. Pool 大小預設 64（Tomcat 最多 200 個 thread）

- **理由**：channel 很便宜，但也不是越多越好。每個 publish 佔用 channel 的時間約等於一次 confirm 往返，64 個在初版夠用；不夠時 thread 會在 `poll` 等待，不會出錯。
- **之後調整**：作業列出 connection pool size 是調參項目，可用 `CHANNEL_POOL_SIZE` 環境變數不重編譯直接測。

### D10. Consumer threading 模型：**每個 resort queue 一個 channel＋共用的 thread pool**

- **選擇**：consumer 開 10 個 channel，每個只訂閱一個 resort queue（prefetch 預設 100）。所有 channel 的投遞都交給同一個 `ThreadPoolExecutor` 執行，也就是建 connection 時傳進去的 executor。
- **為什麼這樣就能保證每個 resort 依序處理**：RabbitMQ Java client 對同一個 channel 的訊息保證「一次只交給一個 thread、依序執行」。一個 queue 只有一個 consumer、一個 consumer 只在一個 channel 上，所以同一個 resort 的 swipe 一定依序套用。
- **公平分配**：函式庫的工作池會輪流處理有待處理訊息的 channel（每次最多處理一小批再換下一個），10 個 resort 會輪流被服務。
- **「同一個 queue 開多個 consumer」為何較差**：吞吐量會上升，但 RabbitMQ 會把同一個 queue 的訊息輪流分給不同 consumer，兩個 thread 會同時處理同一個 resort，順序無法保證。這直接違反作業的 per-resort ordering 要求（作業的 Common Issues 也特別點名這點）。
- **「固定 N 個 worker，每個 worker 綁一組 resort」為何沒選**：也能滿足「每個 consumer 負責一部分 resort」，但要做動態擴縮時得把 resort 從一個 worker 搬到另一個。搬移過程中舊 worker 手上還有未處理完的訊息，新 worker 又開始收，會短暫亂序，要處理就得寫一大段協調邏輯。現在的設計擴縮只是改 thread pool 大小，完全不影響順序。
- **重要限制（Part 4 一定要寫進報告）**：因為每個 channel 同時只能由一個 thread 處理，**有效並行度最多就是 10（resort 數）**。作業要求測 10、20、40、80 個 consumer threads，預期 20 以上不會再變快。這不是 bug，而是「per-resort ordering」與「並行度」之間的本質取捨。如果真的要突破，就得把每個 resort 再依 skier 切成多個 queue（per-skier ordering 就夠算 vertical），但 Top-N 要另外合併，屬於之後的進階設計。

### D11. 動態擴縮（Dynamic scaling）：**依 queue 總深度調整 thread pool 大小**

- **選擇**：`LoadMonitor` 每 5 秒查 10 個 queue 的總深度。> 500 就把 thread 數加倍（上限 `CONSUMER_MAX_THREADS`，預設 10）；< 50 就減半（下限 `CONSUMER_MIN_THREADS`，預設 2）。`ThreadPoolExecutor` 可以在執行中呼叫 `setCorePoolSize/setMaximumPoolSize` 調整。
- **理由**：這是最小、最安全的「依負載擴縮」。只改「有幾個 thread 在處理」，不動任何 queue 訂閱，所以完全不影響 per-resort 順序。
- **一個小細節**：加大時要先設 max 再設 core，縮小時要先設 core 再設 max，否則會違反 core ≤ max 而拋例外（程式中有註解）。
- **調整 consumer 數量（新增 channel／consumer）為何較差**：見 D10，會破壞順序。
- **門檻值的由來**：作業目標是深度 < 1000，所以超過 500 就提早擴充；50 以下代表很閒。這是經驗值，Part 4 可以再調。

### D12. 冪等（Idempotency）：**記憶體中的 `Set<eventId>`**，且「**更新成功後才標記**」

- **選擇**：`ConcurrentHashMap.newKeySet()` 存所有處理過的 eventId。處理順序是：先檢查是否處理過 → 更新累計與 Top-N → 才加入 Set。
- **為什麼「先檢查、後加入」沒有競爭問題（race condition）**：同一個 eventId 一定屬於同一個 resort，而同一個 resort 同時只有一個 thread 在處理（D10），所以不會有兩個 thread 同時處理同一個 eventId。
- **為什麼要「更新成功後才標記」**：如果先標記，更新途中拋例外，重試時會被當成重複而跳過，那筆 vertical 就永遠漏算了。
- **外部儲存（Redis／資料庫）為何初版不用**：多一個元件、每筆訊息多一次網路往返。初版所有聚合狀態本來就都在記憶體中（見第 5 節限制），去重表也放記憶體，生命週期是一致的。
- **代價**：Set 會一直長大，100 萬筆 UUID 大約 100MB 等級。consumer 跑在 t3.small（2GB）上，systemd 設定 `-Xmx1200m`，留記憶體給作業系統，足夠撐過作業的測試量（1M 筆約 100–200MB）。之後可以改成有上限的 LRU，或依 day 分區、過期清除。

### D13. 每筆訊息個別 ack，不用 **批次 ack（batch ack）**

- **理由**：最簡單、語意最清楚，處理完一筆就 ack 一筆。
- **批次 ack 為何初版不用**：`basicAck(tag, multiple=true)` 可以減少網路往返、提升吞吐量，但要自己計數、還要處理「最後不足一批」的情況（需要計時器補 ack）。作業把 batch ack size 列為調參項目，留到 Part 4 視瓶頸再加。

### D14. 失敗處理：**同一 thread 內重試 3 次，仍失敗就 `basicReject(requeue=false)` 送進死信**

- **理由**：暫時性錯誤（例如廣播時 channel 短暫出錯）重試通常就會成功；解析不了的壞訊息重試也沒用，就送死信 queue 保留起來檢查。
- **`basicNack(requeue=true)` 為何較差**：壞訊息會被放回 queue 開頭立刻再送來，變成無限循環（poison message）。它會卡住整個 resort 的處理，因為每個 resort 依序處理，後面的訊息全部被擋住。
- **重試時不會重複計算**：如果第一次已經更新成功，只是後面的步驟失敗，重試時 eventId 已在 Set 裡，就會被當重複跳過。

### D15. 「Top-N 有變就廣播」，初版不做節流（throttle）

- **理由**：照作業的流程寫：「If the top-N changed, broadcast」。`TopN.update()` 只有在名次表真的變動時才回傳 true，不會每筆 swipe 都廣播。
- **節流（例如每個 resort 每 100ms 最多廣播一次）為何初版不做**：可以大幅減少 WebSocket 流量，但要加計時器與「最後一次更新一定要送出」的邏輯，比較複雜。如果測試時發現 fanout 或 WebSocket 推送成為瓶頸，再加上去。

### D16. Backplane 的 queue：**伺服器自動命名、exclusive、auto-delete，且 auto-ack**

- **理由**：
  - 每台 server 要有「自己的」queue，fanout 才會複製一份給每台；server 關掉時 queue 自動刪除，不會留下垃圾 queue 一直累積訊息。
  - 排行榜是「最新快照」，下一筆會完全取代上一筆，掉一筆影響很小，所以用 auto-ack，省掉 ack 往返。
- **用固定名稱的 durable queue＋manual ack 為何較差**：server 下線後 queue 還在，訊息會一直累積；重新上線時還要先推完一堆過期的排行榜，沒有意義。

### D17. WebSocket 推送：**`synchronized(session)` + `getBasicRemote()`（同步送）**，不用 A1 的 `getAsyncRemote()`

- **理由**：Tomcat 規定同一個 session 上一個非同步寫入還沒完成時，不能再開始下一個，否則拋 `IllegalStateException`（TEXT_FULL_WRITING）。A1 每 500ms 推一次所以沒遇到；A2 的更新頻率高很多，一定會撞到。對 session 加鎖、同步送，是最簡單的保證。
- **代價**：如果某個 client 很慢，backplane 的處理 thread 會被它卡住。之後可以改成「每個 session 一個送信 queue，只保留最新一筆」。

### D18. 新訂閱者連上時立刻送最新排行榜（`latestBoard` 快取）

- **理由**：只多幾行程式。訂閱者一連上就能看到目前排名，不用等到下一次變動；也方便截圖證明「訂閱者收到的是真實資料」。

### D19. Topology **由 server-v2 與 consumer 兩邊都宣告**（兩份完全相同的 `Topology`）

- **理由**：RabbitMQ 的 declare 是冪等的，先啟動哪一邊都可以。
- **只讓 consumer 宣告為何較差**：如果 server 先啟動、consumer 還沒起來，queue 還不存在，發到 exchange 的訊息找不到 queue 就會被直接丟掉，造成遺失。
- **代價**：兩份程式碼要保持一致；如果參數不同，RabbitMQ 會拒絕（PRECONDITION_FAILED），這反而能很快發現不一致。之後可以抽成共用模組，或改由部署腳本宣告。

### D20. 驗證 resortID 必須在 1~10

- **理由**：只有 10 個 queue，resortID=11 的訊息會「無法路由（unroutable）」，被 broker 直接丟掉，而且 confirm 依然會成功（因為沒有用 mandatory flag）。server 會誤回 201，等於資料遺失。所以在入口就回 400。

### D21. 健康檢查與自動重啟

- **server-v2 `/health`**：維持 A1 的固定回 200。broker 連不上時，啟動階段就會 fail-fast（D26），webapp 不會部署成功，`/health` 回 404，ALB 就會判定不健康。
  - **為何不讓 `/health` 檢查 broker 連線**：broker 掛掉時所有 server 都會一起變成不健康，ALB 會沒有任何可用目標。這時檢查 broker 沒有意義，反而讓 server 連 503 都回不了。
- **consumer**：`/health` 回報 connection 是否開著。systemd `Restart=always` 處理「程序崩潰」；`healthcheck.sh`（cron 每分鐘）處理「程序還活著但已經失常」。兩種情況都有涵蓋。

### D22. ALB：**`lb_cookie` sticky session**、idle timeout 120 秒、健康檢查 `/ski-servlet/health`

- **理由**：作業要求 sticky、idle timeout > 60s、健康檢查 30/5/2/3。lb_cookie 由 ALB 自己產生，不用改應用程式。WebSocket 連線一旦升級（upgrade）就固定在那台 target 上，stickiness 主要保證同一個 client 的後續 HTTP 請求也落在同一台。
- **application cookie 為何較差**：需要應用程式自己發 cookie，多寫程式，作業規模不需要。
- **注意**：因為 WAR 部署在 `/ski-servlet`，健康檢查路徑要設成 `/ski-servlet/health`。
- **另一個注意點（測試時會遇到）**：Java `HttpClient` 預設不保存 cookie，所以 A1 client 的 HTTP 請求實際上不會被 sticky，而是平均分散。這對負載平衡反而是好事。報告中要說明：stickiness 對 WebSocket 的作用來自「連線本身固定在一台」。

### D23. 所有設定都用 **環境變數**

- **理由**：Part 4 要測很多組參數（pool size、prefetch、threads），改環境變數再重啟就好，不用重編譯、重新部署 WAR 或 jar。也同時支援 `-D` system property，方便本機測試。

### D24. Lift → vertical 對照表目前是**暫代值**

- 作業說對照表「provided in the starter repo」。2026-11-08 查過課程教材 repo（`vishalrajpal/cs6650-fall-2026`）的最新版本（2026-11-05）與全部 18 個 commit 的歷史，**都沒有這張表**。
- **選擇**：用 60~400 ft 平均分佈的 40 個值（liftID 1 → 60 ft、liftID 40 → 400 ft），寫在 `LiftVerticals.java` 的陣列裡，註解中說明來源。
- **理由**：符合作業給的所有限制（40 個 lift、每個固定、60–400 ft）。評分看的是「有做 vertical 查表並放進訊息」，不是特定數值。表格與程式邏輯分離，日後公布正式表時只要換陣列。
- **隨機產生為何較差**：如果每次啟動隨機產生，不同 server-v2 會得到不同的表，同一個 lift 的 vertical 不一致，違反「fixed vertical drop」。用固定公式就不會有這個問題。

### D25. 排行榜訊息格式沿用 A1，Top-N 取 10

- **理由**：A1 client 只解析 `serverTimestamp` 來算推送延遲，沿用 `{resortID, serverTimestamp, top[{rank, skierID, vertical}]}` 的格式，client 完全不用改。另外多加了 `dayID`，因為排行榜是以 (resort, day) 為單位。
- `serverTimestamp` 現在是 consumer 產生排行榜的時間，client 算出的推送延遲就是「consumer 產生 → 經 backplane → WebSocket → client」的端到端延遲。

### D26. server-v2 啟動時連不上 RabbitMQ 就 **fail-fast**

- **理由**：程式最簡單，狀態也明確：沒有 broker 就不提供服務，ALB 健康檢查會把它移出。
- **代價**：broker 恢復後要手動重啟 Tomcat（或重新部署 WAR）。執行期間斷線則會自動重連（D7、D8），不受影響。

### D27. Client 的小修改：總筆數與輸出檔名改成參數、送完後等 10 秒、印出最後的排行榜

- **總筆數參數**：作業要求 4 台時加跑 1M 壓力測試；原本 500K 寫死在 `Config`，改成第 3 個參數就不用重編譯。
- **輸出檔名前綴（run label）**：原本每次都寫 `part2-latency.csv`，會互相覆蓋。1/2/4 台要各留一份，所以加第 4 個參數當前綴。
- **送完等 10 秒再關 WebSocket**：consumer 處理會稍微落後 ingest。如果一送完就關訂閱，最後一段排行榜更新會收不到。等的時間不算進吞吐量（`endTime` 在等待前就記錄了）。
- **印出每個 resort 最後收到的排行榜**：作業要求「Evidence that subscribers receive real, swipe-driven leaderboard updates」。印出含真實 skierID 與 vertical 的 JSON，可以直接貼進報告。
- **加 shade plugin 產生 `client.jar`**：讓 client 也能放到 EC2 上跑（見手動步驟文件，從筆電跑會受家用網路上傳頻寬限制）。
- **為什麼不另外寫一個新 client**：作業明確說「Run the Assignment 1 client」，改動越少越好。

---

## 5. 已知限制（初版刻意不處理）

1. **聚合狀態只存在 consumer 記憶體**：consumer 重啟後，累計 vertical 與排行榜會歸零（已 ack 的訊息不會重送）。作業沒有要求持久化；之後可以把狀態寫入 Redis 或資料庫。
2. **只能有一個 consumer 程序**：去重表與聚合狀態都在單一程序的記憶體中。如果要多台 consumer，必須依 resort 分區，讓每台負責固定的 queue，否則狀態會分散在各台。
3. **有效並行度上限 10**（見 D10）。
4. **去重 Set 無上限**（見 D12）。
5. **同步 confirm 會限制單台 server 的 publish 吞吐量**（見 D3），這是 Part 4 首先要觀察的瓶頸候選。
6. **WebSocket 慢速 client 可能卡住 backplane thread**（見 D17）。
7. **consumer lag 用兩台機器的時鐘相減**，依賴 EC2 的 NTP 同步，可能有數毫秒誤差。

---

## 6. 尚未完成 / 之後要做的事

| 項目 | 說明 |
|---|---|
需要你手動操作的步驟（AWS 部署、測試、截圖、繳交）都寫在 [MANUAL_STEPS_zh.md](MANUAL_STEPS_zh.md)。

| 項目 | 狀態 |
|---|---|
| lift → vertical 表 | 教材 repo 中沒有（D24），維持暫代表；如果老師之後公布，換陣列即可 |
| 英文架構文件 `ARCHITECTURE.md` | **已完成**（7 節：架構圖、sequence 圖、queue topology、locality、threading、失敗處理、LB） |
| `AI_USAGE.md` | **草稿已完成**：事實紀錄已填；「接受／拒絕的建議」「AI 出錯的地方」標成 TODO，必須由你本人寫 |
| client 測試參數、排行榜證據輸出 | **已完成**（D27） |
| 部署腳本（server、consumer、ALB、RabbitMQ）、監控與指標彙整腳本 | **已完成** |
| 在 AWS 實際部署、端到端測試、Part 4 效能測試、截圖 | **待手動**（本機沒有 RabbitMQ／Docker；目前只驗證了三個專案都能編譯，以及聚合邏輯的單機小測試） |
| 依測試結果調參 | 候選：async/批次 confirm、batch ack、prefetch、pool size、廣播節流 |
| Git | 依指示**尚未建立 repo、尚未上傳 GitHub** |
