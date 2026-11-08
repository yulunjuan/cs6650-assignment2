import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import mq.Topology;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;

// Every server binds its own temporary queue to the leaderboard fanout exchange, so each
// leaderboard update reaches all servers; each server then pushes to its local sessions.
public class BackplaneListener {
  private Connection connection;

  public void start(ConnectionFactory factory) throws IOException, TimeoutException {
    // Factory has automatic recovery on: the server-named queue, binding and consumer
    // are re-created by the client library after a dropped connection
    connection = factory.newConnection("server-v2-backplane");
    Channel ch = connection.createChannel();
    ch.exchangeDeclare(Topology.LEADERBOARD_EXCHANGE, "fanout", true);
    String queue = ch.queueDeclare().getQueue();  // exclusive, auto-delete, server-named
    ch.queueBind(queue, Topology.LEADERBOARD_EXCHANGE, "");

    // Auto-ack: a missed snapshot is superseded by the next one, so no redelivery is needed
    ch.basicConsume(queue, true,
        (tag, delivery) -> LeaderboardEndpoint.broadcast(
            delivery.getEnvelope().getRoutingKey(),  // consumer sets routing key = resortID
            new String(delivery.getBody(), StandardCharsets.UTF_8)),
        tag -> { });
  }

  public void close() {
    try {
      if (connection != null && connection.isOpen()) {
        connection.close();
      }
    } catch (IOException e) {
      System.out.println("Error closing backplane connection: " + e.getMessage());
    }
  }
}
