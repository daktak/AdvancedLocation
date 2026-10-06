package fr.jayps.android;

/**
 * Energy expenditure estimation for cycling.
 *
 * <p>The estimator degrades through four tiers, picking the most accurate one the rider profile
 * supports:
 *
 * <ol>
 *   <li>{@link #TIER_MIFFLIN_KEYTEL} - the resting metabolic floor (Mifflin-St Jeor) blended into a
 *       heart rate regression (Keytel et al. 2005), blended between the measured resting heart rate
 *       and 60% of max heart rate. Requires age, sex, body weight and resting heart rate.
 *   <li>{@link #TIER_KEYTEL} - Keytel et al. 2005 heart rate regression on its own. Requires age,
 *       sex and body weight.
 *   <li>{@link #TIER_METS} - speed based METs from the Adult Compendium of Physical Activities.
 *       Requires body weight; does not use heart rate at all.
 *   <li>{@link #TIER_NONE} - no usable profile, estimation is disabled.
 * </ol>
 *
 * <p>Tier 1 follows the Mifflin-Keytel construction: below the resting heart rate the body is
 * spending basal energy, above 60% of HRmax the heart rate regression is trusted, and in between
 * the two are interpolated. That keeps the resting heart rate meaningful as a floor rather than
 * letting the regression extrapolate down into resting metabolism.
 *
 * <p>Profile fields mirror stored preferences, where {@code 0} means "not set". No Android
 * dependency, so the math is usable from plain JVM tests.
 */
public final class EnergyModel {

    /** Nothing to estimate from. */
    public static final int TIER_NONE = 0;
    /** Speed based METs, body weight only. */
    public static final int TIER_METS = 1;
    /** Keytel (2005): age, sex, weight. */
    public static final int TIER_KEYTEL = 2;
    /** Mifflin-St Jeor + Keytel (2005): adds height and resting heart rate. */
    public static final int TIER_MIFFLIN_KEYTEL = 3;

    /** Kilojoules per kilocalorie. */
    private static final double KJ_PER_KCAL = 4.184;
    private static final double MINUTES_PER_DAY = 1440.0;
    /** Oxygen cost of a MET, ml/kg/min. */
    private static final double MET_ML_KG_MIN = 3.5;
    /** TCX declares {@code Calories} as xsd:unsignedShort. */
    public static final int MAX_CALORIES = 65535;
    /** Keytel's equation is a fit below this fraction of HRmax. */
    private static final double SUBMAXIMAL_HR_FRACTION = 0.9;
    /** Fraction of HRmax where Tier 1 stops blending in the resting floor. */
    private static final double BLEND_HR_FRACTION = 0.6;

    private static final int DEFAULT_AGE = 30;
    private static final int DEFAULT_WEIGHT_KG = 75;
    private static final int MIN_AGE = 10;
    private static final int MAX_AGE = 100;
    private static final int MIN_WEIGHT_KG = 25;
    private static final int MAX_WEIGHT_KG = 200;
    private static final int MIN_HR = 25;
    private static final int MAX_HR = 240;
    /** Longest gap between two samples that still counts towards the total. */
    private static final double MAX_INTERVAL_MINUTES = 60.0;
    /** Upper sanity bound for any single estimate, kcal/min. */
    private static final double MAX_KCAL_PER_MINUTE = 40.0;

    private EnergyModel() {
    }

    /** Immutable rider profile; every field is normalised on construction. */
    public static final class Profile {
        public final int ageYears;
        public final boolean female;
        public final int weightKg;
        public final int heightCm;
        public final int restingHr;
        public final int maxHr;

        public Profile(int ageYears, boolean female, int weightKg, int heightCm, int restingHr,
                       int maxHr) {
            this.ageYears = normalise(ageYears, MIN_AGE, MAX_AGE);
            this.weightKg = normalise(weightKg, MIN_WEIGHT_KG, MAX_WEIGHT_KG);
            this.heightCm = normalise(heightCm, 100, 230);
            this.restingHr = normalise(restingHr, MIN_HR, MAX_HR);
            this.maxHr = normalise(maxHr, MIN_HR, MAX_HR);
            this.female = female;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Profile)) {
                return false;
            }
            Profile other = (Profile) o;
            return female == other.female && ageYears == other.ageYears
                    && weightKg == other.weightKg && heightCm == other.heightCm
                    && restingHr == other.restingHr && maxHr == other.maxHr;
        }

        @Override
        public int hashCode() {
            int result = ageYears;
            result = 31 * result + (female ? 1 : 0);
            result = 31 * result + weightKg;
            result = 31 * result + heightCm;
            result = 31 * result + restingHr;
            result = 31 * result + maxHr;
            return result;
        }

        /** {@code <= 0} means unset; anything below {@code min} is raised to it. */
        private static int normalise(int value, int min, int max) {
            if (value <= 0) {
                return 0;
            }
            return Math.min(Math.max(value, min), max);
        }
    }

    /**
     * Selects the most accurate estimator the profile supports.
     *
     * <p>Weight is the last-resort input, so Tier 3 stays available as long as any of age, sex or
     * weight is known: weight alone gives METs, and age plus sex on top of that upgrades to the
     * heart rate based tiers.
     */
    public static int tierFor(Profile p) {
        if (p == null) {
            return TIER_NONE;
        }
        boolean hasWeight = p.weightKg > 0;
        boolean hasAgeAndSex = p.ageYears > 0;
        if (hasWeight && hasAgeAndSex) {
            // Tier 1 additionally needs a resting heart rate strictly below the blend point,
            // otherwise the resting floor and the exercise model would cross over.
            if (p.restingHr > 0 && p.restingHr < blendPointHr(p)) {
                return TIER_MIFFLIN_KEYTEL;
            }
            return TIER_KEYTEL;
        }
        if (hasWeight || hasAgeAndSex || p.restingHr > 0) {
            return TIER_METS;
        }
        return TIER_NONE;
    }

    /** Effective max heart rate: the measured value when sane, Tanaka (2001) otherwise. */
    public static int effectiveMaxHr(Profile p) {
        if (p.maxHr > 0) {
            return p.maxHr;
        }
        if (p.ageYears > 0) {
            return (int) Math.min(Math.max(Math.round(208.0 - 0.7 * p.ageYears), MIN_HR), MAX_HR);
        }
        return 0;
    }

    /** Heart rate at which Tier 1 stops blending and uses the exercise model alone. */
    public static double blendPointHr(Profile p) {
        return BLEND_HR_FRACTION * effectiveMaxHr(p);
    }

    /** Mifflin-St Jeor basal metabolic rate in kcal/day. */
    public static double basalMetabolicRateKcalPerDay(Profile p) {
        double bmr = 10.0 * p.weightKg + 6.25 * p.heightCm - 5.0 * effectiveAge(p);
        return p.female ? bmr - 161.0 : bmr + 5.0;
    }

    private static int effectiveAge(Profile p) {
        return p.ageYears > 0 ? p.ageYears : DEFAULT_AGE;
    }

    /**
     * Keytel et al. (2005) sex specific energy expenditure regression, kcal/min. Gross (not net) of
     * resting metabolism, which is why Tier 1 blends it with a basal floor rather than subtracting.
     */
    public static double keytelKcalPerMinute(Profile p, double hr) {
        double w = p.weightKg;
        double a = effectiveAge(p);
        double kj;
        if (p.female) {
            kj = 0.4472 * hr - 0.1263 * w + 0.074 * a - 20.4022;
        } else {
            kj = 0.6309 * hr + 0.1988 * w + 0.2017 * a - 55.0969;
        }
        return kj / KJ_PER_KCAL;
    }

    /** Adult Compendium cycling METs from ground speed. */
    public static double metsFromSpeedKmh(double speedKmh) {
        if (speedKmh <= 0) {
            return 0;
        }
        if (speedKmh < 16) {
            return 3.5;
        }
        if (speedKmh < 19) {
            return 6.8;
        }
        return 8.0;
    }

    /** MET based estimate in kcal/min; falls back to a nominal weight when unset. */
    public static double metsKcalPerMinute(Profile p, double speedKmh) {
        int weight = p.weightKg > 0 ? p.weightKg : DEFAULT_WEIGHT_KG;
        return metsFromSpeedKmh(speedKmh) * MET_ML_KG_MIN * weight / 200.0;
    }

    /**
     * Energy expenditure for one interval, in kcal/min.
     *
     * @param tier     one of the {@code TIER_*} constants from {@link #tierFor}
     * @param p        normalised rider profile
     * @param hr       heart rate in bpm, or {@code <= 0} when no strap data is available
     * @param speedKmh ground speed in km/h, or {@code <= 0} when unavailable
     */
    public static double kcalPerMinute(int tier, Profile p, int hr, double speedKmh) {
        if (tier == TIER_NONE || p == null) {
            return 0;
        }
        if (hr > 0) {
            double perMinute = tier == TIER_MIFFLIN_KEYTEL
                    ? mifflinKeytelKcalPerMinute(p, hr)
                    : keytelKcalPerMinute(p, hr);
            // A dropped strap sample must not zero out the interval; fall back to METs.
            if (perMinute > 0) {
                return Math.min(perMinute, MAX_KCAL_PER_MINUTE);
            }
        }
        return metsKcalPerMinute(p, speedKmh);
    }

    /** Tier 1: resting metabolic floor blended into the heart rate regression. */
    public static double mifflinKeytelKcalPerMinute(Profile p, double hr) {
        double rest = p.restingHr;
        double maxHr = effectiveMaxHr(p);
        if (rest <= 0 || maxHr <= 0) {
            return keytelKcalPerMinute(p, hr);
        }
        double restKcal = basalMetabolicRateKcalPerDay(p) / MINUTES_PER_DAY;
        double blendHr = blendPointHr(p);
        if (blendHr <= rest || hr <= rest) {
            return restKcal;
        }
        if (hr >= blendHr) {
            return boundedExerciseKcal(p, hr, rest, restKcal, maxHr);
        }
        double exerciseKcal = boundedExerciseKcal(p, blendHr, rest, restKcal, maxHr);
        double wExercise = (hr - rest) / (blendHr - rest);
        return (1.0 - wExercise) * restKcal + wExercise * exerciseKcal;
    }

    /**
     * Keytel's equation is a regression fit valid below HRmax and diverges above it. Since the app
     * has no measured VO2max, linear extrapolation from the 90% HRmax anchor is used instead of
     * letting near-maximal heart rates produce absurd numbers.
     */
    private static double boundedExerciseKcal(Profile p, double hr, double rest, double restKcal,
                                              double maxHr) {
        double anchorHr = SUBMAXIMAL_HR_FRACTION * maxHr;
        if (anchorHr <= rest) {
            return restKcal;
        }
        double anchorKcal = Math.max(keytelKcalPerMinute(p, anchorHr), restKcal);
        if (hr <= anchorHr) {
            return Math.max(keytelKcalPerMinute(p, hr), restKcal);
        }
        double slope = (anchorKcal - restKcal) / (anchorHr - rest);
        return Math.max(restKcal, anchorKcal + slope * (hr - anchorHr));
    }

    /**
     * Energy expenditure for the interval between two samples, in kcal.
     *
     * <p>A non-positive {@code prevTimeMs} or non-increasing timestamps contribute nothing, and the
     * interval is capped at {@link #MAX_INTERVAL_MINUTES} so recording gaps and the 12h/2h track
     * splits in the exporters cannot inflate the total.
     *
     * @param speedKmh ground speed over the interval, in km/h, or {@code <= 0} when unavailable
     */
    public static int intervalCalories(int tier, Profile p, long prevTimeMs, long timeMs, int hr,
                                       double speedKmh) {
        if (tier == TIER_NONE || p == null) {
            return 0;
        }
        // A non-positive previous time means "no previous sample", not "since the epoch".
        if (prevTimeMs <= 0) {
            return 0;
        }
        long dtMs = timeMs - prevTimeMs;
        if (dtMs <= 0) {
            return 0;
        }
        double minutes = Math.min(dtMs / 60000.0, MAX_INTERVAL_MINUTES);
        return (int) Math.max(0, Math.round(kcalPerMinute(tier, p, hr, speedKmh) * minutes));
    }

    /**
     * Sum the energy expenditure over a recorded track.
     *
     * @param timesMs   ascending track timestamps in ms
     * @param hrs       heart rates in bpm, {@code <= 0} when unavailable, may be null
     * @param speedsKmh ground speeds in km/h, {@code <= 0} when unavailable, may be null
     */
    public static int totalCalories(int tier, Profile p, long[] timesMs, int[] hrs,
                                    double[] speedsKmh) {
        if (tier == TIER_NONE || p == null || timesMs == null || timesMs.length < 2) {
            return 0;
        }
        int n = timesMs.length;
        if (hrs != null) {
            n = Math.min(n, hrs.length);
        }
        if (speedsKmh != null) {
            n = Math.min(n, speedsKmh.length);
        }
        double total = 0;
        for (int i = 1; i < n; i++) {
            total += intervalCalories(tier, p, timesMs[i - 1], timesMs[i],
                    hrs == null ? -1 : hrs[i], speedsKmh == null ? -1 : speedsKmh[i]);
        }
        return (int) Math.min(MAX_CALORIES, Math.max(0, Math.round(total)));
    }
}
