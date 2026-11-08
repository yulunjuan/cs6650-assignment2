package consumer;

import com.rabbitmq.client.Connection;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

// GET /health -> 200 while connected to RabbitMQ, 503 otherwise (used by the restart script)
public class HealthServer {
  private HttpServer server;

  public void start(Connection connection, Aggregator aggregator, LoadMonitor monitor) throws IOException {
    server = HttpServer.create(new InetSocketAddress(Config.HEALTH_PORT), 0);
    server.createContext("/health", exchange -> {
      boolean up = connection.isOpen();
      String body = String.format("{\"status\":\"%s\",\"swipesProcessed\":%d,\"queueDepth\":%d}",
          up ? "UP" : "DOWN", aggregator.getSwipesProcessed(), monitor.getLastDepth());
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(up ? 200 : 503, bytes.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(bytes);
      }
    });
    server.start();
  }

  public void stop() {
    if (server != null) {
      server.stop(0);
    }
  }
}
