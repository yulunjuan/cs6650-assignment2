package client;

public class SwipeEvent {
  private final int skierID;
  private final int resortID;
  private final int liftID;
  private final int seasonID;
  private final int dayID;
  private final int time;

  public SwipeEvent(int skierID, int resortID, int liftID, int seasonID, int dayID, int time) {
    this.skierID = skierID;
    this.resortID = resortID;
    this.liftID = liftID;
    this.seasonID = seasonID;
    this.dayID = dayID;
    this.time = time;
  }

  public String getPath() {
    return "/skiers/" + resortID + "/seasons/" + seasonID + "/days/" + dayID + "/skiers/" + skierID;
  }

  // Built by hand instead of Gson to keep per-request CPU cost low
  public String getJsonBody() {
    return "{\"time\":" + time + ",\"liftID\":" + liftID + "}";
  }

  public int getResortID() {
    return resortID;
  }
}