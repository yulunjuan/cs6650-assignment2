# 需要手動操作的步驟

> 程式碼、腳本、英文架構文件、AI_USAGE 草稿都已完成。以下是只能由你在 AWS 上或自己做的事，依執行順序排列。
> 截止日是 11/8。時間不夠時的優先順序：步驟 1–6（單台跑通、拿到證據）> 步驟 8–9（2 台、4 台）> 步驟 7 的參數掃描 > 步驟 10 的故障測試。
> 所有指令都在 Git Bash 執行。下面的 `MVN` 是 IntelliJ 內建 Maven 的路徑（你的 PATH 上沒有 `mvn`）。

```bash
export MVN="/c/Program Files/JetBrains/IntelliJ IDEA 2025.2.1/plugins/maven/lib/maven3/bin/mvn"
export KEY=/d/path/to/cs6650-lab1-key.pem      # 換成你 A1 用的 key
cd /d/NEU/CS6650/Assignment/Assignment2
```

---

## 步驟 1：建立 Security Group（約 10 分鐘）

最簡單的做法是建一個共用的 SG `skitrak-sg`，套在所有 EC2 上：

| Inbound 規則 | Port | 來源 | 用途 |
|---|---|---|---|
| All traffic | 全部 | `skitrak-sg` 自己（選 SG ID） | 機器彼此互通（5672、8080、8081） |
| SSH | 22 | My IP | ssh/scp 部署 |
| Custom TCP | 15672 | My IP | RabbitMQ 管理介面 |
| Custom TCP | 8080 | My IP | 直接測單台 server |
| Custom TCP | 8081 | My IP | consumer `/health`（可選） |

ALB 另外建一個 `skitrak-alb-sg`：Inbound HTTP 80，來源 `0.0.0.0/0`。然後在 `skitrak-sg` 加一條規則：「All traffic，來源 `skitrak-alb-sg`」。

---

## 步驟 2：RabbitMQ EC2（約 10 分鐘）

1. 啟動一台 **Amazon Linux 2023、t3.small**（t3.micro 只有 1GB，persistent 訊息加上管理介面容易吃緊），套用 `skitrak-sg`。
2. 上傳並執行安裝腳本，**密碼自己設定**：
   ```bash
   scp -i $KEY deployment/rabbitmq/install-rabbitmq.sh ec2-user@<RABBIT_PUBLIC_IP>:~
   ssh -i $KEY ec2-user@<RABBIT_PUBLIC_IP> 'RABBIT_PASS=<你的密碼> bash install-rabbitmq.sh'
   ```
3. 瀏覽器開 `http://<RABBIT_PUBLIC_IP>:15672`，用 `skitrak / <你的密碼>` 登入，能進去就成功了。
4. 記下這台的 **Private IP**（server 和 consumer 都用 private IP 連它）。

---

## 步驟 3：填設定檔（約 2 分鐘）

- `deployment/server/setenv.sh`：`RABBIT_HOST` 改成 RabbitMQ 的 private IP，`RABBIT_PASS` 改成你的密碼。
- `deployment/consumer/consumer.env`：同上。
- ⚠️ 這兩個檔案含密碼。之後上傳 GitHub 前，記得把密碼改回 `change-me`。

---

## 步驟 4：第一台 server-v2（約 15 分鐘）

1. 最快的方式：在 EC2 console 對 **A1 的 server instance** 做 *Actions → Image → Create image*，產生 AMI（裡面已經裝好 Tomcat 9 和 JDK）。再用這個 AMI 啟動新的 instance（**建議 t3.small 以上**；t3.micro 跑 200 個 Tomcat thread 加上 RabbitMQ client 會很緊），套用 `skitrak-sg`。
2. 部署：
   ```bash
   ./deployment/server/deploy-server.sh $KEY <SERVER1_PUBLIC_IP>
   ```
   腳本會把 RabbitMQ 設定放到 `/opt/tomcat9/bin/skitrak-env.sh`，並從 `setenv.sh` 引用它，所以原本 `setenv.sh` 裡的 `JAVA_HOME` 會保留。
3. 檢查：
   ```bash
   curl http://<SERVER1_PUBLIC_IP>:8080/ski-servlet/health      # {"message":"OK"}
   ssh -i $KEY ec2-user@<SERVER1_PUBLIC_IP> 'grep -E "server-v2|Exception" /opt/tomcat9/logs/catalina.out | tail'
   ```
   應該要看到 `server-v2 ip-xxx connected to RabbitMQ, channel pool size 64`。
4. 回到 RabbitMQ 管理介面的 **Exchanges／Queues** 分頁：應該看到 `skitrak.exchange`、`leaderboard.fanout`、`skitrak.dlx`，以及 `resort.1`~`resort.10`、`skitrak.dead`，還有一個 `amq.gen-...` 暫存 queue（這台 server 的 backplane）。

---

## 步驟 5：Consumer EC2（約 10 分鐘）

1. 啟動一台 **Amazon Linux 2023、t3.small**，套用 `skitrak-sg`。
2. 部署（腳本會自動安裝 Java 17 和 cron、註冊 systemd、設定每分鐘的健康檢查）：
   ```bash
   ./deployment/consumer/deploy-consumer.sh $KEY <CONSUMER_PUBLIC_IP>
   ```
   最後一行應該印出 `{"status":"UP",...}`。
3. 觀察 log（測試時一直開著）：
   ```bash
   ssh -i $KEY ec2-user@<CONSUMER_PUBLIC_IP> 'journalctl -u skitrak-consumer -f'
   ```
   每 5 秒會印一行 `processed=... rate=.../s avgLag=...ms queueDepth=... threads=...`。

---

## 步驟 6：冒煙測試（smoke test）＋排行榜證據（約 5 分鐘）

1. 建置 client：
   ```bash
   (cd client && "$MVN" -q clean package)
   ```
2. 跑一次小量測試（總數至少要大於 warmup 的 32,000）：
   ```bash
   cd client
   java -jar target/client.jar http://<SERVER1_PUBLIC_IP>:8080/ski-servlet 64 50000 smoke
   ```
3. 確認輸出：
   - `Failed swipes: 0`
   - `Subscribe Path` 每個 resort 的 `updates` > 0
   - **`Last Leaderboard Received`** 印出含真實 skierID 和 vertical 的 JSON → 這就是「subscribers 收到真實排行榜」的證據，報告中直接貼這段。
   - consumer log 的 `processed` 增加了 50,000。

---

## 步驟 7：單台效能測試（Part 4，約 30–60 分鐘）

**Client 放哪裡**：A1 是從筆電跑的。但 A2 最後要比較「1 台 vs 4 台」，筆電的上傳頻寬很可能先成為瓶頸，導致 4 台看不出提升。建議另開一台 **c5.xlarge（或 t3.large）** 當 client，和其他機器放同一個 region：
```bash
ssh -i $KEY ec2-user@<CLIENT_IP> 'sudo dnf install -y java-17-amazon-corretto-headless; mkdir -p ~/client'
scp -i $KEY client/target/client.jar ec2-user@<CLIENT_IP>:~/client/
```
client 的 CSV 會寫到「執行目錄的上一層」的 `results/`。所以在 EC2 上要 `cd ~/client` 再執行，結果就會在 `~/results/`，之後再用 `scp` 拿回 `Assignment2/results/`。

**每次測試的固定流程**：
1. 另開一個終端機，啟動 queue 監控（在筆電上跑即可，需要 15672 port）：
   ```bash
   python monitoring/queue_monitor.py <RABBIT_PUBLIC_IP> skitrak <密碼> 5 results/<label>-queue.csv
   ```
2. （可選）每台機器上跑 `monitoring/system_monitor.sh <label>`，收集 CPU、記憶體、網路。或者直接截 EC2 console 的 Monitoring 分頁。
3. 執行 client：
   ```bash
   java -jar client.jar http://<SERVER1_IP>:8080/ski-servlet 256 500000 single-500k | tee single-500k-output.txt
   ```
4. 測試結束後：
   - 停掉 queue monitor，然後執行 `python monitoring/summarize_queue.py results/<label>-queue.csv`，得到 peak/avg depth 與 producer/consumer rate。
   - **截圖**：RabbitMQ 管理介面的 *Overview*（Queued messages 和 Message rates 兩張圖）以及 *Queues* 列表。
   - 把 client 輸出、CSV、截圖都放進 `Assignment2/results/`。

**要跑的組合**：
- **Client threads 掃描**：`64`、`128`、`256`、`512`（label 例如 `single-t64`），挑最快的一組作為後續的固定值。
- **Consumer threads 掃描**：修改 `deployment/consumer/consumer.env`，把 `CONSUMER_MIN_THREADS` 和 `CONSUMER_MAX_THREADS` 都設成 `10`、`20`、`40`、`80`，每次重跑 `deploy-consumer.sh` 再測。**預期 20 以上不會再變快**（設計文件 D10、ARCHITECTURE §5），報告中要用數據說明原因。
- 觀察 consumer log 的 `avgLag`（目標 < 100ms）和 `queueDepth`（目標 < 1000）。如果 queue 深度一直往上爬，或出現鋸齒狀，就把截圖留下來，在報告中說明。

---

## 步驟 8：ALB＋2 台（約 30 分鐘）

1. 用同一個 AMI 再啟動 1 台 server，部署：`./deployment/server/deploy-server.sh $KEY <SERVER2_IP>`。
2. 建立 ALB。用 console 比較直觀，要設定的值都和 `deployment/alb/create-alb.sh` 一致：
   - **Target group**：Instances、HTTP、port 8080、名稱 `skitrak-ingest`。
     Health check path **`/ski-servlet/health`**；Advanced：healthy 2、unhealthy 3、timeout 5、interval 30。
     註冊 2 台 server。
   - 建好後進 target group → **Attributes → Edit**：Stickiness 打勾，類型選 *Load balancer generated cookie*，時長 1 day。
   - **Load balancer**：Application LB、Internet-facing、至少選 2 個 AZ 的 subnet，SG 用 `skitrak-alb-sg`，Listener HTTP:80 → forward 到 `skitrak-ingest`。
   - 建好後進 LB → **Attributes → Edit**：**Idle timeout 120** 秒。
   - （如果你有設定 AWS CLI 的 Learner Lab 憑證，也可以填好 `create-alb.sh` 最上面 4 個變數後直接執行。）
3. 等 target 變成 **healthy**（大約 1 分鐘），然後檢查：
   ```bash
   curl -i http://<ALB_DNS>/ski-servlet/health     # 標頭應出現 Set-Cookie: AWSALB=...（sticky cookie 證據）
   ```
4. 執行測試：
   ```bash
   java -jar client.jar http://<ALB_DNS>/ski-servlet <最佳threads> 500000 alb2-500k | tee alb2-500k-output.txt
   ```
5. **分佈證據**：
   - CloudWatch → Metrics → ApplicationELB → Per AppELB, per TG Metrics → **RequestCountPerTarget**（或 target group 的 Monitoring 分頁）→ 截圖。
   - RabbitMQ 的訊息裡有 `serverId`；兩台的 publish 量也可以看各台 `catalina.out` 的請求數。
   - **WebSocket stickiness 證據**：兩台 server 的 `catalina.out` 都 `grep "WebSocket open"`，可以看到 10 個 resort 的訂閱分散在哪台。client 輸出中 `reconnections` 都是 0，表示連線全程固定在同一台。

---

## 步驟 9：4 台＋1M 壓力測試（約 30 分鐘）

1. 再啟動 2 台，執行 `deploy-server.sh $KEY <IP3> <IP4>`，並在 target group 中註冊。
2. 跑 `alb4-500k`（500,000），接著跑 `alb4-1m`（1,000,000）。
3. 找瓶頸：同時看各機器的 CPU（EC2 Monitoring）、RabbitMQ 的 queue 深度與 publish/ack rate、consumer 的 `rate` 和 `avgLag`。
   - 如果 consumer rate < producer rate、queue 一直漲 → 瓶頸在 consumer（參考 D10 的上限 10）。
   - 如果 RabbitMQ CPU 滿了 → 瓶頸在 broker（persistent 訊息加上同步 confirm，參考 D3、D4）。
   - 如果 server CPU 都不高、吞吐量卻上不去 → 瓶頸在 client 或 confirm 往返。

---

## 步驟 10：故障測試（可選但對評分有幫助，約 15 分鐘）

1. **RabbitMQ 斷線重連**：在 500K 測試進行中執行：
   ```bash
   ssh -i $KEY ec2-user@<RABBIT_PUBLIC_IP> 'sudo docker restart rabbitmq'
   ```
   預期：client 出現一些重試／503，然後恢復；server 的 `catalina.out` 出現 `RabbitMQ connection lost, reconnecting`；consumer 自動恢復；最後 `Failed swipes` 為 0 或極少。consumer log 的 `duplicates` 可能 > 0（重送的訊息被去重）。截圖或擷取 log 存證。
2. **Server 故障轉移（failover）**：測試中停掉一台 server 的 Tomcat（`/opt/tomcat9/bin/shutdown.sh`）。ALB 大約 90 秒（30s × 3 次）後標為 unhealthy，流量會轉到其他台。記錄 client 的錯誤與重試數。
3. **Consumer 自動重啟**：`ssh ... 'sudo pkill -9 java'`（在 consumer 機器上），5 秒後 systemd 應該自動拉起（`journalctl` 可以看到）。

---

## 步驟 11：撰寫與繳交（約 1 小時）

1. **AI_USAGE.md**：第 2、3、4 節是 TODO，**必須用你自己的話寫**。素材可以來自你在測試中發現的問題、你不同意或修改過的設計。匯出這次對話放進 `transcripts/`（作業建議提供）。
2. **PDF 內容**（Canvas）：
   1. Git repo URL
   2. `ARCHITECTURE.md` 的內容（已控制在約 2 頁；如果超過，可以刪掉第 6 節的部分表格列）
   3. 1/2/4 台的 client 輸出、queue 截圖、ALB 分佈截圖、`Last Leaderboard Received` 證據
   4. 效能分析：用一張表比較 throughput、p99 latency、peak/avg queue depth，說明 2 台和 4 台的提升與瓶頸
3. **Git**：依你先前的指示，我還沒建 repo、也沒推送。你決定要放在哪個 repo 後告訴我，我可以幫你 `git init`、整理 commit。推送前先確認 `setenv.sh`／`consumer.env` 裡的密碼已改回佔位值。

---

## 步驟 12：結束後清理（避免一直計費）

刪除 ALB 和 target group（ALB 是按小時計費），然後 stop 或 terminate 所有 EC2。
