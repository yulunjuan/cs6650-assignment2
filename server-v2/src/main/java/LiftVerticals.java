// Fixed vertical drop (feet) per lift, liftID 1..40, all in the 60-400 ft range.
// The spec says this table is in the starter repo, but the course repo (as of 2026-11-05)
// does not contain one, so lifts are spread evenly over 60-400 ft. Replace if one is published.
public final class LiftVerticals {
  private static final int[] VERTICAL_FT = {
      60, 69, 77, 86, 95, 104, 112, 121, 130, 138,
      147, 156, 165, 173, 182, 191, 199, 208, 217, 226,
      234, 243, 252, 261, 269, 278, 287, 295, 304, 313,
      322, 330, 339, 348, 356, 365, 374, 383, 391, 400
  };

  private LiftVerticals() {}

  public static int of(int liftID) {
    return VERTICAL_FT[liftID - 1];
  }
}
