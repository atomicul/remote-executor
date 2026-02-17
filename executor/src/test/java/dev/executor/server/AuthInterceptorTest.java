package dev.executor.server;

import static org.junit.jupiter.api.Assertions.*;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthInterceptorTest {

    private AuthInterceptor interceptor;
    private RecordingServerCall<Object, Object> call;
    private boolean handlerInvoked;

    @BeforeEach
    void setUp() {
        interceptor = new AuthInterceptor("test-secret");
        call = new RecordingServerCall<>();
        handlerInvoked = false;
    }

    private ServerCallHandler<Object, Object> trackingHandler() {
        return (c, headers) -> {
            handlerInvoked = true;
            return new ServerCall.Listener<>() {};
        };
    }

    @Test
    void rejectsRequestWithNoAuthHeader() {
        interceptor.interceptCall(call, new Metadata(), trackingHandler());

        assertEquals(Status.UNAUTHENTICATED.getCode(), call.closedStatus.getCode());
        assertFalse(handlerInvoked);
    }

    @Test
    void rejectsRequestWithWrongToken() {
        var headers = new Metadata();
        headers.put(
                Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER),
                "Bearer wrong-secret");

        interceptor.interceptCall(call, headers, trackingHandler());

        assertEquals(Status.UNAUTHENTICATED.getCode(), call.closedStatus.getCode());
        assertFalse(handlerInvoked);
    }

    @Test
    void rejectsRequestWithMalformedHeader() {
        var headers = new Metadata();
        headers.put(
                Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER),
                "test-secret");

        interceptor.interceptCall(call, headers, trackingHandler());

        assertEquals(Status.UNAUTHENTICATED.getCode(), call.closedStatus.getCode());
        assertFalse(handlerInvoked);
    }

    @Test
    void allowsRequestWithValidToken() {
        var headers = new Metadata();
        headers.put(
                Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER),
                "Bearer test-secret");

        interceptor.interceptCall(call, headers, trackingHandler());

        assertNull(call.closedStatus);
        assertTrue(handlerInvoked);
    }

    private static class RecordingServerCall<ReqT, RespT> extends ServerCall<ReqT, RespT> {
        Status closedStatus;

        @Override
        public void close(Status status, Metadata trailers) {
            closedStatus = status;
        }

        @Override public void request(int numMessages) {}
        @Override public void sendHeaders(Metadata headers) {}
        @Override public void sendMessage(RespT message) {}
        @Override public boolean isCancelled() { return false; }
        @Override public io.grpc.MethodDescriptor<ReqT, RespT> getMethodDescriptor() { return null; }
    }
}
