package io.github.lihuu.mitmkit.core;

import io.github.lihuu.mitmkit.core.FlowRecorder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class FlowRecorderTest {

    @Test
    void recordsJsonLines() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        FlowRecorder recorder = new FlowRecorder(new PrintWriter(buffer, true, StandardCharsets.UTF_8),
                true, false);

        FlowRecorder.Flow flow = new FlowRecorder.Flow(1, "GET", "https://example.test/path",
                200, 12, 100, 2048, null, null, null, null, null);
        recorder.record(flow);

        String output = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("\"id\":1"), output);
        assertTrue(output.contains("\"ts\":\"20"), "ts must be a quoted ISO-8601 string: " + output);
        assertTrue(output.contains("\"method\":\"GET\""), output);
        assertTrue(output.contains("\"url\":\"https://example.test/path\""), output);
        assertTrue(output.contains("\"status\":200"), output);
        assertTrue(output.contains("\"durationMs\":12"), output);
        assertFalse(output.contains("\"reqBody\""), "body fields must be absent without --body");
        assertTrue(output.endsWith("\n"), "each flow is one line");
    }

    @Test
    void recordsHumanReadableLines() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        FlowRecorder recorder = new FlowRecorder(new PrintWriter(buffer, true, StandardCharsets.UTF_8),
                false, false);

        FlowRecorder.Flow flow = new FlowRecorder.Flow(2, "POST", "https://example.test/api",
                201, 34, 512, 128, null, null, null, null, null);
        recorder.record(flow);

        String output = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("POST"), output);
        assertTrue(output.contains("https://example.test/api"), output);
        assertTrue(output.contains("201"), output);
        assertTrue(output.contains("34ms"), output);
    }

    @Test
    void recordsErrorFlow() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        FlowRecorder recorder = new FlowRecorder(new PrintWriter(buffer, true, StandardCharsets.UTF_8),
                true, false);

        FlowRecorder.Flow flow = new FlowRecorder.Flow(3, "CONNECT", "example.test:443",
                null, 5, 0, 0, null, null, null, null, "upstream unreachable");
        recorder.record(flow);

        String output = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("\"status\":null"), output);
        assertTrue(output.contains("\"error\":\"upstream unreachable\""), output);
    }
}
