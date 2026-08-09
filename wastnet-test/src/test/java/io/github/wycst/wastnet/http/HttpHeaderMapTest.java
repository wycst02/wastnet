package io.github.wycst.wastnet.http;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.*;

/**
 * Unit tests for {@link HttpHeaderMap}
 *
 * @author wangyc
 */
public class HttpHeaderMapTest {

    @Test
    public void testEmptyMap() {
        HttpHeaderMap<String, String> map = new HttpHeaderMap<String, String>();
        Assertions.assertTrue(map.isEmpty());
        Assertions.assertEquals(0, map.size());
        Assertions.assertFalse(map.containsKey("key"));
        Assertions.assertFalse(map.containsValue("value"));
    }

    @Test
    public void testPutAndGet() {
        HttpHeaderMap<String, String> map = new HttpHeaderMap<String, String>();
        Assertions.assertNull(map.put("Content-Type", "text/html"));
        Assertions.assertEquals("text/html", map.get("Content-Type"));
        Assertions.assertEquals(1, map.size());
    }

    @Test
    public void testPutOverwrite() {
        HttpHeaderMap<String, String> map = new HttpHeaderMap<String, String>();
        map.put("Content-Type", "text/html");
        String old = map.put("Content-Type", "application/json");
        Assertions.assertEquals("text/html", old);
        Assertions.assertEquals("application/json", map.get("Content-Type"));
        Assertions.assertEquals(1, map.size());
    }

    @Test
    public void testContainsKey() {
        HttpHeaderMap<String, String> map = new HttpHeaderMap<String, String>();
        map.put("Host", "example.com");
        Assertions.assertTrue(map.containsKey("Host"));
        Assertions.assertFalse(map.containsKey("host"));
        Assertions.assertFalse(map.containsKey("X-Custom"));
    }

    @Test
    public void testContainsValue() {
        HttpHeaderMap<String, String> map = new HttpHeaderMap<String, String>();
        map.put("Accept", "text/html");
        Assertions.assertTrue(map.containsValue("text/html"));
        Assertions.assertFalse(map.containsValue("application/json"));
    }

    @Test
    public void testRemove() {
        HttpHeaderMap<String, String> map = new HttpHeaderMap<String, String>();
        map.put("X-Custom", "value1");
        map.put("Accept", "text/html");
        Assertions.assertEquals(2, map.size());

        String removed = map.remove("X-Custom");
        Assertions.assertEquals("value1", removed);
        Assertions.assertEquals(1, map.size());
        Assertions.assertNull(map.get("X-Custom"));
        Assertions.assertEquals("text/html", map.get("Accept"));
    }

    @Test
    public void testRemoveNonExistent() {
        HttpHeaderMap<String, String> map = new HttpHeaderMap<String, String>();
        map.put("Host", "example.com");
        Assertions.assertNull(map.remove("NonExistent"));
        Assertions.assertEquals(1, map.size());
    }

    @Test
    public void testClear() {
        HttpHeaderMap<String, String> map = new HttpHeaderMap<String, String>();
        map.put("Host", "example.com");
        map.put("Accept", "text/html");
        map.clear();
        Assertions.assertTrue(map.isEmpty());
        Assertions.assertEquals(0, map.size());
        Assertions.assertNull(map.get("Host"));
        Assertions.assertNull(map.get("Accept"));
    }

    @Test
    public void testKeySet() {
        HttpHeaderMap<String, String> map = new HttpHeaderMap<String, String>();
        map.put("Host", "example.com");
        map.put("Accept", "text/html");
        map.put("Content-Type", "application/json");

        Set<String> keys = map.keySet();
        Assertions.assertEquals(3, keys.size());
        Assertions.assertTrue(keys.contains("Host"));
        Assertions.assertTrue(keys.contains("Accept"));
        Assertions.assertTrue(keys.contains("Content-Type"));

        // Test iteration order preservation
        Iterator<String> it = keys.iterator();
        Assertions.assertEquals("Host", it.next());
        Assertions.assertEquals("Accept", it.next());
        Assertions.assertEquals("Content-Type", it.next());
    }

    @Test
    public void testKeySetRemove() {
        HttpHeaderMap<String, String> map = new HttpHeaderMap<String, String>();
        map.put("Host", "example.com");
        map.put("Accept", "text/html");

        Iterator<String> it = map.keySet().iterator();
        it.next(); // Host
        it.remove();
        Assertions.assertEquals(1, map.size());
        Assertions.assertNull(map.get("Host"));
        Assertions.assertEquals("text/html", map.get("Accept"));
    }

    @Test
    public void testEntrySet() {
        HttpHeaderMap<String, String> map = new HttpHeaderMap<String, String>();
        map.put("Host", "example.com");
        map.put("Accept", "text/html");

        Set<Map.Entry<String, String>> entries = map.entrySet();
        Assertions.assertEquals(2, entries.size());

        Iterator<Map.Entry<String, String>> it = entries.iterator();
        Map.Entry<String, String> e1 = it.next();
        Assertions.assertEquals("Host", e1.getKey());
        Assertions.assertEquals("example.com", e1.getValue());

        Map.Entry<String, String> e2 = it.next();
        Assertions.assertEquals("Accept", e2.getKey());
        Assertions.assertEquals("text/html", e2.getValue());
    }

    @Test
    public void testEntrySetSetValue() {
        HttpHeaderMap<String, String> map = new HttpHeaderMap<String, String>();
        map.put("Host", "example.com");

        Map.Entry<String, String> entry = map.entrySet().iterator().next();
        String old = entry.setValue("new.example.com");
        Assertions.assertEquals("example.com", old);
        Assertions.assertEquals("new.example.com", map.get("Host"));
    }

    @Test
    public void testValues() {
        HttpHeaderMap<String, String> map = new HttpHeaderMap<String, String>();
        map.put("Host", "example.com");
        map.put("Accept", "text/html");

        Collection<String> values = map.values();
        Assertions.assertEquals(2, values.size());
        Assertions.assertTrue(values.contains("example.com"));
        Assertions.assertTrue(values.contains("text/html"));
    }

    @Test
    public void testPutAll() {
        HttpHeaderMap<String, String> map = new HttpHeaderMap<String, String>();
        Map<String, String> source = new HashMap<String, String>();
        source.put("Host", "example.com");
        source.put("Accept", "text/html");

        map.putAll(source);
        Assertions.assertEquals(2, map.size());
        Assertions.assertEquals("example.com", map.get("Host"));
        Assertions.assertEquals("text/html", map.get("Accept"));
    }

    @Test
    public void testNullKey() {
        HttpHeaderMap<String, String> map = new HttpHeaderMap<String, String>();
        map.put(null, "null-value");
        Assertions.assertTrue(map.containsKey(null));
        Assertions.assertEquals("null-value", map.get(null));
    }

    @Test
    public void testToString() {
        HttpHeaderMap<String, String> map = new HttpHeaderMap<String, String>();
        Assertions.assertEquals("{}", map.toString());

        map.put("Host", "example.com");
        String str = map.toString();
        Assertions.assertTrue(str.contains("Host"));
        Assertions.assertTrue(str.contains("example.com"));
    }

    @Test
    public void testLargeNumberOfEntries() {
        HttpHeaderMap<Integer, Integer> map = new HttpHeaderMap<Integer, Integer>();
        for (int i = 0; i < 100; ++i) {
            map.put(i, i * 2);
        }
        Assertions.assertEquals(100, map.size());
        for (int i = 0; i < 100; ++i) {
            Assertions.assertEquals(Integer.valueOf(i * 2), map.get(i));
        }
    }
}
