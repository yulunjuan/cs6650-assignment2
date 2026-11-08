package client;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;

public class Main {

  // IntelliJ runs from the client folder, so this resolves to Assignment2/results
  private static final Path RESULTS_DIR = Paths.get("..", "results");
  private static final long BUCKET_MS = 10_000;

  public static void main(String[] args) throws Exception {
    String baseUrl = args.length > 0 ? args[0] : "http://localhost:8080/ski-servlet";
    int mainThreads = args.length > 1 ? Integer.parseInt(args[1]) : Config.DEFAULT_MAIN_THREADS;
    int totalSwipes = args.length > 2 ? Integer.parseInt(args[2]) : Config.TOTAL_SWIPES;
    // Prefix for the CSV files so each test run keeps its own results
    String runLabel = args.length > 3 ? args[3] : "run";
    String wsBaseUrl = baseUrl.replaceFirst("^http", "ws");

    // Ingest path: one shared connection pool for all sender threads
    HttpClient ingestClient = newClient();
    // Subscribe path: separate client so push delivery does not compete with ingest I/O
    HttpClient wsClient = newClient();
    Metrics metrics = new Metrics();

    System.out.println("Server: " + baseUrl + ", main phase threads: " + mainThreads
        + ", total swipes: " + totalSwipes + ", run label: " + runLabel);

    double rttMs = measureSingleThreadRtt(ingestClient, baseUrl, Config.LITTLES_LAW_SAMPLES);
    System.out.printf("Single-thread avg RTT: %.2f ms%n", rttMs);
    System.out.printf("Little's Law prediction: warmup (%d threads) = %.0f req/s, main (%d threads) = %.0f req/s%n",
        Config.WARMUP_THREADS, Config.WARMUP_THREADS / (rttMs / 1000.0),
        mainThreads, mainThreads / (rttMs / 1000.0));

    List<LeaderboardSubscriber> subscribers = new ArrayList<>();
    for (int resort = 1; resort <= Config.NUM_RESORTS; resort++) {
      LeaderboardSubscriber sub = new LeaderboardSubscriber(wsClient, wsBaseUrl, resort);
      sub.connect();
      subscribers.add(sub);
    }

    BlockingQueue<SwipeEvent> queue = new LinkedBlockingQueue<>();
    new Thread(new SwipeGenerator(queue, totalSwipes)).start();

    List<SwipeSender> senders = new ArrayList<>();

    long startTime = System.currentTimeMillis();
    senders.addAll(runPhase(Config.WARMUP_THREADS, Config.WARMUP_THREADS * Config.WARMUP_SWIPES_PER_THREAD,
        ingestClient, baseUrl, queue, metrics));
    long warmupEnd = System.currentTimeMillis();

    int remaining = totalSwipes - Config.WARMUP_THREADS * Config.WARMUP_SWIPES_PER_THREAD;
    senders.addAll(runPhase(mainThreads, remaining, ingestClient, baseUrl, queue, metrics));
    long endTime = System.currentTimeMillis();

    // Consumers lag slightly behind ingest; keep subscribers open so they receive the final standings
    Thread.sleep(Config.DRAIN_WAIT_MS);
    for (LeaderboardSubscriber sub : subscribers) {
      sub.close();
    }

    printResults(metrics, subscribers, startTime, warmupEnd, endTime, mainThreads, remaining);

    // Merge per-thread buffers only after all sending is done
    List<RequestRecord> records = new ArrayList<>();
    for (SwipeSender sender : senders) {
      records.addAll(sender.getRecords());
    }
    records.sort(Comparator.comparingLong(r -> r.startMs));

    printAnalysis(records, subscribers, startTime, endTime);
    writeLatencyCsv(records, RESULTS_DIR.resolve(runLabel + "-latency.csv"));
    writeThroughputCsv(records, startTime, endTime, RESULTS_DIR.resolve(runLabel + "-throughput.csv"));
    System.out.println("\nCSV files written to " + RESULTS_DIR.toAbsolutePath().normalize());
  }

  private static HttpClient newClient() {
    return HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(Config.REQUEST_TIMEOUT_SEC))
        .build();
  }

  private static List<SwipeSender> runPhase(int numThreads, int totalSwipes, HttpClient client, String baseUrl,
      BlockingQueue<SwipeEvent> queue, Metrics metrics)
      throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(numThreads);
    List<SwipeSender> senders = new ArrayList<>();
    int base = totalSwipes / numThreads;
    int extra = totalSwipes % numThreads;
    for (int i = 0; i < numThreads; i++) {
      int count = base + (i < extra ? 1 : 0);
      SwipeSender sender = new SwipeSender(client, baseUrl, queue, count, latch, metrics);
      senders.add(sender);
      new Thread(sender).start();
    }
    latch.await();
    return senders;
  }

  private static double measureSingleThreadRtt(HttpClient client, String baseUrl, int samples) throws Exception {
    long totalNanos = 0;
    for (int i = 0; i < samples; i++) {
      HttpRequest request = SwipeSender.buildRequest(baseUrl, SwipeGenerator.randomSwipe());
      long start = System.nanoTime();
      client.send(request, HttpResponse.BodyHandlers.discarding());
      totalNanos += System.nanoTime() - start;
    }
    return totalNanos / (double) samples / 1_000_000.0;
  }

  private static void printResults(Metrics metrics, List<LeaderboardSubscriber> subscribers,
      long startTime, long warmupEnd, long endTime,
      int mainThreads, int mainSwipes) {
    int warmupSwipes = Config.WARMUP_THREADS * Config.WARMUP_SWIPES_PER_THREAD;
    long warmupMs = warmupEnd - startTime;
    long mainMs = endTime - warmupEnd;
    long totalMs = endTime - startTime;

    System.out.println("\n===== Ingest Path =====");
    System.out.println("Successful swipes:   " + metrics.successful.get());
    System.out.println("Failed swipes:       " + metrics.failed.get());
    System.out.println("Retries:             " + metrics.retries.get());
    System.out.println("Connection errors:   " + metrics.connectionErrors.get());
    System.out.printf("Warmup:  %d threads, %d ms, %.0f swipes/s%n",
        Config.WARMUP_THREADS, warmupMs, warmupSwipes * 1000.0 / warmupMs);
    System.out.printf("Main:    %d threads, %d ms, %.0f swipes/s%n",
        mainThreads, mainMs, mainSwipes * 1000.0 / mainMs);
    System.out.printf("Total wall time: %d ms%n", totalMs);
    System.out.printf("Overall throughput: %.0f swipes/s%n",
        metrics.successful.get() * 1000.0 / totalMs);
    System.out.println("HTTP connections: 1 shared HttpClient for ingest (HTTP/1.1 keep-alive pool), "
        + "1 separate HttpClient for WebSockets");

    System.out.println("\n===== Subscribe Path =====");
    for (LeaderboardSubscriber sub : subscribers) {
      System.out.printf("Resort %2d: %d updates, connect %d ms, %d reconnections%n",
          sub.getResortID(), sub.getUpdatesReceived(), sub.getFirstConnectMs(), sub.getReconnections());
    }

    // Evidence that subscribers receive real, swipe-driven standings
    System.out.println("\n===== Last Leaderboard Received =====");
    for (LeaderboardSubscriber sub : subscribers) {
      System.out.printf("Resort %2d: %s%n", sub.getResortID(), sub.getLastMessage());
    }
  }

  private static void printAnalysis(List<RequestRecord> records, List<LeaderboardSubscriber> subscribers,
      long startTime, long endTime) {
    System.out.println("\n===== Performance Analysis =====");

    // Ingest latency over successful requests only
    int successCount = 0;
    for (RequestRecord r : records) {
      if (r.statusCode == 201) {
        successCount++;
      }
    }
    long[] ingestLatencies = new long[successCount];
    int[] perResort = new int[Config.NUM_RESORTS + 1];
    int i = 0;
    for (RequestRecord r : records) {
      if (r.statusCode == 201) {
        ingestLatencies[i++] = r.latencyMs;
        perResort[r.resortID]++;
      }
    }
    Stats.print("Ingest latency", ingestLatencies);

    double totalSec = (endTime - startTime) / 1000.0;
    System.out.println("Throughput per resort:");
    for (int resort = 1; resort <= Config.NUM_RESORTS; resort++) {
      System.out.printf("  Resort %2d: %d swipes, %.0f swipes/s%n",
          resort, perResort[resort], perResort[resort] / totalSec);
    }

    // Push latency = client receive time - serverTimestamp (includes clock skew)
    List<Long> pushList = new ArrayList<>();
    for (LeaderboardSubscriber sub : subscribers) {
      pushList.addAll(sub.getPushLatenciesMs());
    }
    long[] pushLatencies = new long[pushList.size()];
    for (int j = 0; j < pushLatencies.length; j++) {
      pushLatencies[j] = pushList.get(j);
    }
    Stats.print("Push latency", pushLatencies);
    System.out.println("Total leaderboard updates delivered: " + pushLatencies.length);
    System.out.println("Note: push latency includes clock skew between client and server clocks.");
  }

  private static void writeLatencyCsv(List<RequestRecord> records, Path path) throws IOException {
    Files.createDirectories(path.getParent());
    // Single writer after the run: no file contention while sending
    try (BufferedWriter writer = Files.newBufferedWriter(path)) {
      writer.write("timestamp,requestType,latency,statusCode,resortID");
      writer.newLine();
      for (RequestRecord r : records) {
        writer.write(r.toCsvLine());
        writer.newLine();
      }
    }
  }

  private static void writeThroughputCsv(List<RequestRecord> records, long startTime, long endTime, Path path)
      throws IOException {
    int numBuckets = (int) ((endTime - startTime) / BUCKET_MS) + 1;
    int[] counts = new int[numBuckets];
    for (RequestRecord r : records) {
      if (r.statusCode == 201) {
        int bucket = (int) ((r.startMs + r.latencyMs - startTime) / BUCKET_MS);
        if (bucket >= 0 && bucket < numBuckets) {
          counts[bucket]++;
        }
      }
    }
    try (BufferedWriter writer = Files.newBufferedWriter(path)) {
      writer.write("secondsFromStart,swipesPerSecond");
      writer.newLine();
      for (int b = 0; b < numBuckets; b++) {
        writer.write((b * BUCKET_MS / 1000) + "," + (counts[b] * 1000.0 / BUCKET_MS));
        writer.newLine();
      }
    }
  }
}