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

import hudson.ExtensionPoint;
import hudson.Util;
import jenkins.model.Jenkins;

import javax.annotation.Nonnull;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * An extension point to represent source of optional plugins.
 *
 * @since 1.0
 */
public abstract class PluginSource implements ExtensionPoint {

    /**
     * Our logger.
     */
    private static Logger LOGGER = Logger.getLogger(PluginSource.class.getName());

    /**
     * Lists all the optional plugins available from this source.
     *
     * @return a list of URLs representing plugin files, never {@code null}.
     */
    @Nonnull
    public abstract List<URL> listPlugins();

    /**
     * Returns the canonical list of all plugins from all {@link PluginSource} implementations.
     *
     * @return the list of {@link URL}s for all the optional plugins, may be empty but never {@code null}
     */
    @Nonnull
    public static List<URL> allPlugins() {
        Set<URL> resultSet = new LinkedHashSet<URL>();
        Jenkins jenkins = Jenkins.getInstance();
        // TODO switch to ExtensionList.lookup once Jenkins 1.572+
        if (jenkins != null) {
            for (PluginSource src : jenkins.getExtensionList(PluginSource.class)) {
                try {
                    // trust but verify, this extension point can be used in cases where it may not be easy
                    // to recover from errors until it has done its job, so this must be error safe in the extreme
                    for (Object url : Util.fixNull(src.listPlugins())) {
                        if (url instanceof URL) {
                            resultSet.add((URL) url);
                        } else if (url == null) {
                            LOGGER.log(Level.SEVERE,
                                    "Optional plugin source {0} returned a null value in its list of optional plugins",
                                    src);
                        } else {
                            LOGGER.log(Level.SEVERE,
                                    "Optional plugin source {0} returned an instance of {1} in its list of optional " 
                                            + "plugins where only instances of {2} are expected",
                                    new Object[]{src, url.getClass(), URL.class});
                        }
                    }
                } catch (RuntimeException e) {
                    // these should not happen, but we should be graceful if they do
                    LOGGER.log(Level.INFO, String.format("Optional plugin source %s threw a runtime exception", src),
                            e);
                } catch (Exception e) {
                    // your implementation must be doing funky stuff to throw a checked exception from a method with
                    // no checked exceptions declared
                    LOGGER.log(Level.WARNING,
                            String.format("Optional plugin source %s threw an unexpected checked exception", src), e);
                } catch (Error e) {
                    // nothing we can do with an error, just pass it through
                    throw e;
                } catch (Throwable t) {
                    // your implementation is broken if we end up here 
                    LOGGER.log(Level.SEVERE,
                            String.format("Optional plugin source %s threw an unexpected throwable", src), t);
                }
            }
        }
        return new ArrayList<URL>(resultSet);
    }
}
