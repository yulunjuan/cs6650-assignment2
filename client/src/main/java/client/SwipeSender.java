package client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;

public class SwipeSender implements Runnable {
  private final HttpClient client;
  private final String baseUrl;
  private final BlockingQueue<SwipeEvent> queue;
  private final int count;
  private final CountDownLatch latch;
  private final Metrics metrics;

  // Per-thread buffer: no locking needed while sending; merged by main thread at the end
  private final List<RequestRecord> records = new ArrayList<>();

  public SwipeSender(HttpClient client, String baseUrl, BlockingQueue<SwipeEvent> queue,
      int count, CountDownLatch latch, Metrics metrics) {
    this.client = client;
    this.baseUrl = baseUrl;
    this.queue = queue;
    this.count = count;
    this.latch = latch;
    this.metrics = metrics;
  }

  @Override
  public void run() {
    try {
      for (int i = 0; i < count; i++) {
        SwipeEvent event = queue.take();
        if (sendWithRetry(event)) {
          metrics.successful.incrementAndGet();
        } else {
          metrics.failed.incrementAndGet();
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      latch.countDown();
    }
  }

  private boolean sendWithRetry(SwipeEvent event) throws InterruptedException {
    HttpRequest request = buildRequest(baseUrl, event);
    for (int attempt = 0; attempt <= Config.MAX_RETRIES; attempt++) {
      if (attempt > 0) {
        metrics.retries.incrementAndGet();
        Thread.sleep(Config.BASE_BACKOFF_MS * (1L << (attempt - 1)));
      }
      long start = System.currentTimeMillis();
      try {
        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
        int status = response.statusCode();
        records.add(new RequestRecord(start, System.currentTimeMillis() - start, status, event.getResortID()));
        if (status == 201) {
          return true;
        }
        if (status >= 400 && status < 500) {
          return false;
        }
      } catch (IOException e) {
        // Status 0 marks a network-level failure with no HTTP response
        records.add(new RequestRecord(start, System.currentTimeMillis() - start, 0, event.getResortID()));
        metrics.connectionErrors.incrementAndGet();
      }
    }
    return false;
  }

  public List<RequestRecord> getRecords() {
    return records;
  }

  public static HttpRequest buildRequest(String baseUrl, SwipeEvent event) {
    return HttpRequest.newBuilder(URI.create(baseUrl + event.getPath()))
        .header("Content-Type", "application/json")
        .timeout(Duration.ofSeconds(Config.REQUEST_TIMEOUT_SEC))
        .POST(HttpRequest.BodyPublishers.ofString(event.getJsonBody()))
        .build();
  }
}