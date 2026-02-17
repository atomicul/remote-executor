package dev.executor.scaleout;

import com.google.gson.Gson;
import java.util.Map;

public record CommandPayload(
        String jobId,
        String instanceId,
        String command,
        int memoryLimitMb,
        float cpuLimit,
        Map<String, String> envVars) {

    private static final Gson GSON = new Gson();

    public static CommandPayload fromJson(String json) {
        return GSON.fromJson(json, CommandPayload.class);
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    public CommandPayload withInstanceId(String instanceId) {
        return new CommandPayload(jobId, instanceId, command, memoryLimitMb, cpuLimit, envVars);
    }
}
