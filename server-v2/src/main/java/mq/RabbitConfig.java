package mq;

import com.rabbitmq.client.ConnectionFactory;

// Settings come from environment variables (or -D system properties) so the same WAR
// runs locally and on every EC2 instance without rebuilding.
public final class RabbitConfig {
  private RabbitConfig() {}

  public static String get(String key, String defaultValue) {
    String value = System.getProperty(key, System.getenv(key));
    return value != null ? value : defaultValue;
  }

  public static ConnectionFactory newFactory(boolean automaticRecovery) {
    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost(get("RABBIT_HOST", "localhost"));
    factory.setPort(Integer.parseInt(get("RABBIT_PORT", "5672")));
    factory.setUsername(get("RABBIT_USER", "guest"));
    factory.setPassword(get("RABBIT_PASS", "guest"));
    factory.setConnectionTimeout(5_000);
    factory.setAutomaticRecoveryEnabled(automaticRecovery);
    return factory;
  }
}
