package fr.jayps.android;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

public class AdvancedLocationStats {
    public static boolean hasPowerData(SQLiteDatabase db) {
        String q = "SELECT COUNT(*) FROM " + AdvancedLocationDbHelper.Location.TABLE_NAME
                + " WHERE loca_power IS NOT NULL AND loca_power > 0";
        Cursor c = db.rawQuery(q, null);
        boolean has = c.moveToFirst() && c.getInt(0) > 0;
        c.close();
        return has;
    }

    public static int getAvgPower(SQLiteDatabase db, int seconds) {
        java.util.Date date = new java.util.Date();
        long timeMilli = date.getTime() - ((long) seconds * 1000L);
        String time = String.format("%d", timeMilli);
        String selectQuery = "SELECT loca_power from " + AdvancedLocationDbHelper.Location.TABLE_NAME;
        if (seconds > 0) {
            selectQuery += " WHERE loca_time >= " + time;
        }
        Cursor cursor = db.rawQuery(selectQuery, null);
        int count = 0;
        int sum = 0;
        double avg = 0.0;
        try {
            if (cursor.moveToFirst()) {
                do {
                    count++;
                    try {
                        sum += Integer.parseInt(cursor.getString(0));
                    } catch (NumberFormatException e) {
                        // ignore
                    }
                } while (cursor.moveToNext());
                if (count > 0) {
                    avg = (double) sum / (double) count;
                }
            }
        } finally {
            cursor.close();
        }
        return (int) Math.round(avg);
    }

    public static int getNormalizedPower(SQLiteDatabase db, int seconds) {
        java.util.Date date = new java.util.Date();
        long timeMilli = date.getTime() - ((long) seconds * 1000L);
        String time = String.format("%d", timeMilli);
        String selectQuery = "SELECT loca_power from " + AdvancedLocationDbHelper.Location.TABLE_NAME + " WHERE loca_time >= " + time;
        Cursor cursor = db.rawQuery(selectQuery, null);
        int count = 0;
        double sum = 0;
        double avg = 0.0;
        try {
            if (cursor.moveToFirst()) {
                do {
                    count++;
                    try {
                        sum += Math.pow(Integer.parseInt(cursor.getString(0)), 4);
                    } catch (NumberFormatException e) {
                        // ignore
                    }
                } while (cursor.moveToNext());
                if (count > 0) {
                    avg = sum / (double) count;
                }
            }
        } finally {
            cursor.close();
        }
        double np = Math.pow(avg, 1.0 / 4.0);
        return (int) Math.round(np);
    }
}
