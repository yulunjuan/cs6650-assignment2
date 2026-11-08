package consumer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// Top-N skiers of one (resort, day) by total vertical. Totals only grow, so a skier
// outside the list enters only by beating the current last place.
public class TopN {
  private static class Entry {
    final String skierID;
    long vertical;

    Entry(String skierID, long vertical) {
      this.skierID = skierID;
      this.vertical = vertical;
    }
  }

  private final int capacity;
  private final List<Entry> entries = new ArrayList<>();

  public TopN(int capacity) {
    this.capacity = capacity;
  }

  // Returns true if the standings changed
  public synchronized boolean update(String skierID, long total) {
    for (Entry e : entries) {
      if (e.skierID.equals(skierID)) {
        e.vertical = total;
        sort();
        return true;
      }
    }
    if (entries.size() < capacity) {
      entries.add(new Entry(skierID, total));
      sort();
      return true;
    }
    Entry last = entries.get(entries.size() - 1);
    if (total <= last.vertical) {
      return false;
    }
    entries.set(entries.size() - 1, new Entry(skierID, total));
    sort();
    return true;
  }

  public synchronized List<LeaderboardMsg.Entry> snapshot() {
    List<LeaderboardMsg.Entry> result = new ArrayList<>();
    for (int i = 0; i < entries.size(); i++) {
      Entry e = entries.get(i);
      result.add(new LeaderboardMsg.Entry(i + 1, e.skierID, e.vertical));
    }
    return result;
  }

  private void sort() {
    entries.sort(Comparator.comparingLong((Entry e) -> e.vertical).reversed());
  }
}
