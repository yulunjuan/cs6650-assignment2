package client;

public class Config {
  public static final int TOTAL_SWIPES = 500_000;

  public static final int WARMUP_THREADS = 32;
  public static final int WARMUP_SWIPES_PER_THREAD = 1000;
  public static final int DEFAULT_MAIN_THREADS = 200;

  public static final int NUM_RESORTS = 10;
  public static final int SEASON_ID = 2026;
  public static final int DAY_ID = 1;

  public static final int MAX_RETRIES = 5;
  public static final long BASE_BACKOFF_MS = 50;
  public static final int REQUEST_TIMEOUT_SEC = 10;

  public static final int LITTLES_LAW_SAMPLES = 100;

  public static final long DRAIN_WAIT_MS = 10_000;
}