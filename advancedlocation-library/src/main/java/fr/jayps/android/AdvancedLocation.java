
package fr.jayps.android;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.location.Location;
import android.util.Log;
import android.content.Context;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class AdvancedLocation {
    private static final String TAG = "AdvancedLocation";

    // Thread-safety: ReadWriteLock for database operations
    private final ReadWriteLock dbLock = new ReentrantReadWriteLock();
    private final Lock readLock = dbLock.readLock();
    private final Lock writeLock = dbLock.writeLock();

    protected class LocationWithExtraFields extends Location {
        public float distance = 0; // in m

        // altitude2, a 2nd altitude, provided by a pressure sensor for example
        private double _altitude2 = 0;
        private boolean _hasAltitude2 = false;
        private long _altitude2CalibrationTime = 0;
        private double _altitude2CalibrationDelta = 0;

        public LocationWithExtraFields(Location l) {
            super(l);
            this.distance = _distance;
            this._altitude2 = altitude2;
            this._hasAltitude2 = hasAltitude2;
            this._altitude2CalibrationTime = altitude2CalibrationTime;
            this._altitude2CalibrationDelta = altitude2CalibrationDelta;
        }

        public double getAltitude() {
            if (this._hasAltitude2 && this._altitude2CalibrationTime > 0) {
                return this._altitude2 + this._altitude2CalibrationDelta;
            }
            return super.getAltitude();
        }
        public double getAltitudeFromGps() {
            return super.getAltitude();
        }
        public float getAltitudeAccuracy() {
            if (this._hasAltitude2 && this._altitude2CalibrationTime > 0) {
                // obtained from a pressure sensor, and calibration already done
                return 1; // should be below _minAccuracyForAltitudeChangeLevel1
            }
            return super.getAccuracy();
        }
    }

    protected LocationWithExtraFields currentLocation = null;         // current location
    protected LocationWithExtraFields lastLocation = null;            // last received location
    protected LocationWithExtraFields lastGoodLocation = null;        // last location with accuracy below _minAccuracy
    protected LocationWithExtraFields lastGoodAscentLocation = null;  // last location with changed ascent
    protected LocationWithExtraFields lastGoodAscentLocation2 = null; // other previous location with changed ascent, older and with better accuracy than lastGoodAscentLocation
    protected LocationWithExtraFields lastGoodAscentRateLocation = null;  // last location with changed ascentRate
    protected LocationWithExtraFields lastSavedLocation = null;       // last saved location


    // altitude2, a 2nd altitude, provided by a pressure sensor for example
    protected double altitude2 = 0;
    protected boolean hasAltitude2 = false;
    protected long altitude2CalibrationTime = 0;
    protected float altitude2CalibrationAccuracy = 99;
    protected double altitude2CalibrationDelta = 0;
    // constants used to "calibrate" altitude2
    static final float _minAccuracyForAltitude2Calibration = 5; // in m
    static final float _minDeltaTimeForAltitude2Calibration = 20 * 60 * 1000; // in ms


    static final float _minAccuracyIni = 20; // in m
    protected float _minAccuracy = _minAccuracyIni;   // in m

    // max value for _minAccuracy
    static final float _maxMinAccuracy = 50;   // in m

    // always remember that accuracy is 3x worth on altitude than on latitude/longitude
    static final float _minAccuracyForAltitudeChangeLevel1 = 1; // in m
    static final float _minAltitudeChangeLevel1 = 3; // in m
    static final float _minAccuracyForAltitudeChangeLevel2 = 3; // in m
    static final float _minAltitudeChangeLevel2 = 10; // in m
    static final float _minAccuracyForAltitudeChangeLevel3 = 6; // in m
    static final float _minAltitudeChangeLevel3 = 20; // in m
    static final float _minAccuracyForAltitudeChangeLevel4 = 12; // in m
    static final float _minAltitudeChangeLevel4 = 50; // in m
    static final long _minDeltaTimeForAscentRate = 60 * 1000; // in ms
    static final long _maxDeltaTimeForAscentRate = 3 * 60 * 1000; // in ms

    static final long _minDeltaTimeToSaveLocation = 5 * 60 * 1000; // in ms
    static final float _minDeltaDistanceToSaveLocation = 20;   // in m

    private static final float MAX_ACCURACY_FOR_MAX_SPEED = 12; // in m

    // index of the loca_lap column in the cursor of each export
    private static final int TCX_LAP_COLUMN = 15;
    private static final int GPX_LAP_COLUMN = 14;

    // min speed to compute _elapsedTime or _ascent
    // 0.3m/s <=> 1.08km/h
    static final float _minSpeedToComputeStats = 0.3f; // in m/s

    public int nbOnLocationChanged = 0;
    public int nbGoodLocations = 0;
    protected int _nbBadAccuracyLocations = 0;

    protected float _distance = 0; // in m
    protected double _ascent = 0; // in m
    protected long _elapsedTime = 0; // in ms

    protected long _totalElapsedTime = 0; // in ms, always accumulates (including stationary)
    protected float _averageSpeed = 0; // in m/s
    protected float _maxSpeed = 0; // in m/s
    protected float _ascentRate = 0; // in m/s

    protected float _slope = 0; // in %

    // Lap state. The first lap is the ride itself: the lap accumulators run from the first fix
    // and newLap() closes them, so there is no "lap not started yet" state to special-case.
    protected int _lapCount = 0; // completed laps
    protected float _lapDistance = 0; // in m, current lap
    protected long _lapElapsedTime = 0; // in ms, current lap moving time
    protected long _lapTotalElapsedTime = 0; // in ms, current lap wall clock
    protected double _lapAscent = 0; // in m, current lap
    protected float _lapMaxSpeed = 0; // in m/s, current lap
    protected long _lapStartTime = 0; // in ms, epoch of the current lap start
    protected long _lastLapElapsedTime = 0; // in ms, last completed lap moving time
    protected float _lastLapDistance = 0; // in m, last completed lap
    protected long _bestLapElapsedTime = 0; // in ms, fastest completed lap, 0 when none yet

    // Lap heart rate accumulators (point average)
    protected long _lapHrSum = 0;
    protected int _lapHrCount = 0;
    protected int _lapHrMax = 0;

    // Lap cadence accumulators (point average)
    protected long _lapCadSum = 0;
    protected int _lapCadCount = 0;
    protected int _lapCadMax = 0;

    // Lap power max
    protected int _lapPowerMax = 0;

    // Average power is time weighted, so it needs the same running sums the app has to
    // reconstruct it: watts x ms, and the ms it was accumulated over. Accumulated over moving
    // time only, so it stays consistent with the moving-time average speed.
    protected double _lapPowerSum = 0; // in W.ms
    protected long _lapPowerElapsedTime = 0; // in ms
    protected double _totalPowerSum = 0; // in W.ms
    protected long _totalPowerElapsedTime = 0; // in ms

    protected int _nbAscent = 0;
    private double _nbAscentAltitudeLocalMin = 0;
    private double _nbAscentAltitudeLocalMax = 0;
    private boolean _nbAscentAscentInProgress = false;
    private boolean _nbAscentDescentInProgress = false;
    public static final float MAX_ACCURACY_FOR_NB_ASCENT = 7; // in m
    public static final float NB_ASCENT_DELTA_ALTITUDE = 50; // in m

    // Height of geoid above WGS84 ellipsoid
    protected double _geoidHeight = 0; // in m

    private int _hearRate = 0;
    private int _cadence = 0;
    private float _sensorSpeed = 0;
    private long _sensorSpeedTime = 0;
    private int _power = 0;
    private int _maxPower = 0;
    private int _maxHr = 0;
    private int _maxCadence = 0;

    private boolean _indoor = false;
    private long _lastIndoorTick = -1;

    // Energy expenditure estimation. The profile is injected by the app; the library never reads
    // preferences itself.
    private EnergyModel.Profile _riderProfile = null;
    private int _calories = 0;
    private boolean _caloriesDirty = true;

    // debug levels
    public int debugLevel = 0;
    public int debugLevelToast = 0;
    public String debugTagPrefix = "";

    // constants
    public static final int SKIPPED = 0x0;
    public static final int NORMAL = 0x1;
    public static final int SAVED = 0x2;

    protected Context _context = null;
    private AdvancedLocationDbHelper dbHelper;
    private SQLiteDatabase db;
    private boolean _saveLocation = false;
    private boolean _saveOnLocationChange = true;

    public AdvancedLocation() {
        this._context = null;
    }

    public AdvancedLocation(Context context) {
        this._context = context;
        dbHelper = AdvancedLocationDbHelper.getInstance(context);
        writeLock.lock();
        try {
            db = dbHelper.getWritableDatabase();
        } finally {
            writeLock.unlock();
        }
    }

    // Thread-safe database access methods.
    // getReadableDatabase()/getWritableDatabase() lazily (re)open the database if it has been closed.
    private SQLiteDatabase getReadableDatabase() {
        readLock.lock();
        try {
            if (db == null || !db.isOpen()) {
                dbHelper = AdvancedLocationDbHelper.getInstance(_context);
                db = dbHelper.getReadableDatabase();
            }
            return db;
        } finally {
            readLock.unlock();
        }
    }

    private SQLiteDatabase getWritableDatabase() {
        writeLock.lock();
        try {
            if (db == null || !db.isOpen()) {
                dbHelper = AdvancedLocationDbHelper.getInstance(_context);
                db = dbHelper.getWritableDatabase();
            }
            return db;
        } finally {
            writeLock.unlock();
        }
    }

    // getters
    public double getAltitude() {
        if (hasAltitude2 && altitude2CalibrationTime > 0) {
            return altitude2 + altitude2CalibrationDelta;
        }

        if (lastGoodLocation != null) {
            return lastGoodLocation.getAltitude();
        }
        return 0;
    }
    public double getAltitudeFromGps() {
        if (currentLocation != null) {
            return currentLocation.getAltitudeFromGps();
        }
        return 0;
    }
    public double getAltitudeFromPressure() {
        return altitude2;
    }


    public double getGoodAltitude() {
        if (lastGoodAscentLocation != null) {
            return lastGoodAscentLocation.getAltitude();
        }
        return 0;
    }

    public float getAccuracy() {
        if (currentLocation != null) {
            return currentLocation.getAccuracy();
        }
        return 0.0f;
    }

    public float getAltitudeAccuracy() {
        if (currentLocation != null) {
            return currentLocation.getAltitudeAccuracy();
        }
        return 0.0f;
    }

    public float getSpeed() {
        if (currentLocation != null) {
            Logger("getSpeed currentLocation time:" + currentLocation.getTime() + " speed:" + currentLocation.getSpeed() + " sensor time:" + _sensorSpeedTime + " speed:" + _sensorSpeed);
            if (_sensorSpeed != 0.0 && _sensorSpeedTime > 0 && currentLocation.getTime() < _sensorSpeedTime + 10 * 1000) {
                // we've got a sensor speed, and no gps speed at least 10s newer
                return _sensorSpeed;
            }
            return currentLocation.getSpeed();
        } else if (_sensorSpeedTime > 0) {
            Logger("getSpeed sensor time:" + _sensorSpeedTime + " speed:" + _sensorSpeed);
            return _sensorSpeed;
        }
        return 0.0f;
    }

    public float getAverageSpeed() {
        if ((_averageSpeed == 0) && (_elapsedTime > 0)) {
            // not yet calculated yet?
            _averageSpeed = (float) _distance / ((float) _elapsedTime / 1000f);
        }
        return _averageSpeed;
    }
    public float getMaxSpeed() {
        return _maxSpeed;
    }
    public int getNbAscent() {
        return _nbAscent;
    }

    public long getElapsedTime() {
        return _elapsedTime;
    }

    public long getTotalElapsedTime() {
        return _totalElapsedTime;
    }

    public int getTotalTimeSeconds() {
        return (int) (_totalElapsedTime / 1000);
    }

    // lap getters
    /** Number of completed laps; the lap in progress is {@code getLapCount() + 1}. */
    public int getLapCount() {
        return _lapCount;
    }

    /** Distance of the lap in progress, in m. */
    public float getLapDistance() {
        return _lapDistance;
    }

    /** Moving time of the lap in progress, in ms. */
    public long getLapElapsedTime() {
        return _lapElapsedTime;
    }

    /** Wall clock time of the lap in progress, in ms: this is the number a lap timer shows. */
    public long getLapTotalElapsedTime() {
        return _lapTotalElapsedTime;
    }

    public double getLapAscent() {
        return Math.floor(_lapAscent);
    }

    public float getLapMaxSpeed() {
        return _lapMaxSpeed;
    }

    /** Average speed of the lap in progress, in m/s, over its moving time. */
    public float getLapAverageSpeed() {
        if (_lapElapsedTime > 0) {
            return (float) _lapDistance / ((float) _lapElapsedTime / 1000f);
        }
        return 0;
    }

    /** Epoch of the current lap start, in ms, 0 before the first fix. */
    public long getLapStartTime() {
        return _lapStartTime;
    }

    /** Moving time of the last completed lap, in ms. */
    public long getLastLapElapsedTime() {
        return _lastLapElapsedTime;
    }

    public float getLastLapDistance() {
        return _lastLapDistance;
    }

    /** Moving time of the fastest completed lap, in ms, 0 when no lap is complete yet. */
    public long getBestLapElapsedTime() {
        return _bestLapElapsedTime;
    }

    /** Lap average heart rate (point average), bpm, 0 when no HR data. */
    public int getLapAverageHeartRate() {
        if (_lapHrCount > 0) {
            return (int) Math.round((double) _lapHrSum / (double) _lapHrCount);
        }
        return 0;
    }

    public int getLapAverageHr() {
        return getLapAverageHeartRate();
    }

    public int getLapMaxHeartRate() {
        return _lapHrMax;
    }

    public int getLapMaxHr() {
        return _lapHrMax;
    }

    /** Lap average cadence (point average), rpm, 0 when no cadence data. */
    public int getLapAverageCadence() {
        if (_lapCadCount > 0) {
            return (int) Math.round((double) _lapCadSum / (double) _lapCadCount);
        }
        return 0;
    }

    public int getLapMaxCadence() {
        return _lapCadMax;
    }

    public int getLapMaxPower() {
        return _lapPowerMax;
    }

    /**
     * Time weighted average power of the lap in progress, in W, 0 when no power data has been
     * received for it.
     */
    public int getLapAveragePower() {
        if (_lapPowerElapsedTime > 0) {
            return (int) Math.round(_lapPowerSum / _lapPowerElapsedTime);
        }
        return 0;
    }

    /**
     * Time weighted average power of the whole ride, in W, 0 when no power data has been received.
     */
    public int getAveragePower() {
        if (_totalPowerElapsedTime > 0) {
            return (int) Math.round(_totalPowerSum / _totalPowerElapsedTime);
        }
        return 0;
    }

    /**
     * Closes the lap in progress and starts a new one. The first lap is the ride itself, so this
     * is only ever called while a ride is in progress.
     *
     * <p>A zero length lap (a double press on the lap button) is still counted, but never becomes
     * the best lap: comparing against a 0 ms split would make every later lap look slow.
     */
    public void newLap() {
        Logger("newLap, closing lap " + (_lapCount + 1) + ": " + _lapElapsedTime + "ms "
                + _lapDistance + "m", LoggerType.TOAST);

        _lastLapElapsedTime = _lapElapsedTime;
        _lastLapDistance = _lapDistance;
        if (_lapElapsedTime > 0 && (_bestLapElapsedTime == 0 || _lapElapsedTime < _bestLapElapsedTime)) {
            _bestLapElapsedTime = _lapElapsedTime;
        }

        _lapCount++;
        _lapDistance = 0;
        _lapElapsedTime = 0;
        _lapTotalElapsedTime = 0;
        _lapAscent = 0;
        _lapMaxSpeed = 0;
        _lapHrSum = 0;
        _lapHrCount = 0;
        _lapHrMax = 0;
        _lapCadSum = 0;
        _lapCadCount = 0;
        _lapCadMax = 0;
        _lapPowerMax = 0;
        _lapPowerSum = 0;
        _lapPowerElapsedTime = 0;
        _lapStartTime = getTime() > 0 ? getTime() : System.currentTimeMillis();
    }

    /**
     * Immutable snapshot of the lap state, so a caller can persist it and hand it back to
     * {@link #setLapState(fr.jayps.android.LapState)} to resume a ride across a pause.
     */


    public fr.jayps.android.LapState getLapState() {
        return new fr.jayps.android.LapState(_lapCount, _lapDistance, _lapElapsedTime, _lapTotalElapsedTime,
                _lapAscent, _lapMaxSpeed, _lapStartTime, _lastLapElapsedTime, _lastLapDistance,
                _bestLapElapsedTime, _lapHrSum, _lapHrCount, _lapHrMax, _lapCadSum, _lapCadCount,
                _lapCadMax, _lapPowerMax, _lapPowerSum, _lapPowerElapsedTime,
                _totalPowerSum, _totalPowerElapsedTime);
    }

    /** Restores a snapshot taken by {@link #getLapState()}. */
    public void setLapState(fr.jayps.android.LapState state) {
        if (state == null) {
            return;
        }
        _lapCount = state.lapCount;
        _lapDistance = state.lapDistance;
        _lapElapsedTime = state.lapElapsedTime;
        _lapTotalElapsedTime = state.lapTotalElapsedTime;
        _lapAscent = state.lapAscent;
        _lapMaxSpeed = state.lapMaxSpeed;
        _lapStartTime = state.lapStartTime;
        _lastLapElapsedTime = state.lastLapElapsedTime;
        _lastLapDistance = state.lastLapDistance;
        _bestLapElapsedTime = state.bestLapElapsedTime;
        _lapHrSum = state.lapHrSum;
        _lapHrCount = state.lapHrCount;
        _lapHrMax = state.lapHrMax;
        _lapCadSum = state.lapCadSum;
        _lapCadCount = state.lapCadCount;
        _lapCadMax = state.lapCadMax;
        _lapPowerMax = state.lapPowerMax;
        // carried so a lap keeps its running average across a pause instead of starting over
        _lapPowerSum = state.lapPowerSum;
        _lapPowerElapsedTime = state.lapPowerElapsedTime;
        _totalPowerSum = state.totalPowerSum;
        _totalPowerElapsedTime = state.totalPowerElapsedTime;
    }

    public long getTime() {
        if (currentLocation != null) {
            return currentLocation.getTime();
        }
        return 0;
    }

    public float getDistance() {
        return _distance;
    }

    public double getAscent() {
        return Math.floor(_ascent);
    }

    public float getAscentRate() {
        return _ascentRate;
    }

    public float getSlope() {
        return _slope;
    }

    public boolean hasBearing() {
        if (currentLocation != null) {
            return currentLocation.hasBearing();
        }
        return false;
    }

    public float getBearing() {
        if (currentLocation != null) {
            return currentLocation.getBearing();
        }
        return 0;
    }

    public String getBearingText() {
        if (currentLocation != null) {
            // getBearing() is guaranteed to be in the range (0.0, 360.0] if the device has a bearing.
            return bearingText(currentLocation.getBearing());
        }
        return "";
    }

    public static String bearingText(float bearing) {
        String bearingText = "";

        bearing = bearing % 360;
        if (bearing < 0) {
            bearing += 360;
        }

        if (bearing >= 0 && bearing < 22.5) {
            bearingText = "N";
        }

        if (bearing >= 22.5 && bearing < 67.5) {
            bearingText = "NE";
        }

        if (bearing >= 67.5 && bearing < 112.5) {
            bearingText = "E";
        }

        if (bearing >= 112.5 && bearing < 157.5) {
            bearingText = "SE";
        }

        if (bearing >= 157.5 && bearing < 202.5) {
            bearingText = "S";
        }

        if (bearing >= 202.5 && bearing < 247.5) {
            bearingText = "SW";
        }

        if (bearing >= 247.5 && bearing < 292.5) {
            bearingText = "W";
        }

        if (bearing >= 292.5 && bearing < 337.5) {
            bearingText = "NW";
        }

        if (bearing >= 337.5 && bearing < 360) {
            bearingText = "N";
        }

        return bearingText;
    }

    public double getLatitude() {
        if (currentLocation != null) {
            return currentLocation.getLatitude();
        }
        return 0;
    }

    public double getLongitude() {
        if (currentLocation != null) {
            return currentLocation.getLongitude();
        }
        return 0;
    }

    public double getGeoidHeight() {
        return this._geoidHeight;
    }

    public double getAltitudeCalibrationDelta() {
        return this.altitude2CalibrationDelta;
    }

    // setters
    public void setElapsedTime(long elapsedTime) {
        this._elapsedTime = elapsedTime;
    }

    public void setTotalElapsedTime(long totalElapsedTime) {
        this._totalElapsedTime = totalElapsedTime;
    }

    public void setDistance(float distance) {
        this._distance = distance;
    }

    public void setAscent(double ascent) {
        this._ascent = ascent;
    }

    public void setGeoidHeight(double geoidHeight) {
        Logger("setGeoidHeight:" + geoidHeight);

        if (this._geoidHeight != geoidHeight) {
            this._geoidHeight = geoidHeight;

            // force to recalibrate altitude2 (pressure sensor, if we got one)
            hasAltitude2 = false;
            altitude2CalibrationTime = 0;
        }
    }

    public void setAltitudeCalibrationDelta(double altitudeCalibrationDelta) {
        Logger("setAltitudeCalibrationDelta:" + altitudeCalibrationDelta);
        if (altitudeCalibrationDelta != 0 && this.altitude2CalibrationDelta != altitudeCalibrationDelta) {
            this.altitude2CalibrationDelta = altitudeCalibrationDelta;
            this.altitude2CalibrationAccuracy = _minAccuracyForAltitude2Calibration;
            this.altitude2CalibrationTime = 1; // timestamp in the past
        }
    }

    public void setMaxSpeed(float maxSpeed) {
        this._maxSpeed = maxSpeed;
    }
    public void setNbAscent(int nbAscent) {
        // reset internal data
        _nbAscentAltitudeLocalMin = _nbAscentAltitudeLocalMax = 0;
        this._nbAscent = nbAscent;
    }
    public void setSaveLocation(boolean saveLocation) {
        this._saveLocation = saveLocation;
    }

    public void setSaveOnLocationChange(boolean saveOnLocationChange) {
        this._saveOnLocationChange = saveOnLocationChange;
    }

    public void setIndoor(boolean indoor) {
        this._indoor = indoor;
        if (!_indoor) {
            _lastIndoorTick = -1;
        }
    }

    public boolean isIndoor() {
        return _indoor;
    }

    /**
     * Supplies the rider profile used for energy expenditure estimation. Any field may be 0 to mean
     * "not set", in which case {@link EnergyModel} falls back to a less demanding estimator.
     *
     * @param ageYears   age in years, 0 when unknown
     * @param female     false selects the male Keytel/Mifflin coefficients
     * @param weightKg   body weight in kg, 0 when unknown
     * @param heightCm   body height in cm, used by the basal metabolic floor
     * @param restingHr  resting heart rate in bpm, 0 when unknown
     * @param maxHr      max heart rate in bpm, 0 when unknown
     */
    public void setRiderProfile(int ageYears, boolean female, int weightKg, int heightCm,
                                int restingHr, int maxHr) {
        EnergyModel.Profile profile = new EnergyModel.Profile(ageYears, female, weightKg, heightCm,
                restingHr, maxHr);
        if (profile.equals(_riderProfile)) {
            return;
        }
        _riderProfile = profile;
        _caloriesDirty = true;
    }

    /**
     * Estimated energy expenditure over the whole recorded track, in kcal.
     *
     * <p>Returns 0 when no rider profile has been supplied or the profile is too incomplete to
     * estimate from. The result is cached and invalidated whenever a point is written.
     */
    public int getCalories() {
        if (!_caloriesDirty) {
            return _calories;
        }
        _calories = computeCalories();
        _caloriesDirty = false;
        return _calories;
    }

    private int _calorieTier() {
        return _riderProfile == null ? EnergyModel.TIER_NONE
                : EnergyModel.tierFor(_riderProfile);
    }

    /** Reads a speed column stored as TEXT in m/s and converts it to km/h. */
    private static double speedKmh(Cursor cursor, int column) {
        if (cursor.isNull(column)) {
            return -1;
        }
        return Math.abs(safeFloat(cursor.getString(column), 0f)) * 3.6;
    }

    private int computeCalories() {
        int tier = _calorieTier();
        if (tier == EnergyModel.TIER_NONE) {
            return 0;
        }
        String selectQuery = "SELECT loca_time, loca_hr, loca_speed FROM "
                + AdvancedLocationDbHelper.Location.TABLE_NAME + " ORDER BY _ID ASC";
        Cursor cursor = getReadableDatabase().rawQuery(selectQuery, null);
        try {
            List<Long> times = new ArrayList<>();
            List<Integer> heartRates = new ArrayList<>();
            List<Double> speedsKmh = new ArrayList<>();
            while (cursor.moveToNext()) {
                if (cursor.isNull(0)) {
                    continue;
                }
                long time;
                try {
                    time = Long.parseLong(cursor.getString(0));
                } catch (NumberFormatException e) {
                    continue;
                }
                times.add(time);
                heartRates.add(cursor.isNull(1) ? -1 : safeInt(cursor.getString(1), -1));
                speedsKmh.add(speedKmh(cursor, 2));
            }
            if (times.size() < 2) {
                return 0;
            }
            return EnergyModel.totalCalories(tier, _riderProfile,
                    toLongArray(times), toIntArray(heartRates), toDoubleArray(speedsKmh));
        } finally {
            cursor.close();
        }
    }

    private static long[] toLongArray(List<Long> values) {
        long[] out = new long[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }

    private static int[] toIntArray(List<Integer> values) {
        int[] out = new int[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }

    private static double[] toDoubleArray(List<Double> values) {
        double[] out = new double[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }

    private static int safeInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static float safeFloat(String value, float fallback) {
        try {
            return Float.parseFloat(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    public int onLocationChanged(Location location, int heartRate, int cadence, int power) {
        int returnValue = NORMAL;
        long deltaTime = 0;
        float deltaDistance = 0;
        double deltaAscent = 0;
        double deltaAltitude = 0;
        float deltaAltitudeAccuracy = 0;
        boolean isFirstLocation = false;

        if (this._geoidHeight != 0) {
        // we get an height of geoid (above WGS84 ellipsoid), use it to correct altitude
            location.setAltitude(location.getAltitude() - this._geoidHeight);
        }

        nbOnLocationChanged++;
        Logger("onLocationChanged: " +nbGoodLocations+"/"+nbOnLocationChanged+" "+(location.getTime()/1000)+","+location.getLatitude()+","+location.getLongitude()+","+location.getAltitude()+"("+this._geoidHeight+"),"+location.getAccuracy());

        if (lastLocation == null) {
            // save 1st location for next call to onLocationChanged()
            lastLocation = new LocationWithExtraFields(location);
            isFirstLocation = true;
        }

        if (location.getAccuracy() > _minAccuracy) {
            _nbBadAccuracyLocations++;
            if (_nbBadAccuracyLocations > 10) {
                float _prevMinAccuracy = _minAccuracy;

                _minAccuracy = (float) Math.floor(1.5f * _minAccuracy);

                if (_minAccuracy > _maxMinAccuracy) {
                    // max value for _minAccuracy
                    _minAccuracy = _maxMinAccuracy;
                }

                if (_minAccuracy != _prevMinAccuracy) {
                    _nbBadAccuracyLocations = 0;

                    Logger("Accuracy to often above _minAccuracy, augment _minAccuracy to " + _minAccuracy,  LoggerType.TOAST);
                }
            }
        }
        if (location.getAccuracy() < MAX_ACCURACY_FOR_MAX_SPEED) {
            _maxSpeed = Math.max(location.getSpeed(), _maxSpeed);
            _lapMaxSpeed = Math.max(location.getSpeed(), _lapMaxSpeed);
        }
        _hearRate = heartRate;
        _cadence = cadence;
        _power = power;
        _maxPower = Math.max(_power, _maxPower);
        if (_hearRate > 0) {
            if (_hearRate > _maxHr) {
                _maxHr = _hearRate;
            }
            _lapHrSum += _hearRate;
            _lapHrCount++;
            if (_hearRate > _lapHrMax) {
                _lapHrMax = _hearRate;
            }
        }
        if (_cadence > 0) {
            if (_cadence > _maxCadence) {
                _maxCadence = _cadence;
            }
            _lapCadSum += _cadence;
            _lapCadCount++;
            if (_cadence > _lapCadMax) {
                _lapCadMax = _cadence;
            }
        }
        if (_power > 0) {
            if (_power > _lapPowerMax) {
                _lapPowerMax = _power;
            }
        }

        if ((lastGoodLocation != null) && ((location.getTime() - lastGoodLocation.getTime()) < 500)) {
            // less than X ms, skip this location
            return SKIPPED;
        }

        if (hasAltitude2) {
            if (
                (location.getAccuracy() < altitude2CalibrationAccuracy - 0.5)
                ||
                ((location.getTime() - altitude2CalibrationTime > _minDeltaTimeForAltitude2Calibration)
                && (location.getAccuracy() < _minAccuracyForAltitude2Calibration))
                ) {
                    String s = altitude2CalibrationDelta + "->";
                    altitude2CalibrationTime = location.getTime();
                    altitude2CalibrationAccuracy = location.getAccuracy();
                    altitude2CalibrationDelta = location.getAltitude() - altitude2;

                    // force to restart computations based on altitude
                    lastGoodAscentLocation = null;

                    s += altitude2CalibrationDelta;
                    Logger("altitude2CalibrationDelta:" + s);
                    Logger("delta:" + s, LoggerType.TOAST);
                }
        }

        currentLocation = new LocationWithExtraFields(location);

        if (currentLocation.getAccuracy() <= _minAccuracy) {

            if (lastGoodLocation == null) {
                lastGoodLocation = currentLocation;
            }

            deltaTime = location.getTime() - lastGoodLocation.getTime();
            deltaDistance = location.distanceTo(lastGoodLocation);

            if (currentLocation.getAccuracy() <= (_minAccuracy / 1.5f)) {
                float _prevMinAccuracy = _minAccuracy;

                _minAccuracy = (float) Math.floor(_minAccuracy / 1.5f);

                if (_minAccuracy < _minAccuracyIni) {
                    _minAccuracy = _minAccuracyIni;
                }

                if (_minAccuracy != _prevMinAccuracy) {
                    Logger("Accuracy below _minAccuracy, decrease it to: " + _minAccuracy, LoggerType.TOAST);
                }
            }

            float localAverageSpeed = deltaTime > 0 ? ((float) deltaDistance / ((float) deltaTime / 1000f)) : 0; // in m/s

            //Logger("localAverageSpeed:" + localAverageSpeed + " speed=" + currentLocation.getSpeed());

            // additional conditions to compute statistics
            if (
                isFirstLocation
                ||
                (localAverageSpeed > _minSpeedToComputeStats)
            ) {
                _elapsedTime += deltaTime;
                _averageSpeed = _elapsedTime > 0 ? ((float) _distance / ((float) _elapsedTime / 1000f)) : 0;

                _lapElapsedTime += deltaTime;
                // Time weighted power, accumulated over the same moving window as the lap's
                // elapsed time so the average speed and the average power share a denominator.
                // A non positive power means "no meter connected" rather than "zero watts", so
                // those intervals are left out instead of dragging the average towards zero.
                if (power > 0) {
                    _lapPowerSum += power * (double) deltaTime;
                    _lapPowerElapsedTime += deltaTime;
                    _totalPowerSum += power * (double) deltaTime;
                    _totalPowerElapsedTime += deltaTime;
                }

                if (_lapStartTime == 0) {
                    _lapStartTime = currentLocation.getTime();
                }

                if (lastGoodAscentLocation == null) {
                    lastGoodAscentLocation = currentLocation;
                    lastGoodAscentLocation2 = currentLocation;
                    lastGoodAscentRateLocation = currentLocation;
                }

                deltaAltitude = currentLocation.getAltitude() - lastGoodAscentLocation.getAltitude();
                deltaAltitudeAccuracy = currentLocation.getAltitudeAccuracy() - lastGoodAscentLocation.getAltitudeAccuracy();

                if (deltaAltitude < 0 && deltaAltitudeAccuracy <= -3) {
                    // Goal: during a "climb", if altitude decreases and accuracy is better, update lastGoodAscentLocation
                    // it will avoid use of previously "wrong" (too high) lastGoodAscentLocation with lesser accuracy to compute ascent
                    Logger("altitude decreases and accuracy is better (it decreases of at least 3m), use this position as lastGoodAscentLocation");
                    lastGoodAscentLocation = currentLocation;
                    lastGoodAscentRateLocation = currentLocation;
                    deltaAltitude = 0;
                }

                if (Math.abs(deltaAltitude) < 0.5 && deltaAltitudeAccuracy < 0) {
                    Logger("flat section, and better accuracy, reset lastGoodAscentLocation", 2);
                    lastGoodAscentLocation = currentLocation;
                    deltaAltitude = 0;
                }

                if (_testLocationOKForAscent()) {
                    // compute ascent
                    // always remember that accuracy is 3x worth on altitude than on latitude/longitude
                    deltaAscent = deltaAltitude;

                    lastGoodAscentLocation = currentLocation;

                    if (lastGoodAscentLocation2.getAltitudeAccuracy() > lastGoodAscentLocation.getAltitudeAccuracy()) {
                        Logger("Update lastGoodAscentLocation2 acc:" + lastGoodAscentLocation2.getAltitudeAccuracy() +"->"+ lastGoodAscentLocation.getAltitudeAccuracy(), 2);
                        lastGoodAscentLocation2 = currentLocation;
                    }

                    if (lastGoodAscentLocation.getTime() - lastGoodAscentLocation2.getTime() > 60 * 10 * 1000) {
                        Logger("lastGoodAscentLocation2 too old", 2);
                        lastGoodAscentLocation2 = currentLocation;
                    }

                    if (deltaAscent > 0) {
                        _ascent += deltaAscent;
                        _lapAscent += deltaAscent;
                    } else {
                        lastGoodAscentLocation2 = currentLocation;
                        Logger("descent, reset lastGoodAscentLocation2", 2);
                    }

                    // try to compute ascentRate if enough time has elapsed
                    long tmpDeltaTime = currentLocation.getTime() - lastGoodAscentRateLocation.getTime();

                    if (tmpDeltaTime < _minDeltaTimeForAscentRate) {
                        // not enough time since lastGoodAscentRateLocation to compute ascentRate and slope
                        Logger("tmpDeltaTime:" + tmpDeltaTime +"<"+ _minDeltaTimeForAscentRate + " ascentRate skip");
                    } else {
                        double tmpDeltaAscent = Math.floor(currentLocation.getAltitude() - lastGoodAscentRateLocation.getAltitude());
                        float tmpDeltaDistance = _distance - lastGoodAscentRateLocation.distance;

                        _ascentRate = tmpDeltaTime > 0 ? ((float) tmpDeltaAscent / (tmpDeltaTime) * 1000) : 0; // m/s

                        if (tmpDeltaDistance != 0) {
                            _slope = tmpDeltaDistance > 0 ? ((float) tmpDeltaAscent / tmpDeltaDistance) : 0; // in %
                        } else {
                            _slope = 0;
                        }

                        Logger("alt:" + lastGoodAscentRateLocation.getAltitude() + "->" + currentLocation.getAltitude() + ":" + tmpDeltaAscent + " _ascentRate:" + _ascentRate + " _slope:" + _slope);

                        lastGoodAscentRateLocation = currentLocation;
                    }
                } // if (_testLocationOKForAscent()) {

                if (currentLocation.getAccuracy() < MAX_ACCURACY_FOR_NB_ASCENT) {
                    if (_nbAscentAltitudeLocalMin == 0 && _nbAscentAltitudeLocalMax == 0) {
                        // first time only
                        _nbAscentAltitudeLocalMin = _nbAscentAltitudeLocalMax = currentLocation.getAltitude();
                        _nbAscentAscentInProgress = _nbAscentDescentInProgress = false;
                    }
                    _nbAscentAltitudeLocalMin = Math.min(currentLocation.getAltitude(), _nbAscentAltitudeLocalMin);
                    _nbAscentAltitudeLocalMax = Math.max(currentLocation.getAltitude(), _nbAscentAltitudeLocalMax);

                    if (!_nbAscentDescentInProgress && currentLocation.getAltitude() <= _nbAscentAltitudeLocalMax - NB_ASCENT_DELTA_ALTITUDE) {
                        Logger("nbAscent: start new descent", 1);
                        _nbAscentDescentInProgress = true;
                        _nbAscentAscentInProgress = false;
                        _nbAscentAltitudeLocalMin = currentLocation.getAltitude();
                    }
                    if (!_nbAscentAscentInProgress && currentLocation.getAltitude() >= _nbAscentAltitudeLocalMin + NB_ASCENT_DELTA_ALTITUDE) {
                        Logger("nbAscent: start new ascent", 1);
                        _nbAscentAscentInProgress = true;
                        _nbAscentDescentInProgress = false;
                        _nbAscentAltitudeLocalMax = currentLocation.getAltitude();
                        _nbAscent++;
                    }

                    Logger("nbAscent: " + _nbAscentAltitudeLocalMin +"<"+_nbAscentAltitudeLocalMax + " " + currentLocation.getAltitude() + " " + (_nbAscentAscentInProgress ? "ASC" : "NOASC") + " " + (_nbAscentDescentInProgress ? "DSC" : "NODSC"), 2);
                }

                nbGoodLocations++;

                if (_testFlatSection(lastGoodAscentRateLocation, currentLocation)) {
                    Logger("slope below 1% on the last 500m, update lastGoodAscentRateLocation");
                    _slope = 0;
                    _ascentRate = 0;
                    lastGoodAscentRateLocation = currentLocation;
                }

                long tmpDeltaTime = currentLocation.getTime() - lastGoodAscentRateLocation.getTime();
                if (tmpDeltaTime > _maxDeltaTimeForAscentRate && currentLocation.getAltitudeAccuracy() < 10) {
                    Logger("lastGoodAscentRateLocation too old ("+tmpDeltaTime+"s) and current accuracy ok ("+currentLocation.getAltitudeAccuracy()+"m), update lastGoodAscentRateLocation");
                    _slope = 0;
                    _ascentRate = 0;
                    lastGoodAscentRateLocation = currentLocation;
                }

                Logger(currentLocation.getTime()/1000+ " deltaDistance:" + deltaDistance + " deltaTime:" + deltaTime + " deltaAscent:" + deltaAscent + " _ascent:" + _ascent + " _distance: " + _distance + " _averageSpeed: " + _averageSpeed + " _elapsedTime:" + _elapsedTime);

                if (_testLocationOKForSave()) {
                    Logger("Location OK to be saved", 2);
                    returnValue = SAVED;
                    lastSavedLocation = currentLocation;
                    if (_saveLocation && _saveOnLocationChange) {
                        _saveLocation(this.getTime());
                    }
                }

            } // additional conditions to compute statistics

            _totalElapsedTime += deltaTime;
            _distance += deltaDistance;
            _lapTotalElapsedTime += deltaTime;
            _lapDistance += deltaDistance;
            lastGoodLocation = currentLocation;

        } // if (currentLocation.getAccuracy() <= _minAccuracy) {

        lastLocation = currentLocation;

        return returnValue;
    }

    // Array of altitude, to compute median of _ALTITUDES2_NB values
    private static int _ALTITUDES2_NB = 5;
    private double[] _altitudes2 = new double[_ALTITUDES2_NB];
    private int _altitudes2_i = 0;

    public void onAltitudeChanged(double altitude) {
        Logger("onAltitudeChanged: " + altitude + " altitude2CalibrationTime=" + altitude2CalibrationTime + " altitude2CalibrationAccuracy=" + altitude2CalibrationAccuracy + " altitude2CalibrationDelta=" + altitude2CalibrationDelta, 2);
        _altitudes2[_altitudes2_i % _ALTITUDES2_NB] = altitude;
        _altitudes2_i++;
        if (_altitudes2_i > _ALTITUDES2_NB) {
            double[] _altitudes2b = Arrays.copyOf(_altitudes2, _ALTITUDES2_NB);;
            Arrays.sort(_altitudes2b);
            this.hasAltitude2 = true;
            this.altitude2 = _altitudes2b[(int) Math.floor(_ALTITUDES2_NB/2)]; // median value
            Logger("altitude=" + altitude + " this.altitude2=" + this.altitude2, 2);
        }
    }

    public void setSensorSpeed(float speed, long time) {
        this._sensorSpeed = speed;
        this._sensorSpeedTime = time;
        Logger("setSensorSpeed:" + _sensorSpeedTime + " speed:" + _sensorSpeed);
    }

    private boolean _testFlatSection(LocationWithExtraFields l1, LocationWithExtraFields l2) {
        float deltaDistance = l2.distance - l1.distance;
        double deltaAltitude = l2.getAltitude() - l1.getAltitude();

        if ((deltaDistance > 500) && (100 * Math.abs(deltaAltitude) < deltaDistance)) {
            // distance greater than 1000m and slope below 1%: this is a flat portion

            if (l2.getAltitudeAccuracy() > 5) {
                // if l2.getAltitudeAccuracy() is bad, avoid positive result (wait a bit more for better accuracy?)
                return false;
            }
            // Note: if l1.getAltitudeAccuracy() was bad, don't avoid positive result (it won't change if we wait)

            return true;
        }
        return false;
    }

    private boolean _testLocationOKForAscent() {
        if (lastGoodAscentLocation == null) {
            return false;
        }

        float worstAccuracy = Math.max(lastGoodAscentLocation.getAltitudeAccuracy(), currentLocation.getAltitudeAccuracy());
        double deltaAltitude = currentLocation.getAltitude() - lastGoodAscentLocation.getAltitude();
        float deltaAccuracy = currentLocation.getAltitudeAccuracy() - lastGoodAscentLocation.getAltitudeAccuracy();
        boolean result = false;

        if ((Math.abs(deltaAltitude) >= _minAltitudeChangeLevel1) && (worstAccuracy <= _minAccuracyForAltitudeChangeLevel1)) {
            Logger("abs(deltaAltitude):" + Math.abs(deltaAltitude) + ">=" + _minAltitudeChangeLevel1 + " & worstAccuracy:" + worstAccuracy + "<=" + _minAccuracyForAltitudeChangeLevel1);
            result = true;
        } else if ((Math.abs(deltaAltitude) >= _minAltitudeChangeLevel2) && (worstAccuracy <= _minAccuracyForAltitudeChangeLevel2)) {
            Logger("abs(deltaAltitude):" + Math.abs(deltaAltitude) + ">=" + _minAltitudeChangeLevel2 + " & worstAccuracy:" + worstAccuracy + "<=" + _minAccuracyForAltitudeChangeLevel2);
            result = true;
        } else if ((Math.abs(deltaAltitude) >= _minAltitudeChangeLevel3) && (worstAccuracy <= _minAccuracyForAltitudeChangeLevel3)) {
            Logger("abs(deltaAltitude):" + Math.abs(deltaAltitude) + ">=" + _minAltitudeChangeLevel3 + " & worstAccuracy:" + worstAccuracy + "<=" + _minAccuracyForAltitudeChangeLevel3);
            result = true;
        } else if ((Math.abs(deltaAltitude) >= _minAltitudeChangeLevel4) && (worstAccuracy <= _minAccuracyForAltitudeChangeLevel4)) {
            Logger("abs(deltaAltitude):" + Math.abs(deltaAltitude) + ">=" + _minAltitudeChangeLevel4 + " & worstAccuracy:" + worstAccuracy + "<=" + _minAccuracyForAltitudeChangeLevel4);
            result = true;
        } else if (Math.abs(deltaAltitude) >= 4 * worstAccuracy) {
            Logger("abs(deltaAltitude):" + Math.abs(deltaAltitude) + ">=4*worstAccuracy: 4*" + worstAccuracy);
            result = true;
        } else if (lastGoodAscentLocation2 != null) {
            float worstAccuracy2 = Math.max(lastGoodAscentLocation2.getAltitudeAccuracy(), currentLocation.getAltitudeAccuracy());
            double deltaAltitude2 = currentLocation.getAltitude() - lastGoodAscentLocation2.getAltitude();
            if (Math.abs(deltaAltitude2) >= 4 * worstAccuracy2) {
                Logger("abs(deltaAltitude2):" + Math.abs(deltaAltitude2) + ">=4*worstAccuracy2: 4*" + worstAccuracy2);
                result = true;
            }
        }

        if (result) {
            Logger("alt:" + lastGoodAscentLocation.getAltitude() + "->" + currentLocation.getAltitude() + ":" + deltaAltitude + " - acc: " + worstAccuracy);
            return true;
        }

        return false;
    }

    private boolean _testLocationOKForSave() {
        if (
        (lastSavedLocation == null) // 1st saved location
        ||
        (currentLocation.getTime() - lastSavedLocation.getTime() >= _minDeltaTimeToSaveLocation)
        ||
        (currentLocation.distanceTo(lastSavedLocation) >= _minDeltaDistanceToSaveLocation)
        ) {
            return true;
        }

        return false;
    }

    private void _saveLocation(long timeMs) {
        ContentValues values = new ContentValues();
        values.put("loca_time", timeMs);
        if (_indoor) {
            values.putNull("loca_lat");
            values.putNull("loca_lon");
            values.putNull("loca_altitude");
            values.putNull("loca_gps_altitude");
            values.putNull("loca_pressure_altitude");
            values.putNull("loca_accuracy");
            values.put("loca_ascent", this.getAscent());
        } else {
            values.put("loca_lat", this.getLatitude());
            values.put("loca_lon", this.getLongitude());
            values.put("loca_altitude", this.getAltitude());
            values.put("loca_gps_altitude", this.getAltitudeFromGps());
            values.put("loca_pressure_altitude", this.getAltitudeFromPressure());
            values.put("loca_ascent", this.getAscent());
            values.put("loca_accuracy", this.getAccuracy());
        }
        if (_hearRate > 0) {
            values.put("loca_hr", _hearRate);
        }
        if (_cadence > 0) {
            values.put("loca_cad", _cadence);
        }
        if (_power > 0) {
            values.put("loca_power",_power);
        }
        values.put("loca_speed", this.getSpeed());
        values.put("loca_distance", this.getDistance());
        values.put("loca_lap", _lapCount);
        //values.put("loca_comment", "");

        long newRowId = getWritableDatabase().insert(
                AdvancedLocationDbHelper.Location.TABLE_NAME,
                null,
                values);
        _caloriesDirty = true;
    };

    /**
     * Save the current location to the database using the supplied timestamp.
     * Used by an external timer to record a trackpoint at a fixed interval
     * even when no GPS fix is delivered (e.g. when stationary). Bypasses the
     * accuracy gate and uses the last known position.
     */
    /**
     * Advance the indoor (no-GPS) state by one timer tick.
     * Total time is accumulated even while stationary; distance is integrated
     * from the current sensor speed (or power-estimated speed). Heart rate,
     * cadence and power are taken from the supplied sensor values.
     */
    public void updateIndoor(int heartRate, int cadence, int power, long nowMs) {
        if (_lastIndoorTick < 0) {
            _lastIndoorTick = nowMs;
            return;
        }
        long delta = nowMs - _lastIndoorTick;
        if (delta <= 0) {
            _lastIndoorTick = nowMs;
            return;
        }
        _lastIndoorTick = nowMs;
        _elapsedTime += delta;
        _totalElapsedTime += delta;
        float speed = getSpeed();
        _distance += speed * (delta / 1000f);
        _averageSpeed = _elapsedTime > 0 ? _distance / (_elapsedTime / 1000f) : 0;
        _maxSpeed = Math.max(speed, _maxSpeed);
        _hearRate = heartRate;
        _cadence = cadence;
        _power = power;
        _maxPower = Math.max(power, _maxPower);
        if (_hearRate > 0) {
            if (_hearRate > _maxHr) {
                _maxHr = _hearRate;
            }
            _lapHrSum += _hearRate;
            _lapHrCount++;
            if (_hearRate > _lapHrMax) {
                _lapHrMax = _hearRate;
            }
        }
        if (_cadence > 0) {
            if (_cadence > _maxCadence) {
                _maxCadence = _cadence;
            }
            _lapCadSum += _cadence;
            _lapCadCount++;
            if (_cadence > _lapCadMax) {
                _lapCadMax = _cadence;
            }
        }
        if (_power > 0) {
            if (_power > _lapPowerMax) {
                _lapPowerMax = _power;
            }
        }

        _lapElapsedTime += delta;
        _lapTotalElapsedTime += delta;
        _lapDistance += speed * (delta / 1000f);
        _lapMaxSpeed = Math.max(speed, _lapMaxSpeed);
        // as outdoors: a non positive power means no meter connected, not zero watts
        if (power > 0) {
            _lapPowerSum += power * (double) delta;
            _lapPowerElapsedTime += delta;
            _totalPowerSum += power * (double) delta;
            _totalPowerElapsedTime += delta;
        }
        if (_lapStartTime == 0) {
            _lapStartTime = nowMs;
        }
    }

    public void saveCurrentLocationAtInterval(long timeMs) {
        if (!_saveLocation) {
            return;
        }
        if (!_indoor && currentLocation == null) {
            return;
        }
        if (!_indoor) {
            lastSavedLocation = currentLocation;
        }
        _saveLocation(timeMs);
    }

    public String getTCX(final String sportType) {
        readLock.lock();
        try {
            return AdvancedLocationExport.getTCX(getReadableDatabase(), _riderProfile, sportType);
        } finally {
            readLock.unlock();
        }
    }

    public String getGPX(boolean extended) {
        readLock.lock();
        try {
            return AdvancedLocationExport.getGPX(getReadableDatabase(), _riderProfile, _context, extended);
        } finally {
            readLock.unlock();
        }
    }

    public void resetGPX() {
        writeLock.lock();
        try {
            String sql = "DELETE FROM " + AdvancedLocationDbHelper.Location.TABLE_NAME;
            getWritableDatabase().execSQL(sql);
        } finally {
            writeLock.unlock();
        }
    }
    
    public void close() {
        writeLock.lock();
        try {
            if (db != null && db.isOpen()) {
                db.close();
            }
            if (dbHelper != null) {
                dbHelper.close();
            }
            db = null;
        } finally {
            writeLock.unlock();
        }
    }

    // log functions
    private enum LoggerType { LOG, TOAST };

    public void Logger(String s) {
        Logger(s, 1, LoggerType.LOG);
    }

    public void Logger(String s, LoggerType type) {
        Logger(s, 1, type);
    }

    public void Logger(String s, int level) {
        Logger(s, level, LoggerType.LOG);
    }

    public void Logger(String s, int level, LoggerType type) {
        if (type == LoggerType.TOAST) {
            if (this.debugLevelToast >= level) {
                if (this._context != null) {
                    Toast.makeText(this._context, s, Toast.LENGTH_LONG).show();
                }
            }
        }

        if (this.debugLevel >= level) {
            if (level == 2) {
                Log.v(this.debugTagPrefix + TAG + ":" + level, s);
            } else {
                Log.d(this.debugTagPrefix + TAG + ":" + level, s);
            }
        }
    }

    public int getHeartRate() {
        return _hearRate;
    }

    public int getHr() {
        return _hearRate;
    }

    public int getMaxHeartRate() {
        return _maxHr;
    }

    public int getMaxHr() {
        return _maxHr;
    }

    public int getCadence() {
        return _cadence;
    }

    public int getMaxCadence() {
        return _maxCadence;
    }

    public int getPower() {
        return _power;
    }

    public int getMaxPower() {
        return _maxPower;
    }

    public boolean hasPowerData() {
        return AdvancedLocationStats.hasPowerData(getReadableDatabase());
    }

    public int getAvgPower(int seconds) {
        return AdvancedLocationStats.getAvgPower(getReadableDatabase(), seconds);
    }

    public int getNormalizedPower(int seconds) {
        return AdvancedLocationStats.getNormalizedPower(getReadableDatabase(), seconds);
    }
}