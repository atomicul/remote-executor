package dev.executor.common;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfigTest {

    @Test
    void getIntResolvesFromParameterMap() {
        var config = new Config(Map.of("poll-interval", "30"));

        assertEquals(30, config.getInt("poll-interval", 10));
    }

    @Test
    void getIntFallsBackToDefaultWhenKeyMissing() {
        var config = new Config(Map.of());

        assertEquals(10, config.getInt("poll-interval", 10));
    }

    @Test
    void getIntThrowsOnInvalidValue() {
        var config = new Config(Map.of("poll-interval", "not-a-number"));

        assertThrows(NumberFormatException.class,
                () -> config.getInt("poll-interval", 10));
    }

    @Test
    void getStringResolvesFromParameterMap() {
        var config = new Config(Map.of("region", "us-west-2"));

        assertEquals("us-west-2", config.getString("region", "us-east-1"));
    }

    @Test
    void getStringFallsBackToDefaultWhenKeyMissing() {
        var config = new Config(Map.of());

        assertEquals("us-east-1", config.getString("region", "us-east-1"));
    }

    @Test
    void constructorCreatesImmutableCopy() {
        var mutable = new java.util.HashMap<String, String>();
        mutable.put("key", "original");
        var config = new Config(mutable);

        mutable.put("key", "mutated");

        assertEquals("original", config.getString("key", "default"));
    }
}
