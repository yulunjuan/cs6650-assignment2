package consumer;

// Same fields as the producer's queue message; filled by Gson
public class SwipeMessage {
  String eventId;
  String resortID;
  String dayID;
  String skierID;
  String liftID;
  int vertical;
  int time;
  String timestamp;
  String serverId;
}
