package io.quarkiverse.asyncapi.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.smallrye.config.ConfigSourceContext;

public class AsyncAPISupplierFactory {

    private static final Logger logger = LoggerFactory.getLogger(AsyncAPISupplierFactory.class);
    private static AsyncAPISupplierFactory instance;

    private final Set<String> EXTENSIONS = Set.of(".yml", ".yaml", ".json");
    private Collection<AsyncAPISupplier> asyncAPISuppliers = new ArrayList<>();

    public static AsyncAPISupplierFactory init(ConfigSourceContext context) {
        instance = new AsyncAPISupplierFactory(context);
        return instance;
    }

    public static AsyncAPISupplierFactory get() {
        return instance;
    }

    private AsyncAPISupplierFactory(ConfigSourceContext context) {
        List<String> specDirs = getValues(context, AsyncApiConfigGroup.SOURCES_PROP,
                Arrays.asList("src/main/asyncapi", "src/test/asyncapi"));
        final Collection<String> ignoredFiles = excludedFiles(context);
        for (String dir : specDirs) {
            Path specDir = Path.of(dir);
            if (Files.isDirectory(specDir)) {
                try (Stream<Path> specFilePaths = Files.walk(specDir)) {
                    Collection<Path> files = specFilePaths
                            .filter(path -> isCandidateFile(path, ignoredFiles))
                            .collect(Collectors.toList());
                    for (Path file : files) {
                        asyncAPISuppliers.add(new JacksonAsyncAPISupplier(
                                AsyncAPIUtils.getJavaClassName(file.getFileName().toString()), Files.readString(file)));
                    }
                } catch (IOException e) {
                    logger.error("Error processing dir {}", specDir, e);
                }
            }
        }
        ServiceLoader<AsyncApiSpecInputProvider> providers = ServiceLoader.load(AsyncApiSpecInputProvider.class);
        for (AsyncApiSpecInputProvider provider : providers) {
            AsyncAPISpecInput specInput = provider.read(context);
            for (Map.Entry<String, InputStreamSupplier> entry : specInput.getStreams().entrySet()) {
                try (InputStream stream = entry.getValue().get()) {
                    asyncAPISuppliers.add(new JacksonAsyncAPISupplier(entry.getKey(), new String(stream.readAllBytes())));
                } catch (IOException e) {
                    logger.error("Error processing stream id {}", entry.getKey(), e);
                }
            }
        }
    }

    public Collection<AsyncAPISupplier> getAsyncApiSuppliers() {
        return asyncAPISuppliers;
    }

    private Collection<String> excludedFiles(ConfigSourceContext context) {
        return getValues(context, AsyncApiConfigGroup.EXCLUDED_FILES_PROP, Collections.emptyList());
    }

    private boolean isCandidateFile(Path path, Collection<String> ignoredFiles) {
        String fileName = path.getFileName().toString();
        return Files.isRegularFile(path) && !ignoredFiles.contains(fileName) && isExtension(fileName);
    }

    List<String> getValues(ConfigSourceContext context, String propertyName, List<String> defaultValue) {
        String propValue = context.getValue(propertyName).getValue();
        return propValue == null ? defaultValue : Arrays.asList(propValue.split(",; "));
    }

    private boolean isExtension(String fileName) {
        return EXTENSIONS.stream().anyMatch(ext -> fileName.endsWith(ext));
    }
}
