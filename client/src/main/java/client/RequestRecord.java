package client;

public class RequestRecord {
  public final long startMs;
  public final long latencyMs;
  public final int statusCode;
  public final int resortID;

  public RequestRecord(long startMs, long latencyMs, int statusCode, int resortID) {
    this.startMs = startMs;
    this.latencyMs = latencyMs;
    this.statusCode = statusCode;
    this.resortID = resortID;
  }

  public String toCsvLine() {
    return startMs + ",SWIPE," + latencyMs + "," + statusCode + "," + resortID;
  }
}