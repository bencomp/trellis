/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.trellisldp.file;

import static java.lang.System.lineSeparator;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.newBufferedWriter;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;
import static java.util.Collections.emptySortedSet;
import static java.util.Collections.unmodifiableSortedSet;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.runAsync;
import static java.util.concurrent.CompletableFuture.supplyAsync;
import static org.slf4j.LoggerFactory.getLogger;
import static org.trellisldp.api.Resource.SpecialResources.MISSING_RESOURCE;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Iterator;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import javax.enterprise.inject.Alternative;
import javax.inject.Inject;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.rdf.api.IRI;
import org.apache.tamaya.ConfigurationProvider;
import org.slf4j.Logger;
import org.trellisldp.api.MementoService;
import org.trellisldp.api.Resource;

/**
 * A file-based versioning system.
 */
@Alternative
public class FileMementoService implements MementoService {

    /** The configuration key controlling the base filesystem path for memento storage. **/
    public static final String CONFIG_FILE_MEMENTO_BASE_PATH = "trellis.file.memento.basepath";

    private static final Logger LOGGER = getLogger(FileMementoService.class);

    private final File directory;

    /**
     * Create a file-based memento service.
     */
    @Inject
    public FileMementoService() {
        this(ConfigurationProvider.getConfiguration().get(CONFIG_FILE_MEMENTO_BASE_PATH));
    }

    /**
     * Create a file-based memento service.
     * @param path the file path
     */
    public FileMementoService(final String path) {
        requireNonNull(path, "Memento base path is undefined!");
        LOGGER.info("Storing Mementos as files at {}", path);
        this.directory = new File(path);
        init();
    }

    @Override
    public CompletableFuture<Void> put(final Resource resource) {
        return put(resource, resource.getModified());
    }

    CompletableFuture<Void> put(final Resource resource, final Instant time) {
        return runAsync(() -> {
            final File resourceDir = FileUtils.getResourceDirectory(directory, resource.getIdentifier());
            if (!resourceDir.exists()) {
                resourceDir.mkdirs();
            }

            try (final BufferedWriter writer = newBufferedWriter(
                            getNquadsFile(resourceDir, time).toPath(), UTF_8, CREATE, WRITE,
                            TRUNCATE_EXISTING)) {
                final Iterator<String> lineIter = resource.stream().map(FileUtils::serializeQuad).iterator();
                while (lineIter.hasNext()) {
                    writer.write(lineIter.next() + lineSeparator());
                }
            } catch (final IOException ex) {
                throw new UncheckedIOException(
                                "Error writing resource version for " + resource.getIdentifier().getIRIString(), ex);
            }
        });
    }

    @Override
    public CompletableFuture<Resource> get(final IRI identifier, final Instant time) {
        return supplyAsync(() -> {
            final File resourceDir = FileUtils.getResourceDirectory(directory, identifier);
            final File file = getNquadsFile(resourceDir, time);
            if (file.exists()) {
                return new FileResource(identifier, file);
            }
            final SortedSet<Instant> possible = listMementos(identifier).headSet(time);
            if (possible.isEmpty()) {
                return MISSING_RESOURCE;
            }
            return new FileResource(identifier, getNquadsFile(resourceDir, possible.last()));
        });
    }

    @Override
    public CompletableFuture<SortedSet<Instant>> mementos(final IRI identifier) {
        return supplyAsync(() -> listMementos(identifier));
    }

    private void init() {
        if (!directory.exists()) {
            directory.mkdirs();
        }
    }

    private SortedSet<Instant> listMementos(final IRI identifier) {
        final File resourceDir = FileUtils.getResourceDirectory(directory, identifier);
        if (!resourceDir.exists()) {
            return emptySortedSet();
        }

        final SortedSet<Instant> instants = new TreeSet<>();
        try (final Stream<Path> files = Files.list(resourceDir.toPath())) {
            files.map(Path::toString).filter(path -> path.endsWith(".nq")).map(FilenameUtils::getBaseName)
                .map(Long::parseLong).map(Instant::ofEpochSecond).forEach(instants::add);
        } catch (final IOException ex) {
            throw new UncheckedIOException("Error fetching memento list for " + identifier, ex);
        }

        return unmodifiableSortedSet(instants);
    }

    private File getNquadsFile(final File dir, final Instant time) {
        return new File(dir, Long.toString(time.getEpochSecond()) + ".nq");
    }
}
