package android.location;

public class Location {
    private double latitude = 40.0;
    private double longitude = -105.0;
    private boolean altitudeSet;
    private double altitude;
    public Location(String provider) {}
    public long getElapsedRealtimeNanos() { return 100000000000L; }
    public long getTime() { return 1700000000000L; }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
    public void setLatitude(double latitude) { this.latitude = latitude; }
    public void setLongitude(double longitude) { this.longitude = longitude; }
    public boolean hasAccuracy() { return true; }
    public float getAccuracy() { return 4; }
    public boolean hasAltitude() { return altitudeSet; }
    public double getAltitude() { return altitude; }
    public void setAltitude(double altitude) { this.altitude = altitude; altitudeSet = true; }
    public boolean hasBearing() { return false; }
    public float getBearing() { return 0; }
    public boolean hasSpeed() { return false; }
    public float getSpeed() { return 0; }
}
