package model;

// Queue message format defined by the assignment
public class SwipeMessage {
  private String eventId;
  private String resortID;
  private String dayID;
  private String skierID;
  private String liftID;
  private int vertical;
  private int time;
  private String timestamp;
  private String serverId;

  public SwipeMessage(String eventId, String resortID, String dayID, String skierID, String liftID,
      int vertical, int time, String timestamp, String serverId) {
    this.eventId = eventId;
    this.resortID = resortID;
    this.dayID = dayID;
    this.skierID = skierID;
    this.liftID = liftID;
    this.vertical = vertical;
    this.time = time;
    this.timestamp = timestamp;
    this.serverId = serverId;
  }
}
