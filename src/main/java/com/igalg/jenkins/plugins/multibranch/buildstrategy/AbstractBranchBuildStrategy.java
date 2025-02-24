/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2019 igalg
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
 */
package com.igalg.jenkins.plugins.multibranch.buildstrategy;

import static com.igalg.jenkins.plugins.multibranch.buildstrategy.BranchBuildStrategyHelper.buildSCMFileSystem;
import static com.igalg.jenkins.plugins.multibranch.buildstrategy.BranchBuildStrategyHelper.getGitChangeSetList;

import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import com.google.common.annotations.VisibleForTesting;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.TaskListener;
import hudson.plugins.git.GitChangeSet;
import hudson.scm.SCM;
import jenkins.branch.BranchBuildStrategy;
import jenkins.scm.api.SCMFileSystem;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceOwner;
import jenkins.scm.api.mixin.ChangeRequestSCMRevision;

public abstract class AbstractBranchBuildStrategy extends BranchBuildStrategy {

    private static final Logger LOGGER = Logger.getLogger(AbstractBranchBuildStrategy.class.getName());

    public enum Strategy {
        INCLUDED, EXCLUDED
    }

    private final Strategy strategy;

    protected AbstractBranchBuildStrategy(Strategy strategy) {
        this.strategy = strategy;
    }

    @Override
    public boolean isAutomaticBuild(@NonNull SCMSource source, @NonNull SCMHead head, @NonNull SCMRevision currRevision, SCMRevision lastBuiltRevision, SCMRevision lastSeenRevision, @NonNull TaskListener listener) {
        try {
            // verify source owner
            final SCMSourceOwner owner = source.getOwner();
            if (owner == null) {
                LOGGER.severe("Error verify SCM source owner");
                return true;
            }

            // build SCM object
            final SCM scm = source.build(head, currRevision);

            // build SCM file system
            final SCMFileSystem fileSystem = buildSCMFileSystem(source, head, currRevision, scm, owner);
            if (fileSystem == null) {
                LOGGER.severe("Error build SCM file system");
                return true;
            }

            // get patterns
            final Set<String> patterns = getPatterns(fileSystem);
            if (patterns.isEmpty()) {
                boolean build = strategy == Strategy.EXCLUDED;
                LOGGER.info(() -> String.format("No pattern with strategy: %s, building=%s", strategy, build));
                return build;
            }

            // If this is the first build of a change request, compare against the target.
            if (lastBuiltRevision == null && currRevision instanceof ChangeRequestSCMRevision) {
                lastBuiltRevision = ((ChangeRequestSCMRevision) currRevision).getTarget();
            }

            // collect all changes from previous build
            final List<GitChangeSet> changeSets = getGitChangeSetList(fileSystem, head, lastBuiltRevision);

            // get expressions to check matching to pattern
            final Set<String> expressions = getExpressions(changeSets);

            LOGGER.fine(() -> String.format("Strategy: %s, patterns: [%s], expressions: [%s]", strategy, String.join(", ", patterns), String.join(", ", expressions)));

            return shouldRunBuild(patterns, expressions);
        } catch (final Exception e) {
            LOGGER.severe("Unexpected exception: " + e);

            if (e instanceof InterruptedException) {
                // clean up whatever needs to be handled before interrupting
                Thread.currentThread().interrupt();
            }

            // we don't want to cancel builds on unexpected exception
            return true;
        }
    }

    @VisibleForTesting
    abstract Set<String> getPatterns(SCMFileSystem fileSystem);

    @VisibleForTesting
    abstract Set<String> getExpressions(List<GitChangeSet> changeSets);

    @VisibleForTesting
    abstract boolean shouldRunBuild(Set<String> patterns, Set<String> expressions);
}
