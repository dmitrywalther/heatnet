package ru.lct.heatnet.profile;

import ru.lct.heatnet.report.ConnectionPdfWriter;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;

/** Builds a document from the same immutable scene as the connection card and profile. */
public final class ConnectionPassport {
    private ConnectionPassport() {}

    public static void write(OutputStream out, ProfileScene scene, String variant, String oksKey) throws IOException {
        if (oksKey == null || oksKey.isBlank()) throw new IllegalArgumentException("Укажите ключ ОКС");
        Map<String, Object> card = scene.connection(oksKey);
        Map<String, Object> profile = variant.startsWith("d") && "connected".equals(card.get("status"))
                ? scene.profile(oksKey) : null;
        ConnectionPdfWriter.write(out, card, profile);
    }
}
