import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// Holds only the WebSocket sessions connected to THIS server. Leaderboards are computed
// by the consumer and arrive through the backplane (BackplaneListener -> broadcast).
@ServerEndpoint("/leaderboard/{resortID}")
public class LeaderboardEndpoint {

  // resortID -> local sessions subscribed to that resort
  private static final Map<String, Set<Session>> resortSubscribers = new ConcurrentHashMap<>();
  // resortID -> latest leaderboard JSON, sent to new subscribers right away
  private static final Map<String, String> latestBoard = new ConcurrentHashMap<>();

  @OnOpen
  public void onOpen(Session session, @PathParam("resortID") String resortID) {
    resortSubscribers.computeIfAbsent(resortID, k -> ConcurrentHashMap.newKeySet()).add(session);
    // Shows which server holds each WebSocket (evidence of distribution and stickiness)
    System.out.println("WebSocket open: resort " + resortID + ", session " + session.getId()
        + " on " + AppLifecycle.SERVER_ID);
    String latest = latestBoard.get(resortID);
    if (latest != null) {
      send(session, latest, resortID);
    }
  }

  @OnClose
  public void onClose(Session session, @PathParam("resortID") String resortID) {
    System.out.println("WebSocket close: resort " + resortID + ", session " + session.getId()
        + " on " + AppLifecycle.SERVER_ID);
    removeSession(session, resortID);
  }

  @OnError
  public void onError(Session session, Throwable t, @PathParam("resortID") String resortID) {
    System.out.println("WebSocket error on session " + session.getId() + ": " + t.getMessage());
    removeSession(session, resortID);
  }

  public static void broadcast(String resortID, String json) {
    latestBoard.put(resortID, json);
    Set<Session> sessions = resortSubscribers.get(resortID);
    if (sessions == null) {
      return;
    }
    for (Session s : sessions) {
      send(s, json, resortID);
    }
  }

  private static void send(Session session, String json, String resortID) {
    if (!session.isOpen()) {
      removeSession(session, resortID);
      return;
    }
    // A session allows one write at a time; lock it so onOpen and broadcast never overlap
    synchronized (session) {
      try {
        session.getBasicRemote().sendText(json);
      } catch (IOException | IllegalStateException e) {
        removeSession(session, resortID);
      }
    }
  }

  private static void removeSession(Session session, String resortID) {
    Optional.ofNullable(resortSubscribers.get(resortID)).ifPresent(s -> s.remove(session));
  }
}
