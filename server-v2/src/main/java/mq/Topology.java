package mq;

import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

// Queue topology shared by producer and consumer. Kept identical in both projects:
// declares are idempotent, but RabbitMQ rejects a re-declare whose arguments differ.
public final class Topology {
  public static final String SWIPE_EXCHANGE = "skitrak.exchange";
  public static final String DEAD_LETTER_EXCHANGE = "skitrak.dlx";
  public static final String DEAD_LETTER_QUEUE = "skitrak.dead";
  public static final String LEADERBOARD_EXCHANGE = "leaderboard.fanout";

  public static final int NUM_RESORTS = 10;
  public static final int MESSAGE_TTL_MS = 3_600_000;
  public static final int MAX_QUEUE_LENGTH = 100_000;

  private Topology() {}

  public static String routingKey(String resortID) {
    return "resort." + resortID;
  }

  public static String queueName(int resortID) {
    return "resort." + resortID;
  }

  public static void declare(Channel ch) throws IOException {
    ch.exchangeDeclare(SWIPE_EXCHANGE, BuiltinExchangeType.TOPIC, true);
    ch.exchangeDeclare(DEAD_LETTER_EXCHANGE, BuiltinExchangeType.FANOUT, true);
    ch.exchangeDeclare(LEADERBOARD_EXCHANGE, BuiltinExchangeType.FANOUT, true);

    ch.queueDeclare(DEAD_LETTER_QUEUE, true, false, false, null);
    ch.queueBind(DEAD_LETTER_QUEUE, DEAD_LETTER_EXCHANGE, "");

    Map<String, Object> args = new HashMap<>();
    args.put("x-message-ttl", MESSAGE_TTL_MS);
    args.put("x-max-length", MAX_QUEUE_LENGTH);
    // When full, nack the publisher instead of silently dropping the oldest message
    args.put("x-overflow", "reject-publish");
    args.put("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE);

    for (int r = 1; r <= NUM_RESORTS; r++) {
      ch.queueDeclare(queueName(r), true, false, false, args);
      ch.queueBind(queueName(r), SWIPE_EXCHANGE, routingKey(String.valueOf(r)));
    }
  }
}
