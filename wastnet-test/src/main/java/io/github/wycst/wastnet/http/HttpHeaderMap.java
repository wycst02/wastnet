package io.github.wycst.wastnet.http;

import java.util.*;

/**
 * Lightweight Map implementation using parallel arrays for keys and values.
 *
 * <p>Optimized for HTTP headers (small size, 5-20 entries):
 * <ul>
 *   <li>No Entry object overhead - keys[] and values[] arrays only</li>
 *   <li>Linear search O(n) - acceptable for tiny maps</li>
 *   <li>Maintains insertion order</li>
 * </ul>
 *
 * @author wangyc
 */
@SuppressWarnings("all")
final class HttpHeaderMap<K, V> implements Map<K, V> {

    private Object[] keys;
    private Object[] values;
    private int size;
    private int modCount;
    private EntrySet entrySet;
    private KeySet keySet;

    public HttpHeaderMap(int initialCapacity) {
        this.keys = new Object[initialCapacity];
        this.values = new Object[initialCapacity];
    }

    public HttpHeaderMap() {
        this(16);
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public boolean isEmpty() {
        return size == 0;
    }

    @Override
    public boolean containsKey(Object key) {
        return indexOfKey(key) > -1;
    }

    @Override
    public boolean containsValue(Object value) {
        for (int i = 0; i < size; ++i) {
            Object v = values[i];
            if (v == null ? value == null : v.equals(value)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public V get(Object key) {
        int index = indexOfKey(key);
        return index > -1 ? (V) values[index] : null;
    }

    @Override
    public V put(K key, V value) {
        int index = indexOfKey(key);
        if (index > -1) {
            V oldValue = (V) values[index];
            values[index] = value;
            ++modCount;
            return oldValue;
        }
        ensureCapacity();
        keys[size] = key;
        values[size] = value;
        ++size;
        ++modCount;
        return null;
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> m) {
        for (Entry<? extends K, ? extends V> entry : m.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public V remove(Object key) {
        int index = indexOfKey(key);
        if (index == -1) {
            return null;
        }
        V oldValue = (V) values[index];
        int numMoved = size - index - 1;
        if (numMoved > 0) {
            System.arraycopy(keys, index + 1, keys, index, numMoved);
            System.arraycopy(values, index + 1, values, index, numMoved);
        }
        keys[--size] = values[size] = null;
        ++modCount;
        return oldValue;
    }

    @Override
    public void clear() {
        for (int i = 0; i < size; ++i) {
            keys[i] = values[i] = null;
        }
        size = 0;
        ++modCount;
    }

    @Override
    public String toString() {
        if (size == 0) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder((size << 4) + 2);
        sb.append('{');
        for (int i = 0; i < size; ++i) {
            if (i > 0) {
                sb.append(',').append(' ');
            }
            sb.append(keys[i]).append('=').append(values[i]);
        }
        sb.append('}');
        return sb.toString();
    }

    @Override
    public Set<K> keySet() {
        if (keySet == null) {
            keySet = new KeySet();
        }
        return keySet;
    }

    @Override
    public Collection<V> values() {
        return new AbstractSet<V>() {
            @Override
            public Iterator<V> iterator() {
                return new ValueIterator();
            }

            @Override
            public int size() {
                return size;
            }
        };
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
        if (entrySet == null) {
            entrySet = new EntrySet();
        }
        return entrySet;
    }

    private int indexOfKey(Object key) {
        if (key == null) {
            for (int i = 0; i < size; ++i) {
                if (keys[i] == null) {
                    return i;
                }
            }
        } else {
            for (int i = 0; i < size; ++i) {
                if (key.equals(keys[i])) {
                    return i;
                }
            }
        }
        return -1;
    }

    private void ensureCapacity() {
        if (size >= keys.length) {
            int newCapacity = size << 1;
            keys = Arrays.copyOf(keys, newCapacity);
            values = Arrays.copyOf(values, newCapacity);
        }
    }

    // ---- Inner classes ---

    private static class ArrayEntry<K, V> implements Entry<K, V> {
        final HttpHeaderMap<?, ?> map;
        final int idx;

        ArrayEntry(HttpHeaderMap<?, ?> map, int idx) {
            this.map = map;
            this.idx = idx;
        }

        public K getKey() {
            return (K) map.keys[idx];
        }

        public V getValue() {
            return (V) map.values[idx];
        }

        public V setValue(V value) {
            V old = (V) map.values[idx];
            map.values[idx] = value;
            return old;
        }
    }

    private class EntrySet extends AbstractSet<Entry<K, V>> {
        @Override
        public Iterator<Entry<K, V>> iterator() {
            return new EntryIterator();
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public void clear() {
            HttpHeaderMap.this.clear();
        }
    }

    private class KeySet extends AbstractSet<K> {
        @Override
        public Iterator<K> iterator() {
            return new KeyIterator();
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public void clear() {
            HttpHeaderMap.this.clear();
        }
    }

    private class EntryIterator implements Iterator<Entry<K, V>> {
        private int index = 0;
        private int lastReturned = -1;
        private int expectedModCount = modCount;

        @Override
        public boolean hasNext() {
            return index < size;
        }

        @Override
        public Entry<K, V> next() {
            if (modCount != expectedModCount) {
                throw new ConcurrentModificationException();
            }
            if (index >= size) {
                throw new NoSuchElementException();
            }
            lastReturned = index;
            return new ArrayEntry<K, V>(HttpHeaderMap.this, index++);
        }

        @Override
        public void remove() {
            if (modCount != expectedModCount) {
                throw new ConcurrentModificationException();
            }
            if (lastReturned == -1) {
                throw new IllegalStateException();
            }
            int numMoved = size - lastReturned - 1;
            if (numMoved > 0) {
                System.arraycopy(keys, lastReturned + 1, keys, lastReturned, numMoved);
                System.arraycopy(values, lastReturned + 1, values, lastReturned, numMoved);
            }
            keys[--size] = values[size] = null;
            --index;
            lastReturned = -1;
            ++expectedModCount;
            ++modCount;
        }
    }

    private class KeyIterator implements Iterator<K> {
        private int index = 0;
        private int lastReturned = -1;
        private int expectedModCount = modCount;

        @Override
        public boolean hasNext() {
            return index < size;
        }

        @Override
        public K next() {
            if (modCount != expectedModCount) {
                throw new ConcurrentModificationException();
            }
            if (index >= size) {
                throw new NoSuchElementException();
            }
            lastReturned = index;
            return (K) keys[index++];
        }

        @Override
        public void remove() {
            if (modCount != expectedModCount) {
                throw new ConcurrentModificationException();
            }
            if (lastReturned == -1) {
                throw new IllegalStateException();
            }
            int numMoved = size - lastReturned - 1;
            if (numMoved > 0) {
                System.arraycopy(keys, lastReturned + 1, keys, lastReturned, numMoved);
                System.arraycopy(values, lastReturned + 1, values, lastReturned, numMoved);
            }
            keys[--size] = values[size] = null;
            --index;
            lastReturned = -1;
            ++expectedModCount;
            ++modCount;
        }
    }

    private class ValueIterator implements Iterator<V> {
        private int index = 0;
        private int lastReturned = -1;
        private int expectedModCount = modCount;

        @Override
        public boolean hasNext() {
            return index < size;
        }

        @Override
        public V next() {
            if (modCount != expectedModCount) {
                throw new ConcurrentModificationException();
            }
            if (index >= size) {
                throw new NoSuchElementException();
            }
            lastReturned = index;
            return (V) values[index++];
        }

        @Override
        public void remove() {
            if (modCount != expectedModCount) {
                throw new ConcurrentModificationException();
            }
            if (lastReturned == -1) {
                throw new IllegalStateException();
            }
            int numMoved = size - lastReturned - 1;
            if (numMoved > 0) {
                System.arraycopy(keys, lastReturned + 1, keys, lastReturned, numMoved);
                System.arraycopy(values, lastReturned + 1, values, lastReturned, numMoved);
            }
            keys[--size] = values[size] = null;
            --index;
            lastReturned = -1;
            ++expectedModCount;
            ++modCount;
        }
    }
}
