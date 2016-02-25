/*
 * The MIT License
 *
 * Copyright (c) 2015, CloudBees, Inc., Stephen Connolly
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 */
package org.jenkinsci.modules.optpluginhelper;

import hudson.Extension;
import hudson.PluginManager;
import hudson.PluginStrategy;
import hudson.PluginWrapper;
import hudson.Util;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.util.CyclicGraphDetector;
import hudson.util.VersionNumber;
import jenkins.RestartRequiredException;
import jenkins.YesNoMaybe;
import jenkins.model.Jenkins;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Helps activating optionally bundled plugins.
 *
 * @since 1.0
 */
@Extension
public class PluginHelper extends Descriptor<PluginHelper> implements Describable<PluginHelper> {

    /**
     * Our logger.
     */
    private static final Logger LOGGER = Logger.getLogger(PluginHelper.class.getName());

    /**
     * The directory where we stage the optional plugins ({@link PluginStrategy} needs {@link File} not {@link URL}).
     */
    private static final String OPTIONAL_PLUGIN_DIR = "optional-plugins";

    /**
     * Save having to re-extract when the sources do not add new URLs that have been extracted already
     */
    private final Map<String, ExtractedPluginMetadata> extractedPluginMetadataMap =
            Collections.synchronizedMap(new HashMap<String, ExtractedPluginMetadata>());

    /**
     * Default constructor.
     */
    public PluginHelper() {
        super(PluginHelper.class);
    }

    /**
     * Returns the singleton {@link PluginHelper} instance.
     *
     * @return the singleton {@link PluginHelper} instance.
     */
    public static PluginHelper instance() {
        // TODO maybe replace with ExtensionList.lookup() once past 1.572
        final Jenkins jenkins = Jenkins.getInstance();
        if (jenkins == null) {
            throw new AssertionError(Jenkins.class + " is missing");
        }
        final PluginHelper instance = jenkins.getDescriptorByType(PluginHelper.class);
        if (instance == null) {
            throw new AssertionError(PluginHelper.class + " is missing");
        }
        return instance;
    }

    /**
     * List all the optional plugins (while populating the staging area with any new ones we discover).
     *
     * @return the list of optional plugins available from all the current defined {@link PluginSource} extensions.
     */
    private List<File> listPlugins() {
        // TODO figure out what to do if two sources provide different versions of the same plugin, currently undefined
        List<File> result = new ArrayList<File>();
        final Jenkins jenkins = Jenkins.getInstance();
        if (jenkins == null) {
            return result;
        }
        File baseDir = new File(jenkins.root, OPTIONAL_PLUGIN_DIR);
        if (baseDir.exists() && !baseDir.isDirectory()) {
            LOGGER.log(Level.SEVERE, "Optional plugin working directory {0} exists and is not a directory", baseDir);
            return result;
        }
        if (!baseDir.isDirectory()) {
            if (!baseDir.mkdirs()) {
                LOGGER.log(Level.SEVERE, "Could not create optional plugin working directory {0}", baseDir);
                return result;
            }
        }
        for (URL resource : PluginSource.allPlugins()) {
            try {
                final String externalForm = resource.toExternalForm();
                ExtractedPluginMetadata metadata = extractedPluginMetadataMap.get(externalForm);
                if (metadata != null) {
                    File archive = new File(baseDir, metadata.shortName + ".jpi");
                    if (archive.isFile() && archive.length() == metadata.length && Util.getDigestOf(archive)
                            .equals(metadata.digest)) {
                        result.add(archive);
                        continue;
                    }
                }
                final URLConnection connection = resource.openConnection();
                long lastModified = connection.getLastModified();
                long size = connection.getContentLength();
                String path = resource.getPath();
                String fileName = FilenameUtils.getBaseName(path);
                boolean nameCheck = false;
                if (StringUtils.isBlank(fileName)) {
                    nameCheck = true;
                    fileName = Util.getDigestOf(resource.toString());
                }
                File file = new File(baseDir, fileName + ".jpi");
                if (file.isFile() && (file.lastModified() == lastModified || lastModified == 0)
                        && file.length() == size) {
                    final String fileDigest = Util.getDigestOf(file);
                    final String resourceDigest;
                    final InputStream stream = connection.getInputStream();
                    try {
                        resourceDigest = Util.getDigestOf(stream);
                    } finally {
                        IOUtils.closeQuietly(stream);
                    }
                    if (fileDigest.equals(resourceDigest)) {
                        result.add(file);
                        extractedPluginMetadataMap.put(externalForm, new ExtractedPluginMetadata(file));
                        continue;
                    }
                }
                FileUtils.copyURLToFile(resource, file);
                if (nameCheck) {
                    final String shortName = jenkins.getPluginManager().getPluginStrategy().getShortName(file);
                    if (!fileName.equals(shortName)) {
                        File newFile = new File(baseDir, shortName + ".jpi");
                        if (!newFile.isFile() || !Util.getDigestOf(newFile).equals(Util.getDigestOf(file))) {
                            FileUtils.moveFile(file, newFile);
                        }
                        file = newFile;
                    }
                }
                if (lastModified != 0) {
                    if (!file.setLastModified(lastModified)) {
                        LOGGER.log(Level.FINE, "Couldn't set last modified on {0}", file);
                    }
                }
                result.add(file);
                extractedPluginMetadataMap.put(externalForm, new ExtractedPluginMetadata(file));
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, String.format("Could not process optional plugin from %s", resource), e);
            }
        }

        LOGGER.log(Level.FINE, "List of plugins: " + result);
        return result;
    }

    /**
     * Refreshes the list of plugins that should be loaded. This will re-examine the full list of plugins provided
     * by all the {@link PluginSource} extensions and filter them through all the {@link PluginWrapperFilter}
     * extensions to see if there are any plugins that can be installed. An attempt will be made to dynamically load
     * the plugins.
     *
     * @return {@code true} if a restart is required to complete activation, {@code false} if either nothing changed
     * or the additional plugins were successfully dynamically loaded.
     */
    public boolean refresh() {
        final Jenkins jenkins = Jenkins.getInstance();
        if (jenkins == null) {
            return false;
        }
        PluginManager pm = jenkins.getPluginManager();
        PluginStrategy ps = pm.getPluginStrategy();

        // now figure out which plugins are included
        LOGGER.log(Level.FINE, "Enumerating available optional plugins and filtering to determine set for activation");
        Map<PluginWrapper, File> wrapperToFile = new HashMap<PluginWrapper, File>();
        Map<PluginWrapper, PluginWrapperFilter.Decision> wrapperToDecision = new HashMap<PluginWrapper,
                PluginWrapperFilter.Decision>();
        for (File plugin : listPlugins()) {
            try {
                PluginWrapper wrapper = ps.createPluginWrapper(plugin);
                final PluginWrapper existing = pm.getPlugin(wrapper.getShortName());
                if (existing != null
                        && (existing.isEnabled() || existing.isActive())
                        && !(wrapper.getVersionNumber().isNewerThan(existing.getVersionNumber()))) {
                    LOGGER.log(Level.FINER, "Excluding {0} version {1} as version {2} is already installed",
                            new Object[]{wrapper.getShortName(), wrapper.getVersion(), existing.getVersion()});
                    continue;
                }
                final PluginWrapperFilter.Decision decision = PluginWrapperFilter.decide(wrapper, plugin);
                if (decision == PluginWrapperFilter.Decision.EXCLUDE) {
                    LOGGER.log(Level.FINER, "Excluding {0} version {1} based on decision from filters",
                            new Object[]{wrapper.getShortName(), wrapper.getVersion()});
                } else {
                    wrapperToFile.put(wrapper, plugin);
                    wrapperToDecision.put(wrapper, decision);
                }
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "IO exception processing " + plugin, e);
            }
        }
        LOGGER.log(Level.FINE, "Initial filtered set determined: {0}", wrapperToDecision);
        // now any non-optional dependencies of an included plugin get upped to included
        boolean changed = true;
        while (changed) {
            changed = false;
            Set<String> upscale = new HashSet<String>();
            for (Map.Entry<PluginWrapper, PluginWrapperFilter.Decision> entry : wrapperToDecision.entrySet()) {
                if (entry.getValue() != PluginWrapperFilter.Decision.INCLUDE) {
                    continue;
                }
                for (PluginWrapper.Dependency d : entry.getKey().getDependencies()) {
                    // we need all non-optional dependencies
                    upscale.add(d.shortName);
                }
                for (PluginWrapper.Dependency d : entry.getKey().getOptionalDependencies()) {
                    // we only need optional dependencies if they are already installed and are an incompatible version
                    final PluginWrapper existing = pm.getPlugin(d.shortName);
                    if (existing != null && (existing.isEnabled() || existing.isActive())) {
                        if (existing.isOlderThan(new VersionNumber(d.version))) {
                            upscale.add(d.shortName);
                        }
                    }
                }
            }
            for (Map.Entry<PluginWrapper, PluginWrapperFilter.Decision> entry : wrapperToDecision.entrySet()) {
                if (entry.getValue() == PluginWrapperFilter.Decision.INCLUDE) {
                    continue;
                }
                if (upscale.contains(entry.getKey().getShortName())) {
                    changed = true;
                    entry.setValue(PluginWrapperFilter.Decision.INCLUDE);
                }
            }
        }
        for (Iterator<Map.Entry<PluginWrapper, PluginWrapperFilter.Decision>> iterator =
             wrapperToDecision.entrySet().iterator(); iterator.hasNext(); ) {
            Map.Entry<PluginWrapper, PluginWrapperFilter.Decision> entry = iterator.next();
            if (entry.getValue() == PluginWrapperFilter.Decision.INCLUDE) {
                continue;
            }
            wrapperToFile.remove(entry.getKey());
            iterator.remove();
        }
        LOGGER.log(Level.FINE, "After adding required dependencies: {0}", wrapperToDecision.keySet());

        if (wrapperToFile.isEmpty()) {
            // bail early if the list is empty
            LOGGER.log(Level.FINE, "No new optional plugins to install");
            return false;
        }

        LOGGER.log(Level.FINE, "Checking if dynamic loading of plugins is possible...");
        boolean cannotDynamicLoad = false;
        for (PluginWrapper wrapper : wrapperToFile.keySet()) {
            final PluginWrapper existing = pm.getPlugin(wrapper.getShortName());
            if (existing != null && (existing.isActive() || existing.isEnabled()) && !existing.isPinned()) {
                LOGGER.log(Level.INFO, "Cannot dynamically load optional plugins because {0} is already installed",
                        existing.getShortName());
                cannotDynamicLoad = true;
            } else if (YesNoMaybe.NO == wrapper.supportsDynamicLoad()) {
                LOGGER.log(Level.INFO,
                        "Cannot dynamically load optional plugins because {0} does not support dynamic load",
                        wrapper.getShortName());
                cannotDynamicLoad = true;
            }
        }

        Map<String, VersionNumber> finalVersions = new HashMap<String, VersionNumber>();
        // start with the active/enabled plugins that are currently installed
        for (PluginWrapper w : pm.getPlugins()) {
            if (w.isActive() || w.isEnabled()) {
                finalVersions.put(w.getShortName(), w.getVersionNumber());
            }
        }
        // now add any new versions
        for (PluginWrapper w : wrapperToFile.keySet()) {
            VersionNumber existing = finalVersions.get(w.getShortName());
            if (existing == null || w.getVersionNumber().isNewerThan(existing)) {
                finalVersions.put(w.getShortName(), w.getVersionNumber());
            }
        }

        LOGGER.log(Level.FINE, "Expected final plugin version map: {0}", finalVersions);

        Set<String> pluginsToEnable = new HashSet<String>();
        for (PluginWrapper w : wrapperToFile.keySet()) {
            LOGGER.log(Level.FINE, "Checking if {0} can be enabled, i.e. all dependencies can be satisfied",
                    w.getShortName());
            boolean missingDependency = false;
            for (PluginWrapper.Dependency d : w.getDependencies()) {
                VersionNumber v = finalVersions.get(d.shortName);
                if (v == null || v.isOlderThan(new VersionNumber(d.version))) {
                    missingDependency = true;
                    LOGGER.log(Level.FINER, "{0} is missing a dependency on {1} version {2}",
                            new Object[]{w.getShortName(), d.shortName, d.shortName});
                }
            }
            for (PluginWrapper.Dependency d : w.getOptionalDependencies()) {
                VersionNumber v = finalVersions.get(d.shortName);
                if (v != null && v.isOlderThan(new VersionNumber(d.version))) {
                    missingDependency = true;
                    LOGGER.log(Level.FINER, "{0} is missing a dependency on {1} version {2}",
                            new Object[]{w.getShortName(), d.shortName, d.shortName});
                }
            }
            if (missingDependency) {
                LOGGER.log(Level.FINE, "{0} cannot be enabled due to missing dependencies", w.getShortName());
            } else {
                LOGGER.log(Level.FINE, "{0} can be enabled", w.getShortName());
                pluginsToEnable.add(w.getShortName());
            }
        }

        Map<String, File> newPlugins = new HashMap<String, File>();
        for (Map.Entry<PluginWrapper, File> entry : wrapperToFile.entrySet()) {
            final String shortName = entry.getKey().getShortName();
            final PluginWrapper existing = pm.getPlugin(shortName);
            final PluginWrapper proposed = entry.getKey();
            if (existing != null && existing.isActive()) {
                if (existing.getVersionNumber().equals(proposed.getVersionNumber())) {
                    LOGGER.log(Level.FINE, "Ignoring installing plugin {0} as current version is desired",
                            shortName);
                    // ignore as we are fine
                    continue;
                }
                if (existing.getVersionNumber().isNewerThan(proposed.getVersionNumber())) {
                    LOGGER.log(Level.INFO,
                            "Ignoring installing plugin {0} as current version {1} is newer that bundled "
                                    + "version {2}",
                            new Object[]{shortName, existing.getVersion(), proposed.getVersion()});
                    continue;
                }
                if (existing.isPinned()) {
                    LOGGER.log(Level.INFO,
                            "Ignoring installing plugin {0} as it is pinned. You might want to unpin this plugin.",
                            new Object[]{shortName});
                    continue;
                }

                LOGGER.log(Level.INFO, "Restart required as plugin {0} is already installed", shortName);
                cannotDynamicLoad = true;
            }
            String fileName = shortName + ".jpi";
            String legacyName = fileName.replace(".jpi", ".hpi");
            File file = new File(pm.rootDir, fileName);
            File pinFile = new File(pm.rootDir, fileName + ".pinned");
            File disableFile = new File(pm.rootDir, fileName + ".disabled");


            // normalization first, if the old file exists.
            try {
                rename(new File(pm.rootDir, legacyName), file);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, String.format("Could not move legacy %s.hpi to %s.jpi", shortName, shortName),
                        e);
            }
            try {
                rename(new File(pm.rootDir, legacyName + ".pinned"), pinFile);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        String.format("Could not move legacy %s.hpi.pinned to %s.jpi.pinned", shortName, shortName), e);
            }
            try {
                rename(new File(pm.rootDir, legacyName + ".disabled"), disableFile);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        String.format("Could not move legacy %s.hpi.disabled to %s.jpi.disabled", shortName, shortName),
                        e);
            }

            // update file if:
            //  - no file exists today
            //  - bundled version and current version differs (by timestamp), and the file isn't pinned.
            final long lastModified = entry.getValue().lastModified();
            if (!file.exists() || (file.lastModified() != lastModified && !pinFile.exists())) {
                try {
                    FileUtils.copyFile(entry.getValue(), file);
                    if (lastModified != -1 && !file.setLastModified(lastModified)) {
                        LOGGER.log(Level.WARNING, "Could not set last modified timestamp on {0}.jpi", shortName);
                    }
                    // lastModified is set for two reasons:
                    // - to avoid unpacking as much as possible, but still do it on both upgrade and downgrade
                    // - to make sure the value is not changed after each restart, so we can avoid
                    // unpacking the plugin itself in ClassicPluginStrategy.explode
                    newPlugins.put(shortName, file);
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, String.format("Could not write %s.jpi", shortName), e);
                }
            }
            if (!pluginsToEnable.contains(shortName)) {
                try {
                    new FileOutputStream(disableFile).close();
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, String.format("Could not flag %s as a disabled plugin", shortName), e);
                }
            }
        }

        if (cannotDynamicLoad) {
            return true;
        }

        LOGGER.log(Level.FINE, "Sorting plugins to determine loading order...");
        // now we need to sort plugins and try and dynamically load them
        final List<PluginWrapper> plugins = new ArrayList<PluginWrapper>(newPlugins.size());
        for (File p : newPlugins.values()) {
            try {
                plugins.add(ps.createPluginWrapper(p));
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "IO exception processing " + p, e);
                cannotDynamicLoad = true;
            }
        }
        if (cannotDynamicLoad) {
            return true;
        }
        CyclicGraphDetector<PluginWrapper> cgd = new CyclicGraphDetector<PluginWrapper>() {
            @Override
            protected List<PluginWrapper> getEdges(PluginWrapper p) {
                List<PluginWrapper> next = new ArrayList<PluginWrapper>();
                addTo(p.getDependencies(), next);
                addTo(p.getOptionalDependencies(), next);
                return next;
            }

            private void addTo(List<PluginWrapper.Dependency> dependencies, List<PluginWrapper> r) {
                for (PluginWrapper.Dependency d : dependencies) {
                    for (PluginWrapper p : plugins) {
                        if (p.getShortName().equals(d.shortName)) {
                            r.add(p);
                        }
                    }
                }
            }

        };
        try {
            cgd.run(plugins);
        } catch (CyclicGraphDetector.CycleDetectedException e) {
            LOGGER.log(Level.WARNING, "Cyclic reference detected amongst bundled plugins: " + plugins, e);
            cannotDynamicLoad = true;
        }
        LOGGER.log(Level.FINE, "Sorted plugin load order: {0}", cgd.getSorted());
        LOGGER.log(Level.INFO, "Starting dynamic loading of optional bundled plugins");
        for (PluginWrapper plugin : cgd.getSorted()) {
            File archive = newPlugins.get(plugin.getShortName());
            if (archive == null) {
                // cannot happen, we put only plugins from newPlugins into the list and sorting should never
                // add, so the sorting should be a 1:1 mapping. We have this NPE check for safety only.
                continue;
            }
            try {
                pm.dynamicLoad(archive);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        String.format("Failed to dynamic load plugin %s version %s", plugin.getShortName(),
                                plugin.getVersion()), e);
                cannotDynamicLoad = true;
                break;
            } catch (InterruptedException e) {
                LOGGER.log(Level.WARNING, String.format("Interrupted while trying to dynamic load plugin %s version %s",
                        plugin.getShortName(), plugin.getVersion()), e);
                cannotDynamicLoad = true;
                break;
            } catch (RestartRequiredException e) {
                LOGGER.log(Level.WARNING,
                        String.format("Plugin %s version %s does not support dynamic loading", plugin.getShortName(),
                                plugin.getVersion()), e);
                cannotDynamicLoad = true;
                break;
            }
        }
        LOGGER.log(Level.INFO, "Finished dynamic loading of optional bundled plugins, restart required {0}", cannotDynamicLoad);
        return cannotDynamicLoad;
    }

    /**
     * Rename a legacy file to a new name, with care to Windows where {@link File#renameTo(File)}
     * doesn't work if the destination already exists.
     */
    private void rename(File legacyFile, File newFile) throws IOException {
        if (!legacyFile.exists()) {
            return;
        }
        if (newFile.exists()) {
            Util.deleteFile(newFile);
        }
        if (!legacyFile.renameTo(newFile)) {
            LOGGER.warning("Failed to rename " + legacyFile + " to " + newFile);
        }
    }

    /**
     * {@inheritDoc}
     */
    public PluginHelper getDescriptor() {
        return instance();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getDisplayName() {
        return null;
    }

    private static class ExtractedPluginMetadata {
        private final String shortName;
        private final String digest;
        private final long length;

        public ExtractedPluginMetadata(File archive) throws IOException {
            this.digest = Util.getDigestOf(archive);
            final Jenkins jenkins = Jenkins.getInstance();
            this.shortName = jenkins == null
                    ? FilenameUtils.getBaseName(archive.getName())
                    : jenkins.getPluginManager().getPluginStrategy().getShortName(archive);
            this.length = archive.length();
        }
    }

}
