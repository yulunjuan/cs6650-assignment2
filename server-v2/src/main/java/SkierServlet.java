import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import model.LiftRide;
import model.ResponseMsg;
import model.SwipeMessage;
import mq.SwipePublisher;
import mq.Topology;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

public class SkierServlet extends HttpServlet {
  private final Gson gson = new Gson();
  private SwipePublisher publisher;

  @Override
  public void init() throws ServletException {
    // Created by AppLifecycle before any servlet is initialized
    publisher = (SwipePublisher) getServletContext().getAttribute(AppLifecycle.PUBLISHER_ATTR);
    if (publisher == null) {
      throw new ServletException("SwipePublisher not initialized");
    }
  }

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse res)
      throws ServletException, IOException {

    res.setContentType("application/json");
    res.setCharacterEncoding("UTF-8");

    String urlPath = req.getPathInfo();

    // Check we have a URL
    if (urlPath == null || urlPath.isEmpty()) {
      res.setStatus(HttpServletResponse.SC_NOT_FOUND);
      res.getWriter().write(gson.toJson(new ResponseMsg("missing parameters")));
      return;
    }

    String[] urlParts = urlPath.split("/");

    if (!isUrlValid(urlParts)) {
      res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
      res.getWriter().write(gson.toJson(new ResponseMsg("invalid url")));
      return;
    }

    LiftRide liftRide;
    try {
      liftRide = gson.fromJson(readBody(req), LiftRide.class);
    } catch (JsonSyntaxException e) {
      res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
      res.getWriter().write(gson.toJson(new ResponseMsg("invalid JSON format")));
      return;
    }

    if (liftRide == null || !isLiftRideValid(liftRide)) {
      res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
      res.getWriter().write(gson.toJson(new ResponseMsg("invalid JSON body")));
      return;
    }

    // Extract URL parameters
    String resortID = urlParts[1];
    String dayID = urlParts[5];
    String skierID = urlParts[7];

    SwipeMessage msg = new SwipeMessage(
        UUID.randomUUID().toString(), resortID, dayID, skierID,
        String.valueOf(liftRide.getLiftID()), LiftVerticals.of(liftRide.getLiftID()),
        liftRide.getTime(), Instant.now().toString(), AppLifecycle.SERVER_ID);

    try {
      publisher.publish(resortID, gson.toJson(msg));
    } catch (Exception e) {
      // 5xx tells the client the swipe was not stored and is safe to retry
      res.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
      res.getWriter().write(gson.toJson(new ResponseMsg("queue unavailable")));
      return;
    }

    res.setStatus(HttpServletResponse.SC_CREATED);
    res.getWriter().write(gson.toJson(new ResponseMsg("Lift ride recorded")));
  }

  private String readBody(HttpServletRequest req) throws IOException {
    StringBuilder jsonBody = new StringBuilder();
    BufferedReader reader = req.getReader();
    String line;
    while ((line = reader.readLine()) != null) {
      jsonBody.append(line);
    }
    return jsonBody.toString();
  }

  private boolean isUrlValid(String[] urlParts) {
    // Expected URL: /skiers/{resortID}/seasons/{seasonID}/days/{dayID}/skiers/{skierID}
    // urlParts = ["", "1", "seasons", "2019", "days", "1", "skiers", "123"]

    if (urlParts.length != 8) {
      return false;
    }

    // Check structure
    if (!urlParts[2].equals("seasons") ||
        !urlParts[4].equals("days") ||
        !urlParts[6].equals("skiers")) {
      return false;
    }
    // Validate numeric parameters: must be positive integers
    int[] idPositions = {1, 3, 5, 7};  // resortID, seasonID, dayID, skierID
    for (int pos : idPositions) {
      try {
        if (Integer.parseInt(urlParts[pos]) <= 0) {
          return false;
        }
      } catch (NumberFormatException e) {
        return false;
      }
    }
    // Only resorts 1..10 have a queue; anything else would be unroutable and silently lost
    return Integer.parseInt(urlParts[1]) <= Topology.NUM_RESORTS;
  }

  private boolean isLiftRideValid(LiftRide liftRide) {
    return liftRide.getTime() >= 1 && liftRide.getTime() <= 360 &&
        liftRide.getLiftID() >= 1 && liftRide.getLiftID() <= 40;
  }
}
