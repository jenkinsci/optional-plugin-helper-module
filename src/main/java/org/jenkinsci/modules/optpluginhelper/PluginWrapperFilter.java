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
import hudson.PluginWrapper;
import jenkins.model.Jenkins;

import javax.annotation.Nonnull;
import java.io.File;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * An extension point for plugins to provide filters to apply against optionally bundled plugins.
 *
 * @since 1.0
 */
public abstract class PluginWrapperFilter implements ExtensionPoint {
    /**
     * Our logger.
     */
    private static final Logger LOGGER = Logger.getLogger(PluginWrapperFilter.class.getName());

    /**
     * Represents the result of a decision.
     *
     * @since 1.0
     */
    public enum Decision {
        /**
         * The filter wants the plugin excluded. This decision has veto, in other words if any
         * {@link PluginWrapperFilter} returns this result then the result is final.
         *
         * @since 1.0
         */
        EXCLUDE,
        /**
         * The filter doesn't care about the plugin.
         *
         * @since 1.0
         */
        NO_OPINION,
        /**
         * The filter wants the plugin included.
         *
         * @since 1.0
         */
        INCLUDE;
    }

    /**
     * Makes a decision on a plugin.
     *
     * @param plugin  the plugin wrapper.
     * @param archive the archive file as sometimes a plugin archive needs to be inspected.
     * @return the decision, where {@link Decision#EXCLUDE} is a veto.
     * @since 1.0
     */
    @Nonnull
    protected abstract Decision makeDecision(PluginWrapper plugin, File archive);

    /**
     * Makes a decision on a plugin.
     *
     * @param plugin  the plugin wrapper.
     * @param archive the archive file as sometimes a plugin archive needs to be inspected.
     * @return the decision, where {@link Decision#EXCLUDE} is a veto.
     * @since 1.0
     */
    public static Decision decide(PluginWrapper plugin, File archive) {
        final Jenkins jenkins = Jenkins.getInstance();
        Decision result = Decision.NO_OPINION;
        // TODO replace with ExtensionList.lookup() once past 1.572
        for (PluginWrapperFilter filter : jenkins.getExtensionList(PluginWrapperFilter.class)) {
            try {
                switch (filter.makeDecision(plugin, archive)) {
                    case EXCLUDE:
                        return Decision.EXCLUDE;
                    case INCLUDE:
                        result = Decision.INCLUDE;
                        break;
                    case NO_OPINION:
                        break;
                    default:
                        break;
                }
            } catch (RuntimeException e) {
                // these should not happen, but we should be graceful if they do
                LOGGER.log(Level.INFO, String.format("Optional plugin filter %s threw a runtime exception", filter),
                        e);
            } catch (Exception e) {
                // your implementation must be doing funky stuff to throw a checked exception from a method with
                // no checked exceptions declared
                LOGGER.log(Level.WARNING,
                        String.format("Optional plugin filter %s threw an unexpected checked exception", filter), e);
            } catch (Error e) {
                // nothing we can do with an error, just pass it through
                throw e;
            } catch (Throwable t) {
                // your implementation is broken if we end up here 
                LOGGER.log(Level.SEVERE,
                        String.format("Optional plugin filter %s threw an unexpected throwable", filter), t);
            }
        }
        return result;
    }
}
