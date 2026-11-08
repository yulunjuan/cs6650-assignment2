import com.google.gson.Gson;
import model.ResponseMsg;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

public class HealthServlet extends HttpServlet {
  private final Gson gson = new Gson();

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
    // Canned status; used as the load balancer health check in Assignment 2
    res.setContentType("application/json");
    res.setCharacterEncoding("UTF-8");
    res.setStatus(HttpServletResponse.SC_OK);
    res.getWriter().write(gson.toJson(new ResponseMsg("OK")));
  }
}