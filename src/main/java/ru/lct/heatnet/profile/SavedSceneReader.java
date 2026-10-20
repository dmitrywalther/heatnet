package ru.lct.heatnet.profile;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.util.JsonParserDelegate;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import ru.lct.heatnet.geo.GeoJsonReader;
import ru.lct.heatnet.geo.Projection;
import ru.lct.heatnet.model.InputModel;
import ru.lct.heatnet.refdata.RestrictionRules;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Shared exact-number reader for the web views and offline export of persisted results. */
public final class SavedSceneReader {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));

    private SavedSceneReader() {}

    public static ProfileScene read(Path input, Path result, RestrictionRules rules, String variant,
                                    boolean depthMode) throws IOException {
        InputModel model;
        try (InputStream in = Files.newInputStream(input)) {
            model = new GeoJsonReader(new Projection()).read(in);
        }
        // Match GeoJsonReader: probing isNaN must not round finite 1e400 numeric IDs through double.
        try (InputStream in = Files.newInputStream(result);
             JsonParser parser = new JsonParserDelegate(MAPPER.getFactory().createParser(in)) {
                 @Override public boolean isNaN() { return false; }
             }) {
            return new ProfileScene(model, MAPPER.readTree(parser), rules, variant, depthMode);
        }
    }
}
