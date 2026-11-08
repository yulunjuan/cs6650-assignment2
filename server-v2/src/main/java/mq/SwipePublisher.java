package mq;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.MessageProperties;

import java.nio.charset.StandardCharsets;

public class SwipePublisher {
  private static final long CONFIRM_TIMEOUT_MS = 5_000;

  private final ChannelPool pool;

  public SwipePublisher(ChannelPool pool) {
    this.pool = pool;
  }

  // Returns only after the broker has confirmed the message, so a 201 means the swipe is stored
  public void publish(String resortID, String json) throws Exception {
    Channel ch = pool.borrowChannel();
    try {
      ch.basicPublish(Topology.SWIPE_EXCHANGE, Topology.routingKey(resortID),
          MessageProperties.PERSISTENT_TEXT_PLAIN, json.getBytes(StandardCharsets.UTF_8));
      // Throws (and closes the channel) on nack or timeout; the pool replaces it later
      ch.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MS);
    } finally {
      pool.returnChannel(ch);
    }
  }
}
