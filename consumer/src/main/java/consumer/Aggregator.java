package consumer;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

// In-memory aggregation state shared by all consumer threads
public class Aggregator {
  private final ConcurrentHashMap<String, Long> skierVertical = new ConcurrentHashMap<>();     // resort:day:skier
  private final ConcurrentHashMap<String, TopN> resortLeaderboards = new ConcurrentHashMap<>(); // resort:day
  private final Set<String> processedEventIds = ConcurrentHashMap.newKeySet();
  private final AtomicLong swipesProcessed = new AtomicLong();
  private final AtomicLong duplicatesSkipped = new AtomicLong();

  // Applies one swipe. Returns the new top-N if it changed, otherwise null.
  // All swipes of a resort are applied by one thread at a time (one channel per resort
  // queue), so the check-then-add on eventId cannot race for the same event.
  public List<LeaderboardMsg.Entry> apply(SwipeMessage m) {
    if (processedEventIds.contains(m.eventId)) {
      duplicatesSkipped.incrementAndGet();
      return null;
    }
    String dayKey = m.resortID + ":" + m.dayID;
    long total = skierVertical.merge(dayKey + ":" + m.skierID, (long) m.vertical, Long::sum);
    TopN top = resortLeaderboards.computeIfAbsent(dayKey, k -> new TopN(Config.TOP_N));
    boolean changed = top.update(m.skierID, total);

    // Mark as processed only after the state update succeeded
    processedEventIds.add(m.eventId);
    swipesProcessed.incrementAndGet();
    return changed ? top.snapshot() : null;
  }

  public long getSwipesProcessed() {
    return swipesProcessed.get();
  }

  public long getDuplicatesSkipped() {
    return duplicatesSkipped.get();
  }
}
