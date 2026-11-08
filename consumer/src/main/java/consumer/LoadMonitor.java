package consumer;

import com.rabbitmq.client.Channel;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

// Runs every few seconds: logs throughput, consumer lag and queue depth, and resizes the
// consumer thread pool based on queue depth (dynamic scaling).
public class LoadMonitor implements Runnable {
  private final Channel monitorChannel;
  private final ThreadPoolExecutor workers;
  private final Aggregator aggregator;

  private final AtomicLong lagSumMs = new AtomicLong();
  private final AtomicLong lagCount = new AtomicLong();
  private long lastProcessed;
  private volatile long lastDepth;

  public LoadMonitor(Channel monitorChannel, ThreadPoolExecutor workers, Aggregator aggregator) {
    this.monitorChannel = monitorChannel;
    this.workers = workers;
    this.aggregator = aggregator;
  }

  // Lag = consume time - publish time on the server (assumes NTP-synced EC2 clocks)
  public void recordLag(long lagMs) {
    lagSumMs.addAndGet(lagMs);
    lagCount.incrementAndGet();
  }

  @Override
  public void run() {
    // An uncaught exception would cancel all future runs of this scheduled task
    try {
      long depth = 0;
      for (int r = 1; r <= Topology.NUM_RESORTS; r++) {
        depth += monitorChannel.messageCount(Topology.queueName(r));
      }
      lastDepth = depth;
      scale(depth);

      long processed = aggregator.getSwipesProcessed();
      long count = lagCount.getAndSet(0);
      long lagSum = lagSumMs.getAndSet(0);
      System.out.printf("processed=%d rate=%.0f/s avgLag=%dms queueDepth=%d threads=%d duplicates=%d%n",
          processed, (processed - lastProcessed) / (double) Config.MONITOR_INTERVAL_SEC,
          count == 0 ? 0 : lagSum / count, depth, workers.getMaximumPoolSize(),
          aggregator.getDuplicatesSkipped());
      lastProcessed = processed;
    } catch (Exception e) {
      System.out.println("Monitor failed: " + e.getMessage());
    }
  }

  private void scale(long depth) {
    int current = workers.getMaximumPoolSize();
    if (depth > Config.SCALE_UP_DEPTH && current < Config.MAX_THREADS) {
      int next = Math.min(current * 2, Config.MAX_THREADS);
      // Raise max before core: core may never exceed max
      workers.setMaximumPoolSize(next);
      workers.setCorePoolSize(next);
      System.out.println("Scaling up consumer threads to " + next);
    } else if (depth < Config.SCALE_DOWN_DEPTH && current > Config.MIN_THREADS) {
      int next = Math.max(current / 2, Config.MIN_THREADS);
      workers.setCorePoolSize(next);
      workers.setMaximumPoolSize(next);
      System.out.println("Scaling down consumer threads to " + next);
    }
  }

  public long getLastDepth() {
    return lastDepth;
  }
}
