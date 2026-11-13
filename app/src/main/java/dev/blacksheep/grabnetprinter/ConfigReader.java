package dev.blacksheep.grabnetprinter;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects the effective configuration inside the hooked target app process.
 *
 * <p>Sources, lowest to highest priority:</p>
 * <ol>
 *   <li>the optional legacy file {@code /data/local/tmp/grab-netprinter.conf};</li>
 *   <li>the read-only settings provider (works where Android package visibility permits it);</li>
 *   <li>a per-target-app config file written by the settings screen into the target app's own
 *       files directory (via root). Because the hooked app reads a file it owns, this path needs
 *       no visibility exception, no root at read time and no SELinux permissive mode.</li>
 * </ol>
 *
 * <p>The per-target file is the primary channel: the settings UI writes it for every target
 * package, and the KernelSU/Magisk support module can seed it from {@code config.prop} at boot.</p>
 */
final class ConfigReader {

    /** File name written into each target app's {@code files/} directory. */
    static final String TARGET_FILE = "gnp.conf";

    private ConfigReader() {
    }

    static final class Result {
        final Config config;
        final String sources;

        Result(Config config, String sources) {
            this.config = config;
            this.sources = sources;
        }
    }

    static Result load(Context context) {
        Map<String, String> legacy = Config.readFile(new File(Config.DEFAULT_PATH));
        Map<String, String> provider = ConfigProviderClient.read(context);
        Map<String, String> targetFile = new LinkedHashMap<String, String>();
        try {
            File own = new File(context.getFilesDir(), TARGET_FILE);
            targetFile = Config.readFile(own);
        } catch (Throwable ignored) {
            // no files dir: defaults apply
        }

        Map<String, String> merged = new LinkedHashMap<String, String>();
        merged.putAll(legacy);
        merged.putAll(provider);
        merged.putAll(targetFile);

        List<String> warnings = new ArrayList<String>();
        Config config = Config.fromKeyValues(merged, warnings);
        String sources = "target-file=" + targetFile.size()
                + " settings-provider=" + provider.size()
                + " legacy-file=" + legacy.size();
        return new Result(config, sources);
    }
}
