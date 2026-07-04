/* Copyright 2023-2024 Norconex Inc.
 *
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
package com.norconex.importer.handler.transformer.impl;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;

import com.norconex.commons.lang.config.Configurable;
import com.norconex.importer.handler.ConfigurableDocHandler;
import com.norconex.commons.lang.file.FileUtil;
import com.norconex.commons.lang.text.StringUtil;
import com.norconex.importer.handler.DocHandler;
import com.norconex.importer.handler.DocHandlerContext;

import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * <p>
 * Saves a copy of the document at its current processing state in
 * the specified directory. If no directory is specified, the default is
 * {@value SaveDocumentTransformer#DEFAULT_SAVE_DIR_PATH}.
 * It is recommended to use this tagger as a pre-parse handler if you
 * want to save the original file.
 * </p>
 * <p>
 * Any maximum file path length value below
 * {@value StringUtil#TRUNCATE_HASH_LENGTH} is considered unlimited
 * (the default).
 * </p>
 *
 * {@nx.xml.usage
 * <handler class="com.norconex.importer.handler.tagger.impl.SaveDocumentTagger">
 *
 *   {@nx.include com.norconex.importer.handler.AbstractImporterHandler#restrictTo}
 *
 *   <saveDir>(target directory path where to save documents)</saveDir>
 *   <maxPathLength>
 *     (maximum file path length; defaults to -1, i.e., unlimited)
 *   </maxPathLength>
 *   <dirSplitPattern>
 *     (regular expression matching the separator(s) used to split the document
 *      reference in sub-directories)
 *   </dirSplitPattern>
 *   <escape>[false|true]</escape>
 *   <pathToField>
 *     (optional field name where to store the saved file path)
 *   </pathToField>
 *   <defaultFileName>
 *     (file name to use if a file path already exists as a directory).
 *   </defaultFileName>
 * </handler>
 * }
 *
 * {@nx.xml.example
 * <handler class="SaveDocumentTagger">
 *   <saveDir>/save/files/here/</saveDir>
 *   <pathToField>file.path</pathToField>
 *   <defaultFileName>index.html</defaultFileName>
 * </handler>
 * }
 */
@SuppressWarnings("javadoc")
@Data
@Slf4j
public class SaveDocumentTransformer
        implements ConfigurableDocHandler<SaveDocumentTransformerConfig> {

    private final SaveDocumentTransformerConfig configuration =
            new SaveDocumentTransformerConfig();

    @Getter(value = AccessLevel.NONE)
    @Setter(value = AccessLevel.NONE)
    private static boolean warned = false;

    @Override
    public boolean handle(DocHandlerContext docCtx) throws IOException {

        // create relative path by splitting into directories and maybe escaping
        var rawRelativePath = StringUtils.strip(
                String.join(
                        "/",
                        Stream.of(
                                docCtx.reference().split(
                                        configuration.getDirSplitPattern()))
                                .map(
                                        seg -> configuration.isEscape()
                                                ? FileUtil.toSafeFileName(seg)
                                                : seg)
                                .toList()),
                "\\/");

        // define file, possibly truncated
        var file = adjustLength(
                configuration.getSaveDir().resolve(rawRelativePath));

        saveFile(docCtx, file);

        // store path to a field
        if (StringUtils.isNotBlank(configuration.getPathToField())) {
            docCtx.metadata().add(
                    configuration.getPathToField(),
                    file.toAbsolutePath().toString());
        }
        return true;
    }

    // Done synchronously to reduce the risk of collisions when
    // dealing with default file names vs directory.  A file could be
    // renamed/written while saving occurs in another thread.
    private synchronized void saveFile(
            DocHandlerContext docCtx, Path file) throws IOException {
        // if file already exists as a directory, give it a default file name,
        // within that directory
        if (Files.isDirectory(file)) {
            file = adjustLength(
                    file.resolve(configuration.getDefaultFileName()));
        }

        // safe file
        LOG.debug("Saving {} to {}", docCtx.reference(), file);
        // rename dirs to file if need be, but we make sure not to rename
        // dirs of saveDir.
        try (var input = docCtx.input().asInputStream()) {
            var segFromLast = file.getNameCount() -
                    configuration.getSaveDir().getNameCount();
            Path parent = null;
            for (var i = 0; i < segFromLast; i++) {
                parent = file.getParent();
                if (conflictsHandled(parent)) {
                    break;
                }
            }
            Files.createDirectories(file.getParent());
            Files.copy(input, file, REPLACE_EXISTING);
        }
    }

    private boolean conflictsHandled(Path parent) throws IOException {
        if (Files.isDirectory(parent)) {
            return true;
        }
        if (Files.isRegularFile(parent)) {
            LOG.debug(
                    """
                            Renaming file {} to {} as its original name\s\
                            conflicts with the creation of a directory of\s\
                            the same name.""",
                    parent, parent.resolve(
                            configuration.getDefaultFileName()));
            var newLocation = parent.resolve(
                    configuration.getDefaultFileName());
            //TODO use importer temp dir, which should be set by caller
            // (e.g., crawler) or OS default.
            var tmpLocation = Files.createTempFile(null, null);
            Files.move(parent, tmpLocation, REPLACE_EXISTING);
            Files.createDirectories(newLocation.getParent());
            Files.move(tmpLocation, newLocation, REPLACE_EXISTING);
            return true;
        }
        return false;
    }

    private Path adjustLength(Path file) {
        if (configuration
                .getMaxPathLength() < StringUtil.TRUNCATE_HASH_LENGTH) {
            return file;
        }

        var saveDirLength =
                configuration.getSaveDir().toAbsolutePath().toString().length();
        if (saveDirLength + StringUtil.TRUNCATE_HASH_LENGTH > configuration
                .getMaxPathLength()) {
            if (!warned) {
                LOG.warn(
                        "The save directory path is too long to apply file "
                                + "path truncation on saved files. Save directory: {}",
                        configuration.getSaveDir());
                warned = true;
            }
            return file;
        }

        var fileLength = file.toAbsolutePath().toString().length();

        if (fileLength > configuration.getMaxPathLength()) {
            var truncatedFile = Path.of(
                    StringUtil.truncateWithHash(
                            file.toAbsolutePath().toString(),
                            configuration.getMaxPathLength()));
            LOG.debug(
                    "File path '{}' was truncated to '{}'.",
                    file, truncatedFile);
            file = truncatedFile;
        }
        return file;
    }
}
