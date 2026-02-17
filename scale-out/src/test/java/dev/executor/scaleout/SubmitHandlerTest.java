package dev.executor.scaleout;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.ec2.model.Instance;

class SubmitHandlerTest {

    @Test
    void pickIpPrefersPublic() {
        var instance = Instance.builder()
                .publicIpAddress("1.2.3.4")
                .privateIpAddress("10.0.0.1")
                .build();

        assertEquals("1.2.3.4", SubmitHandler.pickIp(instance));
    }

    @Test
    void pickIpFallsBackToPrivate() {
        var instance = Instance.builder()
                .privateIpAddress("10.0.0.1")
                .build();

        assertEquals("10.0.0.1", SubmitHandler.pickIp(instance));
    }
}
