package dev.executor.common;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParametersByPathRequest;
import software.amazon.awssdk.services.ssm.model.Parameter;

public class Config implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(Config.class);
    private static final int REFRESH_MINUTES = 5;

    private volatile Map<String, String> parameters;
    private final SsmClient ssm;
    private final String ssmPrefix;
    private final ScheduledExecutorService refresher;

    public Config(Map<String, String> parameters) {
        this.parameters = Map.copyOf(parameters);
        this.ssm = null;
        this.ssmPrefix = null;
        this.refresher = null;
    }

    public Config(String ssmPrefix) {
        this.ssmPrefix = ssmPrefix;
        this.ssm = SsmClient.create();
        this.parameters = loadSsmParameters();
        this.refresher = Executors.newSingleThreadScheduledExecutor();
        refresher.scheduleAtFixedRate(this::refresh, REFRESH_MINUTES, REFRESH_MINUTES, TimeUnit.MINUTES);
    }

    @Override
    public void close() {
        if (refresher != null) {
            refresher.shutdown();
        }
        if (ssm != null) {
            ssm.close();
        }
    }

    public String getString(String key, String defaultValue) {
        String value = resolve(key);
        if (value != null) {
            return value;
        }
        logger.debug("Config '{}': using default '{}'", key, defaultValue);
        return defaultValue;
    }

    public int getInt(String key, int defaultValue) {
        String value = resolve(key);
        if (value != null) {
            return Integer.parseInt(value);
        }
        logger.debug("Config '{}': using default {}", key, defaultValue);
        return defaultValue;
    }

    private String resolve(String key) {
        String envVar = key.replace('-', '_').toUpperCase();
        String env = System.getenv(envVar);
        if (env != null) {
            logger.debug("Config '{}': resolved from env var '{}'", key, envVar);
            return env;
        }
        String param = parameters.get(key);
        if (param != null) {
            logger.debug("Config '{}': resolved from parameter store", key);
            return param;
        }
        return null;
    }

    private void refresh() {
        try {
            parameters = loadSsmParameters();
        } catch (Exception e) {
            logger.error("Failed to refresh config from SSM", e);
        }
    }

    private Map<String, String> loadSsmParameters() {
        logger.info("Loading SSM parameters from {}", ssmPrefix);
        Map<String, String> params = new HashMap<>();
        var request = GetParametersByPathRequest.builder()
                .path(ssmPrefix)
                .recursive(true)
                .build();
        for (var page : ssm.getParametersByPathPaginator(request)) {
            for (Parameter p : page.parameters()) {
                String key = p.name().substring(ssmPrefix.length());
                params.put(key, p.value());
                logger.info("Loaded SSM parameter: {} = {}", key, p.value());
            }
        }
        logger.info("Loaded {} SSM parameter(s)", params.size());
        return Map.copyOf(params);
    }
}
