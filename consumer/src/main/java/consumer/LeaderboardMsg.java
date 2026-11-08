package consumer;

import java.util.List;

// Same shape as the Assignment 1 leaderboard message, so the existing client parses it
public class LeaderboardMsg {
  public static class Entry {
    private final int rank;
    private final String skierID;
    private final long vertical;

    public Entry(int rank, String skierID, long vertical) {
      this.rank = rank;
      this.skierID = skierID;
      this.vertical = vertical;
    }
  }

  private final String resortID;
  private final String dayID;
  private final String serverTimestamp;
  private final List<Entry> top;

  public LeaderboardMsg(String resortID, String dayID, String serverTimestamp, List<Entry> top) {
    this.resortID = resortID;
    this.dayID = dayID;
    this.serverTimestamp = serverTimestamp;
    this.top = top;
  }
}
