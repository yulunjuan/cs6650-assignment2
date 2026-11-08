package consumer;

import com.google.gson.Gson;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

// Consumes one resort queue. The client library delivers a channel's messages one at a
// time, in order, so each resort's swipes are applied sequentially.
public class SwipeConsumer extends DefaultConsumer {
  private static final Gson gson = new Gson();

  private final Aggregator aggregator;
  private final LoadMonitor monitor;

  public SwipeConsumer(Channel channel, Aggregator aggregator, LoadMonitor monitor) {
    super(channel);
    this.aggregator = aggregator;
    this.monitor = monitor;
  }

  @Override
  public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties props, byte[] body)
      throws IOException {
    long tag = envelope.getDeliveryTag();
    for (int attempt = 1; attempt <= Config.MAX_PROCESS_ATTEMPTS; attempt++) {
      try {
        process(body);
        // Manual ack only after aggregation succeeded: at-least-once delivery
        getChannel().basicAck(tag, false);
        return;
      } catch (Exception e) {
        System.out.println("Processing failed (attempt " + attempt + "): " + e.getMessage());
      }
    }
    // Give up: requeue=false routes the message to the dead-letter queue for inspection
    getChannel().basicReject(tag, false);
  }

  private void process(byte[] body) throws IOException {
    SwipeMessage m = gson.fromJson(new String(body, StandardCharsets.UTF_8), SwipeMessage.class);
    List<LeaderboardMsg.Entry> top = aggregator.apply(m);
    if (top != null) {
      broadcast(m, top);
    }
    monitor.recordLag(System.currentTimeMillis() - Instant.parse(m.timestamp).toEpochMilli());
  }

  // Backplane: publish to the fanout exchange; every server-v2 instance receives it and
  // pushes to its own WebSocket subscribers. Routing key carries the resortID.
  private void broadcast(SwipeMessage m, List<LeaderboardMsg.Entry> top) throws IOException {
    LeaderboardMsg msg = new LeaderboardMsg(m.resortID, m.dayID, Instant.now().toString(), top);
    getChannel().basicPublish(Topology.LEADERBOARD_EXCHANGE, m.resortID, null,
        gson.toJson(msg).getBytes(StandardCharsets.UTF_8));
  }
}
