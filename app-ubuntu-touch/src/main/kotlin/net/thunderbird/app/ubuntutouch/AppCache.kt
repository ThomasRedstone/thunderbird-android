package net.thunderbird.app.ubuntutouch

import java.nio.file.Files
import java.nio.file.Path

// Click does not permit executing host mkdir. Initialise the confined temp path
// in Java before AWT caches it or the demo backend starts decoding MIME parts.
internal fun prepareCache(directory: Path = Path.of(System.getProperty("java.io.tmpdir"))) {
    Files.createDirectories(directory)
}
