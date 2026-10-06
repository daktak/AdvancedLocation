package fr.jayps.android;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.hardware.Sensor;
import android.hardware.SensorManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class AdvancedLocationExport {
    private static final int TCX_LAP_COLUMN = 15;
    private static final int GPX_LAP_COLUMN = 14;

    private static String tcxTime(SimpleDateFormat sdf, long timeMs) {
        String time = sdf.format(new Date(timeMs));
        return time.substring(0, time.length() - 2) + ':' + time.substring(time.length() - 2);
    }

    private static int lapIndex(Cursor cursor, int column) {
        return cursor.isNull(column) ? 0 : safeInt(cursor.getString(column), 0);
    }

    private static int safeInt(String value, int fallback) {
        try {
            if (value == null) {
                return fallback;
            }
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static float safeFloat(String value, float fallback) {
        try {
            if (value == null) {
                return fallback;
            }
            return Float.parseFloat(value.trim());
        } catch (Exception e) {
            return fallback;
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

    public static String lapXml(SimpleDateFormat sdf, long startTime, long endTime,
                                float distance, float maxSpeed, int calories,
                                int avgHr, int maxHr, int avgCad, int maxCad,
                                int maxPower, StringBuilder track) {
        StringBuilder lap = new StringBuilder();
        lap.append("<Lap StartTime=\"").append(tcxTime(sdf, startTime)).append("\">\n");
        lap.append("    <TotalTimeSeconds>").append(Math.max(0, endTime - startTime) / 1000.0).append("</TotalTimeSeconds>\n");
        lap.append("    <DistanceMeters>").append(distance).append("</DistanceMeters>\n");
        if (maxSpeed > 0) {
            lap.append("    <MaximumSpeed>").append(maxSpeed).append("</MaximumSpeed>\n");
        }
        if (avgHr > 0) {
            lap.append("    <AverageHeartRateBpm><Value>").append(avgHr).append("</Value></AverageHeartRateBpm>\n");
        }
        if (maxHr > 0) {
            lap.append("    <MaximumHeartRateBpm><Value>").append(maxHr).append("</Value></MaximumHeartRateBpm>\n");
        }
        if (avgCad > 0) {
            lap.append("    <Cadence>").append(avgCad).append("</Cadence>\n");
        }
        lap.append("    <Calories>").append(Math.min(calories, EnergyModel.MAX_CALORIES)).append("</Calories>\n");
        lap.append("    <Intensity>Active</Intensity>\n");
        lap.append("    <TriggerMethod>Manual</TriggerMethod>\n");
        lap.append("<Track>\n").append(track).append("</Track>\n</Lap>\n");
        return lap.toString();
    }

    public static String getTCX(SQLiteDatabase db, EnergyModel.Profile profile, final String sportType) {
        StringBuilder tcx = new StringBuilder();
        tcx.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<TrainingCenterDatabase xsi:schemaLocation=\"http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2 http://www.garmin.com/xmlschemas/TrainingCenterDatabasev2.xsd\" "
                + "xmlns:ns5=\"http://www.garmin.com/xmlschemas/ActivityGoals/v1\" "
                + "xmlns:ns3=\"http://www.garmin.com/xmlschemas/ActivityExtension/v2\" "
                + "xmlns:ns2=\"http://www.garmin.com/xmlschemas/UserProfile/v2\" "
                + "xmlns=\"http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2\" "
                + "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns:ns4=\"http://www.garmin.com/xmlschemas/ProfileExtension/v1\">\n");

        String selectQuery = "SELECT _ID, loca_time, loca_lat, loca_lon, loca_altitude, loca_accuracy, loca_comment, loca_ascent, loca_gps_altitude, loca_pressure_altitude, loca_hr, loca_cad, loca_power, loca_speed, loca_distance, loca_lap FROM " + AdvancedLocationDbHelper.Location.TABLE_NAME + " ORDER BY _ID ASC";
        Cursor cursor = db.rawQuery(selectQuery, null);

        try {
            if (cursor.moveToFirst()) {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");
                tcx.append("<Activities>\n<Activity Sport=\"" + sportType + "\">\n<Id>" + tcxTime(sdf, Long.parseLong(cursor.getString(1))) + "</Id>\n");

                int currentLap = lapIndex(cursor, TCX_LAP_COLUMN);
                long lapStartTime = Long.parseLong(cursor.getString(1));
                long lastRowTime = lapStartTime;
                float distanceBeforeLap = 0;
                float lapDistance = 0;
                float lapMaxSpeed = 0;
                int lapCalories = 0;
                long lapHrSum = 0;
                int lapHrCount = 0;
                int lapHrMax = 0;
                long lapCadSum = 0;
                int lapCadCount = 0;
                int lapCadMax = 0;
                int lapPowerMax = 0;
                long prevTime = -1;
                StringBuilder lapTrack = new StringBuilder();

                do {
                    long rowTime = Long.parseLong(cursor.getString(1));
                    float rowDistance = cursor.isNull(14) ? lapDistance : safeFloat(cursor.getString(14), lapDistance);
                    float rowMaxSpeed = cursor.isNull(13) ? 0f : Math.abs(safeFloat(cursor.getString(13), 0f));
                    int rowHr = cursor.isNull(10) ? 0 : safeInt(cursor.getString(10), 0);
                    int rowCad = cursor.isNull(11) ? 0 : safeInt(cursor.getString(11), 0);
                    int rowPower = cursor.isNull(12) ? 0 : safeInt(cursor.getString(12), 0);

                    int rowLap = lapIndex(cursor, TCX_LAP_COLUMN);
                    if (rowLap != currentLap) {
                        tcx.append(lapXml(sdf, lapStartTime, rowTime, lapDistance - distanceBeforeLap,
                                lapMaxSpeed, lapCalories,
                                (lapHrCount > 0) ? (int) Math.round((double) lapHrSum / (double) lapHrCount) : 0,
                                lapHrMax,
                                (lapCadCount > 0) ? (int) Math.round((double) lapCadSum / (double) lapCadCount) : 0,
                                lapCadMax, lapPowerMax, lapTrack));
                        lapTrack = new StringBuilder();
                        distanceBeforeLap = lapDistance;
                        currentLap = rowLap;
                        lapStartTime = rowTime;
                        lapDistance = rowDistance;
                        lapMaxSpeed = rowMaxSpeed;
                        lapCalories = 0;
                        lapHrSum = 0; lapHrCount = 0; lapHrMax = 0;
                        lapCadSum = 0; lapCadCount = 0; lapCadMax = 0;
                        lapPowerMax = 0;
                        prevTime = -1;
                    } else {
                        lapDistance = rowDistance;
                        lapMaxSpeed = Math.max(lapMaxSpeed, rowMaxSpeed);
                    }
                    lastRowTime = rowTime;

                    if (rowHr > 0) {
                        lapHrSum += rowHr;
                        lapHrCount++;
                        if (rowHr > lapHrMax) {
                            lapHrMax = rowHr;
                        }
                    }
                    if (rowCad > 0) {
                        lapCadSum += rowCad;
                        lapCadCount++;
                        if (rowCad > lapCadMax) {
                            lapCadMax = rowCad;
                        }
                    }
                    if (rowPower > 0) {
                        if (rowPower > lapPowerMax) {
                            lapPowerMax = rowPower;
                        }
                    }

                    String time = tcxTime(sdf, rowTime);
                    lapTrack.append("  <Trackpoint>\n    <Time>").append(time).append("</Time>\n    ");
                    if (!cursor.isNull(2) && !cursor.isNull(3)) {
                        lapTrack.append("<Position>\n      <LatitudeDegrees>").append(cursor.getString(2)).append("</LatitudeDegrees>\n      ");
                        lapTrack.append("<LongitudeDegrees>").append(cursor.getString(3)).append("</LongitudeDegrees>\n    </Position>\n");
                    }
                    if (!cursor.isNull(4)) {
                        lapTrack.append("    <AltitudeMeters>").append(cursor.getString(4)).append("</AltitudeMeters>\n");
                    }
                    if (!cursor.isNull(14)) {
                        lapTrack.append("    <DistanceMeters>").append(cursor.getString(14)).append("</DistanceMeters>\n");
                    }
                    if (!cursor.isNull(10)) {
                        lapTrack.append("    <HeartRateBpm><Value>").append(cursor.getString(10)).append("</Value></HeartRateBpm>\n");
                    }
                    if (!cursor.isNull(11)) {
                        lapTrack.append("    <Cadence>").append(cursor.getString(11)).append("</Cadence>\n");
                    }
                    int tier = EnergyModel.tierFor(profile);
                    lapCalories += EnergyModel.intervalCalories(tier, profile, prevTime, rowTime,
                            cursor.isNull(10) ? -1 : safeInt(cursor.getString(10), -1),
                            cursor.isNull(13) ? 0.0 : Math.abs(safeFloat(cursor.getString(13), 0f)) * 3.6f);
                    lapCalories = Math.min(lapCalories, EnergyModel.MAX_CALORIES);
                    prevTime = rowTime;
                    boolean hasSpeed = !cursor.isNull(13);
                    boolean hasPower = !cursor.isNull(12);
                    boolean hasCalories = profile != null && lapCalories > 0;
                    if (hasSpeed || hasPower || hasCalories) {
                        lapTrack.append("    <Extensions>\n      <ns3:TPX>\n");
                        if (hasSpeed) {
                            lapTrack.append("        <ns3:Speed>").append(cursor.getString(13)).append("</ns3:Speed>\n");
                        }
                        if (hasPower) {
                            lapTrack.append("        <ns3:Watts>").append(cursor.getString(12)).append("</ns3:Watts>\n");
                        }
                        if (hasCalories) {
                            lapTrack.append("        <ns3:Calories>").append(lapCalories).append("</ns3:Calories>\n");
                        }
                        lapTrack.append("      </ns3:TPX>\n    </Extensions>\n");
                    }
                    lapTrack.append("  </Trackpoint>\n");
                } while (cursor.moveToNext());

                tcx.append(lapXml(sdf, lapStartTime, lastRowTime, lapDistance - distanceBeforeLap,
                        lapMaxSpeed, lapCalories,
                        (lapHrCount > 0) ? (int) Math.round((double) lapHrSum / (double) lapHrCount) : 0,
                        lapHrMax,
                        (lapCadCount > 0) ? (int) Math.round((double) lapCadSum / (double) lapCadCount) : 0,
                        lapCadMax, lapPowerMax, lapTrack));
                tcx.append("</Activity>\n</Activities>\n");
            }
        } finally {
            cursor.close();
        }

        tcx.append("</TrainingCenterDatabase>");
        return tcx.toString();
    }

    public static String getGPX(SQLiteDatabase db, EnergyModel.Profile profile, Context ctx, boolean extended) {
        StringBuilder gpx = new StringBuilder();
        String creator = "JayPS";
        if (ctx != null) {
            SensorManager mSensorManager = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
            if (mSensorManager != null && mSensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE) != null) {
                creator += " with Barometer";
            }
        }
        gpx.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n"
                + "<gpx xmlns=\"http://www.topografix.com/GPX/1/1\" xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" creator=\"" + creator + "\" version=\"1.1\" xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd  http://www.garmin.com/xmlschemas/TrackPointExtensionv1.xsd\" xmlns:pb10=\"http://www.pebblebike.com/GPX/1/0/\">\n");

        String selectQuery = "SELECT _ID, loca_time, loca_lat, loca_lon, loca_altitude, loca_accuracy, loca_comment, loca_ascent, loca_gps_altitude, loca_pressure_altitude, loca_hr, loca_cad, loca_power, loca_speed, loca_lap FROM " + AdvancedLocationDbHelper.Location.TABLE_NAME + " ORDER BY _ID ASC";
        Cursor cursor = db.rawQuery(selectQuery, null);
        int trackNumber = 1;
        try {
            if (cursor.moveToFirst()) {
                gpx.append("<trk>\n<name>Track #1</name>\n<trkseg>\n");
                long prevTime = -1;
                int cumulativeCalories = 0;
                int currentLap = lapIndex(cursor, GPX_LAP_COLUMN);
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");
                do {
                    if (prevTime > 0 && Long.parseLong(cursor.getString(1)) - prevTime > 12 * 3600 * 1000) {
                        trackNumber++;
                        gpx.append("</trkseg>\n</trk>\n<trk>\n<name>Track #" + trackNumber + "</name>\n<trkseg>\n");
                        cumulativeCalories = 0;
                    } else if (prevTime > 0 && Long.parseLong(cursor.getString(1)) - prevTime > 2 * 3600 * 1000) {
                        gpx.append("</trkseg>\n<trkseg>\n");
                        cumulativeCalories = 0;
                    }
                    int rowLap = lapIndex(cursor, GPX_LAP_COLUMN);
                    if (rowLap != currentLap) {
                        gpx.append("</trkseg>\n<trkseg>\n");
                        cumulativeCalories = 0;
                        currentLap = rowLap;
                    }
                    gpx.append("<trkpt");
                    if (!cursor.isNull(2) && !cursor.isNull(3)) {
                        gpx.append(" lat=\"").append(cursor.getString(2)).append("\" lon=\"").append(cursor.getString(3)).append("\"");
                    }
                    gpx.append(">\n");
                    gpx.append("  <time>").append(tcxTime(sdf, Long.parseLong(cursor.getString(1)))).append("</time>\n");
                    if (extended) {
                        if (!cursor.isNull(8)) {
                            gpx.append("  <ele>").append(cursor.getString(8)).append("</ele>\n");
                        }
                        if (!cursor.isNull(4)) {
                            gpx.append("  <pb10:altitude>").append(cursor.getString(4)).append("</pb10:altitude>\n");
                        }
                        if (!cursor.isNull(9)) {
                            gpx.append("  <pb10:altitude_pressure>").append(cursor.getString(9)).append("</pb10:altitude_pressure>\n");
                        }
                        if (!cursor.isNull(7)) {
                            gpx.append("  <pb10:ascent>").append(cursor.getString(7)).append("</pb10:ascent>\n");
                        }
                        if (!cursor.isNull(5)) {
                            gpx.append("  <pb10:accuracy>").append(cursor.getString(5)).append("</pb10:accuracy>\n");
                        }
                    } else {
                        if (!cursor.isNull(4)) {
                            gpx.append("  <ele>").append(cursor.getString(4)).append("</ele>\n");
                        }
                    }
                    gpx.append("  <extensions>\n");
                    if (!cursor.isNull(13) && Float.parseFloat(cursor.getString(13)) > 0) {
                        gpx.append("    <gpxtpx:TrackPointExtension>\n      <gpxtpx:speed>").append(cursor.getString(13)).append("</gpxtpx:speed>\n");
                        if (profile != null) {
                            int tier = EnergyModel.tierFor(profile);
                            int calories = EnergyModel.intervalCalories(tier, profile, prevTime, Long.parseLong(cursor.getString(1)),
                                    cursor.isNull(10) ? -1 : safeInt(cursor.getString(10), -1),
                                    cursor.isNull(13) ? 0.0 : Math.abs(safeFloat(cursor.getString(13), 0f)) * 3.6f);
                            if (calories > 0) {
                                cumulativeCalories += calories;
                                cumulativeCalories = Math.min(cumulativeCalories, EnergyModel.MAX_CALORIES);
                                gpx.append("      <gpxtpx:hr>").append(cumulativeCalories).append("</gpxtpx:hr>\n");
                            }
                        }
                        if (!cursor.isNull(10)) {
                            gpx.append("      <gpxtpx:hr>").append(cursor.getString(10)).append("</gpxtpx:hr>\n");
                        }
                        if (!cursor.isNull(11)) {
                            gpx.append("      <gpxtpx:cad>").append(cursor.getString(11)).append("</gpxtpx:cad>\n");
                        }
                        if (!cursor.isNull(12)) {
                            gpx.append("      <gpxtpx:watts>").append(cursor.getString(12)).append("</gpxtpx:watts>\n");
                        }
                        gpx.append("    </gpxtpx:TrackPointExtension>\n");
                    }
                    gpx.append("  </extensions>\n");
                    gpx.append("</trkpt>\n");
                    prevTime = Long.parseLong(cursor.getString(1));
                } while (cursor.moveToNext());
                gpx.append("</trkseg>\n</trk>\n");
            }
        } finally {
            cursor.close();
        }
        gpx.append("</gpx>");
        return gpx.toString();
    }

    public static String toJSON(SQLiteDatabase db, Context ctx, EnergyModel.Profile profile, String type, String notes) {
        StringBuilder json = new StringBuilder();
        json.append("{\"type\": \"" + type + "\", \"notes\": \"" + notes + "\", \"duration\": " + 0 + ",");
        String selectQuery = "SELECT _ID, loca_time, loca_lat, loca_lon, loca_altitude, loca_accuracy, loca_comment, loca_ascent, loca_gps_altitude, loca_pressure_altitude, loca_hr, loca_cad FROM " + AdvancedLocationDbHelper.Location.TABLE_NAME + " ORDER BY _ID ASC";
        Cursor cursor = db.rawQuery(selectQuery, null);
        try {
            if (cursor.moveToFirst()) {
                long firstTime = -1;
                String buffer = "";
                do {
                    if (!buffer.isEmpty()) {
                        buffer += ", \"type\": \"gps\"}";
                        json.append("," + buffer);
                    }
                    long deltaTime = firstTime < 0 ? 0 : (Long.parseLong(cursor.getString(1)) - firstTime);
                    buffer = "{\"timestamp\": " + (deltaTime / 1000) + ",\"altitude\": " + cursor.getString(4) + ",\"longitude\":" + cursor.getString(3) + ",\"latitude\":" + cursor.getString(2);
                    if (firstTime < 0) {
                        firstTime = Long.parseLong(cursor.getString(1));
                        Date netDate = (new Date(firstTime));
                        SimpleDateFormat sdf = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss", Locale.ENGLISH);
                        String time = sdf.format(netDate);
                        json.append("\"start_time\": \"" + time + "\", \"path\": [");
                        buffer += ", \"type\": \"start\"}";
                        json.append(buffer);
                        buffer = "";
                    }
                } while (cursor.moveToNext());
                if (!buffer.isEmpty()) {
                    json.append("," + buffer + "]}");
                } else {
                    json.append("]}");
                }
            } else {
                json.append("]}");
            }
        } finally {
            cursor.close();
        }
        return json.toString();
    }

    public static float speedKmhFromCursor(Cursor cursor, int columnIndex) {
        if (cursor.isNull(columnIndex)) {
            return 0f;
        }
        try {
            float speedMs = Float.parseFloat(cursor.getString(columnIndex));
            return speedMs * 3.6f;
        } catch (Exception e) {
            return 0f;
        }
    }
}
