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
import hudson.Util;
import jenkins.model.Jenkins;

import javax.annotation.Nonnull;
import javax.servlet.ServletContext;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Implementation of {@link PluginSource} that provides any plugins in the {@code /WEB-INF/optional-plugins} context
 * path of the web archive.
 *
 * @since 1.0
 */
@Extension
public class OptionalPluginSource extends PluginSource {
    /**
     * Our logger.
     */
    private static final Logger LOGGER = Logger.getLogger(OptionalPluginSource.class.getName());

    /**
     * {@inheritDoc}
     */
    @Nonnull
    @Override
    public List<URL> listPlugins() {
        final Jenkins jenkins = Jenkins.getInstance();
        if (jenkins == null) {
            return Collections.emptyList();
        }
        ServletContext context = jenkins.servletContext;
        List<URL> result = new ArrayList<URL>();
        for (String path : Util.fixNull((Set<String>) context.getResourcePaths("/WEB-INF/optional-plugins"))) {
            String fileName = path.substring(path.lastIndexOf('/') + 1);
            if (fileName.length() == 0) {
                // see http://www.nabble.com/404-Not-Found-error-when-clicking-on-help-td24508544.html
                // I suspect some containers are returning directory names.
                continue;
            }
            final String lowerCaseFileName = fileName.toLowerCase();
            if (lowerCaseFileName.endsWith(".hpi") || lowerCaseFileName.endsWith(".jpi")) {
                try {
                    result.add(context.getResource(path));
                } catch (MalformedURLException e) {
                    LOGGER.log(Level.WARNING, "Malformed resource path " + path, e);
                }
            }
        }
        return result;
    }
}
