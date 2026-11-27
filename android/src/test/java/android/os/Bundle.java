package android.os;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Map-backed stand-in for the host-side android.jar stub of {@link Bundle},
 * whose every method throws "not mocked" in local unit tests.
 *
 * <p>Needed because AndroidX Navigation's {@code SavedStateHandle.toRoute()}
 * (called by ViewModel constructors, e.g. PreviewViewModel) round-trips route
 * arguments through a Bundle. On a device the real Bundle works; on the host
 * JVM the stub makes any ViewModel with a typed route un-constructible in a
 * plain unit test. This class shadows the stub on the unit-test classpath
 * (test classes precede the mockable android.jar) and implements the value
 * accessors as a plain {@link HashMap}. Parcel marshalling is intentionally
 * not supported: nothing in a local unit test parcels a Bundle.
 */
public class Bundle implements Parcelable, Cloneable, Serializable {

    private final HashMap<String, Object> map;

    public Bundle() {
        map = new HashMap<>();
    }

    public Bundle(int capacity) {
        map = new HashMap<>(Math.max(capacity, 1));
    }

    public Bundle(Bundle bundle) {
        map = new HashMap<>(bundle.map);
    }

    public Bundle(ClassLoader loader) {
        map = new HashMap<>();
    }

    // ---- container basics -------------------------------------------------

    public int size() {
        return map.size();
    }

    public boolean isEmpty() {
        return map.isEmpty();
    }

    public void clear() {
        map.clear();
    }

    public boolean containsKey(String key) {
        return map.containsKey(key);
    }

    public void remove(String key) {
        map.remove(key);
    }

    public Set<String> keySet() {
        return map.keySet();
    }

    public Object get(String key) {
        return map.get(key);
    }

    public void putAll(Bundle bundle) {
        map.putAll(bundle.map);
    }

    public void putAll(Map<String, Object> values) {
        map.putAll(values);
    }

    public boolean hasFileDescriptors() {
        return false;
    }

    public void setClassLoader(ClassLoader loader) {
        // No parcelled lazy loading in this stand-in.
    }

    public ClassLoader getClassLoader() {
        return getClass().getClassLoader();
    }

    @Override
    public Bundle clone() {
        return new Bundle(this);
    }

    @Override
    public String toString() {
        return "Bundle" + map.toString();
    }

    // ---- scalars ----------------------------------------------------------

    public void putString(String key, String value) {
        map.put(key, value);
    }

    public String getString(String key) {
        Object v = map.get(key);
        return v instanceof String ? (String) v : null;
    }

    public String getString(String key, String defaultValue) {
        String v = getString(key);
        return v != null ? v : defaultValue;
    }

    public void putCharSequence(String key, CharSequence value) {
        map.put(key, value);
    }

    public CharSequence getCharSequence(String key) {
        Object v = map.get(key);
        return v instanceof CharSequence ? (CharSequence) v : null;
    }

    public CharSequence getCharSequence(String key, CharSequence defaultValue) {
        CharSequence v = getCharSequence(key);
        return v != null ? v : defaultValue;
    }

    public void putBoolean(String key, boolean value) {
        map.put(key, value);
    }

    public boolean getBoolean(String key) {
        return getBoolean(key, false);
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        Object v = map.get(key);
        return v instanceof Boolean ? (Boolean) v : defaultValue;
    }

    public void putInt(String key, int value) {
        map.put(key, value);
    }

    public int getInt(String key) {
        return getInt(key, 0);
    }

    public int getInt(String key, int defaultValue) {
        Object v = map.get(key);
        return v instanceof Integer ? (Integer) v : defaultValue;
    }

    public void putLong(String key, long value) {
        map.put(key, value);
    }

    public long getLong(String key) {
        return getLong(key, 0L);
    }

    public long getLong(String key, long defaultValue) {
        Object v = map.get(key);
        return v instanceof Long ? (Long) v : defaultValue;
    }

    public void putDouble(String key, double value) {
        map.put(key, value);
    }

    public double getDouble(String key) {
        return getDouble(key, 0.0);
    }

    public double getDouble(String key, double defaultValue) {
        Object v = map.get(key);
        return v instanceof Double ? (Double) v : defaultValue;
    }

    public void putFloat(String key, float value) {
        map.put(key, value);
    }

    public float getFloat(String key) {
        return getFloat(key, 0f);
    }

    public float getFloat(String key, float defaultValue) {
        Object v = map.get(key);
        return v instanceof Float ? (Float) v : defaultValue;
    }

    public void putShort(String key, short value) {
        map.put(key, value);
    }

    public short getShort(String key) {
        Object v = map.get(key);
        return v instanceof Short ? (Short) v : 0;
    }

    public void putByte(String key, byte value) {
        map.put(key, value);
    }

    public byte getByte(String key) {
        Object v = map.get(key);
        return v instanceof Byte ? (Byte) v : 0;
    }

    public void putChar(String key, char value) {
        map.put(key, value);
    }

    public char getChar(String key) {
        Object v = map.get(key);
        return v instanceof Character ? (Character) v : 0;
    }

    // ---- arrays and lists ---------------------------------------------------

    public void putStringArray(String key, String[] value) {
        map.put(key, value);
    }

    public String[] getStringArray(String key) {
        Object v = map.get(key);
        return v instanceof String[] ? (String[]) v : null;
    }

    public void putCharSequenceArray(String key, CharSequence[] value) {
        map.put(key, value);
    }

    public CharSequence[] getCharSequenceArray(String key) {
        Object v = map.get(key);
        return v instanceof CharSequence[] ? (CharSequence[]) v : null;
    }

    public void putIntArray(String key, int[] value) {
        map.put(key, value);
    }

    public int[] getIntArray(String key) {
        Object v = map.get(key);
        return v instanceof int[] ? (int[]) v : null;
    }

    public void putLongArray(String key, long[] value) {
        map.put(key, value);
    }

    public long[] getLongArray(String key) {
        Object v = map.get(key);
        return v instanceof long[] ? (long[]) v : null;
    }

    public void putBooleanArray(String key, boolean[] value) {
        map.put(key, value);
    }

    public boolean[] getBooleanArray(String key) {
        Object v = map.get(key);
        return v instanceof boolean[] ? (boolean[]) v : null;
    }

    public void putStringArrayList(String key, ArrayList<String> value) {
        map.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public ArrayList<String> getStringArrayList(String key) {
        Object v = map.get(key);
        return v instanceof ArrayList ? (ArrayList<String>) v : null;
    }

    public void putCharSequenceArrayList(String key, ArrayList<CharSequence> value) {
        map.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public ArrayList<CharSequence> getCharSequenceArrayList(String key) {
        Object v = map.get(key);
        return v instanceof ArrayList ? (ArrayList<CharSequence>) v : null;
    }

    public void putIntegerArrayList(String key, ArrayList<Integer> value) {
        map.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public ArrayList<Integer> getIntegerArrayList(String key) {
        Object v = map.get(key);
        return v instanceof ArrayList ? (ArrayList<Integer>) v : null;
    }

    // ---- parcelable / serializable / nested --------------------------------

    public void putParcelable(String key, Parcelable value) {
        map.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T extends Parcelable> T getParcelable(String key) {
        Object v = map.get(key);
        return v instanceof Parcelable ? (T) v : null;
    }

    public void putParcelableArray(String key, Parcelable[] value) {
        map.put(key, value);
    }

    public Parcelable[] getParcelableArray(String key) {
        Object v = map.get(key);
        return v instanceof Parcelable[] ? (Parcelable[]) v : null;
    }

    public void putParcelableArrayList(String key, ArrayList<? extends Parcelable> value) {
        map.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T extends Parcelable> ArrayList<T> getParcelableArrayList(String key) {
        Object v = map.get(key);
        return v instanceof ArrayList ? (ArrayList<T>) v : null;
    }

    public void putSerializable(String key, Serializable value) {
        map.put(key, value);
    }

    public Serializable getSerializable(String key) {
        Object v = map.get(key);
        return v instanceof Serializable ? (Serializable) v : null;
    }

    public void putBundle(String key, Bundle value) {
        map.put(key, value);
    }

    public Bundle getBundle(String key) {
        Object v = map.get(key);
        return v instanceof Bundle ? (Bundle) v : null;
    }

    // ---- Parcelable contract (never exercised in unit tests) ----------------

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        throw new UnsupportedOperationException(
                "The unit-test Bundle stand-in does not support parcelling");
    }

    public void readFromParcel(Parcel source) {
        throw new UnsupportedOperationException(
                "The unit-test Bundle stand-in does not support parcelling");
    }

    public static final Creator<Bundle> CREATOR = new Creator<Bundle>() {
        @Override
        public Bundle createFromParcel(Parcel source) {
            throw new UnsupportedOperationException(
                    "The unit-test Bundle stand-in does not support parcelling");
        }

        @Override
        public Bundle[] newArray(int size) {
            return new Bundle[size];
        }
    };
}
