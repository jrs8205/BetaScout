package android.os;

/** JVM stub for android.os.Parcel. Present for classloading of Parcelable
 *  models; the harvester never parcels objects, so methods are no-ops. */
public final class Parcel {
    public static Parcel obtain() { return new Parcel(); }

    public void recycle() {}
    public void writeString(String value) {}
    public String readString() { return null; }
    public void writeInt(int value) {}
    public int readInt() { return 0; }
    public void writeLong(long value) {}
    public long readLong() { return 0L; }
    public void writeByte(byte value) {}
    public byte readByte() { return 0; }
    public void writeFloat(float value) {}
    public float readFloat() { return 0f; }
    public void writeDouble(double value) {}
    public double readDouble() { return 0.0; }
}
