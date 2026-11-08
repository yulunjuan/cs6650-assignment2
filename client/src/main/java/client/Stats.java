package client;

import java.util.Arrays;

public class Stats {

  public static void print(String label, long[] values) {
    if (values.length == 0) {
      System.out.println(label + ": no data");
      return;
    }
    long[] sorted = values.clone();
    Arrays.sort(sorted);
    long sum = 0;
    for (long v : sorted) {
      sum += v;
    }
    System.out.printf("%s (n=%d): mean=%.2f, median=%d, p95=%d, p99=%d, min=%d, max=%d (ms)%n",
        label, sorted.length, sum / (double) sorted.length,
        percentile(sorted, 50), percentile(sorted, 95), percentile(sorted, 99),
        sorted[0], sorted[sorted.length - 1]);
  }

  // Nearest-rank percentile on a sorted array
  private static long percentile(long[] sorted, double p) {
    int index = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
    return sorted[Math.max(0, index)];
  }
}