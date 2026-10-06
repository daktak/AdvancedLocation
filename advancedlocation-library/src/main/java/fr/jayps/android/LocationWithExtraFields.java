package fr.jayps.android;

import android.location.Location;
import android.os.Bundle;

public class LocationWithExtraFields extends Location {
    public LocationWithExtraFields(String provider) {
        super(provider);
    }

    public LocationWithExtraFields(Location l) {
        super(l);
    }

    private double _altitude2;
    private double _altitudeFromGps;
    private double _altitudeFromPressure;

    @Override
    public void setAltitude(double altitude) {
        super.setAltitude(altitude);
    }

    @Override
    public double getAltitude() {
        return super.getAltitude();
    }

    public void setAltitude2(double altitude) {
        _altitude2 = altitude;
    }

    public double getAltitude2() {
        return _altitude2;
    }

    public void setAltitudeFromGps(double altitude) {
        _altitudeFromGps = altitude;
    }

    public double getAltitudeFromGps() {
        return _altitudeFromGps;
    }

    public void setAltitudeFromPressure(double altitude) {
        _altitudeFromPressure = altitude;
    }

    public double getAltitudeFromPressure() {
        return _altitudeFromPressure;
    }

    public float getAltitudeAccuracy() {
        Bundle extras = getExtras();
        if (extras != null) {
            return extras.getFloat("satellites_altitude_accuracy");
        }
        return 0.0f;
    }
}
