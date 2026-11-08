package consumer;

// All settings are environment variables (or -D system properties) so tuning runs
// only need a restart, not a rebuild.
public final class Config {
  public static final String RABBIT_HOST = get("RABBIT_HOST", "localhost");
  public static final int RABBIT_PORT = Integer.parseInt(get("RABBIT_PORT", "5672"));
  public static final String RABBIT_USER = get("RABBIT_USER", "guest");
  public static final String RABBIT_PASS = get("RABBIT_PASS", "guest");

  // Thread pool bounds for dynamic scaling; at most one thread per resort queue does work
  public static final int MIN_THREADS = Integer.parseInt(get("CONSUMER_MIN_THREADS", "2"));
  public static final int MAX_THREADS = Integer.parseInt(get("CONSUMER_MAX_THREADS", "10"));
  public static final int PREFETCH = Integer.parseInt(get("PREFETCH", "100"));

  public static final int TOP_N = 10;
  public static final int MAX_PROCESS_ATTEMPTS = 3;
  public static final int HEALTH_PORT = Integer.parseInt(get("HEALTH_PORT", "8081"));
  public static final int MONITOR_INTERVAL_SEC = 5;
  public static final int SCALE_UP_DEPTH = 500;
  public static final int SCALE_DOWN_DEPTH = 50;

  private Config() {}

  private static String get(String key, String defaultValue) {
    String value = System.getProperty(key, System.getenv(key));
    return value != null ? value : defaultValue;
  }
}
