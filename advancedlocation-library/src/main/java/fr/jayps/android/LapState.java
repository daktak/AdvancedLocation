package fr.jayps.android;

public class LapState {
    public final int lapCount;
    public final float lapDistance;
    public final long lapElapsedTime;
    public final long lapTotalElapsedTime;
    public final double lapAscent;
    public final float lapMaxSpeed;
    public final long lapStartTime;
    public final long lastLapElapsedTime;
    public final float lastLapDistance;
    public final long bestLapElapsedTime;
    public final long lapHrSum;
    public final int lapHrCount;
    public final int lapHrMax;
    public final long lapCadSum;
    public final int lapCadCount;
    public final int lapCadMax;
    public final int lapPowerMax;
    public final double lapPowerSum;
    public final long lapPowerElapsedTime;
    public final double totalPowerSum;
    public final long totalPowerElapsedTime;

    public LapState(int lapCount, float lapDistance, long lapElapsedTime,
                    long lapTotalElapsedTime, double lapAscent, float lapMaxSpeed,
                    long lapStartTime, long lastLapElapsedTime, float lastLapDistance,
                    long bestLapElapsedTime) {
        this(lapCount, lapDistance, lapElapsedTime, lapTotalElapsedTime, lapAscent,
                lapMaxSpeed, lapStartTime, lastLapElapsedTime, lastLapDistance,
                bestLapElapsedTime, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public LapState(int lapCount, float lapDistance, long lapElapsedTime,
                    long lapTotalElapsedTime, double lapAscent, float lapMaxSpeed,
                    long lapStartTime, long lastLapElapsedTime, float lastLapDistance,
                    long bestLapElapsedTime, double lapPowerSum, long lapPowerElapsedTime,
                    double totalPowerSum, long totalPowerElapsedTime) {
        this(lapCount, lapDistance, lapElapsedTime, lapTotalElapsedTime, lapAscent,
                lapMaxSpeed, lapStartTime, lastLapElapsedTime, lastLapDistance,
                bestLapElapsedTime, 0, 0, 0, 0, 0, 0, 0, lapPowerSum, lapPowerElapsedTime,
                totalPowerSum, totalPowerElapsedTime);
    }

    public LapState(int lapCount, float lapDistance, long lapElapsedTime,
                    long lapTotalElapsedTime, double lapAscent, float lapMaxSpeed,
                    long lapStartTime, long lastLapElapsedTime, float lastLapDistance,
                    long bestLapElapsedTime, long lapHrSum, int lapHrCount, int lapHrMax,
                    long lapCadSum, int lapCadCount, int lapCadMax, int lapPowerMax,
                    double lapPowerSum, long lapPowerElapsedTime, double totalPowerSum,
                    long totalPowerElapsedTime) {
        this.lapCount = lapCount;
        this.lapDistance = lapDistance;
        this.lapElapsedTime = lapElapsedTime;
        this.lapTotalElapsedTime = lapTotalElapsedTime;
        this.lapAscent = lapAscent;
        this.lapMaxSpeed = lapMaxSpeed;
        this.lapStartTime = lapStartTime;
        this.lastLapElapsedTime = lastLapElapsedTime;
        this.lastLapDistance = lastLapDistance;
        this.bestLapElapsedTime = bestLapElapsedTime;
        this.lapHrSum = lapHrSum;
        this.lapHrCount = lapHrCount;
        this.lapHrMax = lapHrMax;
        this.lapCadSum = lapCadSum;
        this.lapCadCount = lapCadCount;
        this.lapCadMax = lapCadMax;
        this.lapPowerMax = lapPowerMax;
        this.lapPowerSum = lapPowerSum;
        this.lapPowerElapsedTime = lapPowerElapsedTime;
        this.totalPowerSum = totalPowerSum;
        this.totalPowerElapsedTime = totalPowerElapsedTime;
    }
}
