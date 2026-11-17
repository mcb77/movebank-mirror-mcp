package de.firetail.compat.movebank.mirror.mcp;

import de.firetail.compat.movebank.mirror.reader.MirrorReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;

/**
 * The mirror directory the tools read, from {@code movebank.mirror.dir}
 * (environment variable {@code MOVEBANK_MIRROR_DIR}).
 * <p>A missing or wrong setting is reported by each tool call instead of
 * failing at startup: in stdio mode a failed start only shows up in the MCP
 * client as "failed to connect", which says nothing about the cause.
 */
@Component
public class MirrorDirectory {

    private final String dir;
    private final MirrorReader reader;

    public MirrorDirectory(@Value("${movebank.mirror.dir:}") String dir) {
        this.dir = dir;
        this.reader = dir.isBlank() ? null : new MirrorReader(new File(dir));
    }

    /** The reader; throws {@link IllegalStateException} with a usable message if the directory isn't usable. */
    public MirrorReader reader() {
        if (reader == null) {
            throw new IllegalStateException("No mirror directory configured: set MOVEBANK_MIRROR_DIR "
                    + "to a directory written by movebank-mirror.");
        }
        if (!reader.baseDir().isDirectory()) {
            throw new IllegalStateException("Mirror directory " + dir + " (MOVEBANK_MIRROR_DIR) does not exist.");
        }
        return reader;
    }
}
