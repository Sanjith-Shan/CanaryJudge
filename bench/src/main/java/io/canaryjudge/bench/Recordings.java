package io.canaryjudge.bench;

import io.canaryjudge.core.config.CanaryConfig;
import io.canaryjudge.core.model.Recording;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Reads recorded canary runs, one JSON object per line. */
public final class Recordings {
    private Recordings() {}

    public static List<Recording> read(Path path) throws IOException {
        List<Recording> out = new ArrayList<>();
        for (String line : Files.readAllLines(path)) {
            if (line.isBlank()) continue;
            out.add(CanaryConfig.MAPPER.readValue(line, Recording.class));
        }
        return out;
    }
}
