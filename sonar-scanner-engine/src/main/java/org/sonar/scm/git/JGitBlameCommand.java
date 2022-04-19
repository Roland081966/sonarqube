/*
 * SonarQube
 * Copyright (C) 2009-2022 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.scm.git;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.blame.BlameResult;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.submodule.SubmoduleWalk;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.fs.internal.DefaultInputFile;
import org.sonar.api.batch.scm.BlameCommand;
import org.sonar.api.batch.scm.BlameLine;
import org.sonar.api.config.Configuration;
import org.sonar.api.notifications.AnalysisWarnings;
import org.sonar.api.scan.filesystem.PathResolver;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;

public class JGitBlameCommand extends BlameCommand {

    private static final Logger LOG = Loggers.get(JGitBlameCommand.class);

    private final PathResolver pathResolver;
    private final AnalysisWarnings analysisWarnings;
    private final boolean analyseSubmodules;

    public JGitBlameCommand(PathResolver pathResolver, AnalysisWarnings analysisWarnings, Configuration configuration) {

        this.pathResolver = pathResolver;
        this.analysisWarnings = analysisWarnings;
        analyseSubmodules = GitScmConfiguration.subModuleAnalysisEnabled(configuration);

        LOG.info("SCM submodules analysis and retrieving blame information is {}", analyseSubmodules ? "enabled" : "disabled");
    }

    @Override
    public void blame(BlameInput input, BlameOutput output) {

        File basedir = input.fileSystem().baseDir();
        LOG.debug("Building repository for base path", basedir.getAbsolutePath());
        try (Repository repository = JGitUtils.buildRepository(basedir.toPath()); Git git = Git.wrap(repository)) {
            File gitBaseDir = repository.getWorkTree();
            LOG.debug("The current repository base dir is {}", gitBaseDir.getAbsolutePath());

            if (cloneIsInvalid(gitBaseDir)) {
                return;
            }

            ForkJoinPool forkJoinPool = new ForkJoinPool(Runtime.getRuntime().availableProcessors(), new GitThreadFactory(), null, false);
            Stream<InputFile> stream = StreamSupport.stream(input.filesToBlame().spliterator(), true);

            forkJoinPool.submit(() -> stream.forEach(inputFile -> blame(output, git, gitBaseDir, (DefaultInputFile) inputFile)));

            waitForPoolShutDown(forkJoinPool, "Git blame for root repository interrupted");

            if (!git.submoduleStatus().call().isEmpty() && analyseSubmodules) {

                LOG.debug("Collecting blame information from submodules");
                LOG.debug("Submodules available {}", git.submoduleStatus().call().toString());

                ForkJoinPool subModulesForkPool = new ForkJoinPool(Runtime.getRuntime().availableProcessors(), new GitThreadFactory(), null, false);

                for (String submodule : git.submoduleStatus().call().keySet()) {

                    LOG.debug("Trying to collect blame information from submodule {}", submodule);

                    Repository submoduleRepository = SubmoduleWalk.getSubmoduleRepository(repository, submodule);
                    if (submoduleRepository != null) {

                        Git subModuleGit = Git.wrap(submoduleRepository);
                        File subModuleWorkTree = submoduleRepository.getWorkTree();

                        Stream<InputFile> submoduleStream = StreamSupport.stream(input.filesToBlame().spliterator(), true);
                        submoduleStream.forEach(inputFile -> blame(output, subModuleGit, subModuleWorkTree, (DefaultInputFile) inputFile));
                        subModulesForkPool.submit(() -> submoduleStream.forEach(inputFile -> blame(output, subModuleGit, subModuleWorkTree, (DefaultInputFile) inputFile)));
                    } else {
                        LOG.info("Submodule {} given, failed to get submodule repository, is it not checked out?", submodule);
                    }
                }

                waitForPoolShutDown(subModulesForkPool, "Git blame for submodules interrupted");
            }
        } catch (GitAPIException | IOException e) {
            LOG.error("Failed to access repository when collecting blame information", e);
        }
    }

    private void waitForPoolShutDown(ForkJoinPool forkJoinPool, String s) {
        try {
            forkJoinPool.shutdown();
            forkJoinPool.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            LOG.info(s);
            Thread.currentThread().interrupt();
        }
    }

    private boolean cloneIsInvalid(File gitBaseDir) {
        if (Files.isRegularFile(gitBaseDir.toPath().resolve(".git/objects/info/alternates"))) {
            LOG.info("This git repository references another local repository which is not well supported. SCM information might be missing for some files. "
                    + "You can avoid borrow objects from another local repository by not using --reference or --shared when cloning it.");
        }

        if (Files.isRegularFile(gitBaseDir.toPath().resolve(".git/shallow"))) {
            LOG.warn("Shallow clone detected, no blame information will be provided. "
                    + "You can convert to non-shallow with 'git fetch --unshallow'.");
            analysisWarnings.addUnique("Shallow clone detected during the analysis. "
                    + "Some files will miss SCM information. This will affect features like auto-assignment of issues. "
                    + "Please configure your build to disable shallow clone.");
            return true;
        }

        return false;
    }

    private void blame(BlameOutput output, Git git, File gitBaseDir, DefaultInputFile inputFile) {

        String filename = pathResolver.relativePath(gitBaseDir, inputFile.file());
        if (filename == null) {
            LOG.debug("Unable to blame file {}, not found under base directory {}", inputFile.getModuleRelativePath(), gitBaseDir.getAbsolutePath());
            return;
        }

        LOG.debug("Trying to collect blame information from file {}, base directory {}, input file {}", filename, gitBaseDir.getAbsolutePath(), inputFile.absolutePath());

        BlameResult blameResult;
        try {

            blameResult = git.blame()
                    // Equivalent to -w command line option
                    .setTextComparator(RawTextComparator.WS_IGNORE_ALL)
                    .setFilePath(filename).call();

            if (blameResult == null) {
                LOG.debug("Unable to blame file {}. It is probably a symlink or part of a submodule.",
                        filename);
                return;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to blame file " + inputFile.getModuleRelativePath(),
                    e);
        }

        LOG.debug("Collecting blame information from file {}", filename);

        List<BlameLine> blameLines = new ArrayList<>();
        for (int i = 0; i < blameResult.getResultContents().size(); i++) {

            if (blameResult.getSourceAuthor(i) == null || blameResult.getSourceCommit(i) == null) {
                LOG.debug("Unable to blame file {}. No blame info at line {}. Is file committed? [Author: {} Source commit: {}]",
                        inputFile.getModuleRelativePath(),
                        i + 1,
                        blameResult.getSourceAuthor(i),
                        blameResult.getSourceCommit(i));
                return;
            }

            blameLines.add(new BlameLine()
                    .date(blameResult.getSourceCommitter(i).getWhen())
                    .revision(blameResult.getSourceCommit(i).getName())
                    .author(blameResult.getSourceAuthor(i).getEmailAddress()));
        }

        if (blameLines.size() == inputFile.lines() - 1) {
            // SONARPLUGINS-3097 Git do not report blame on last empty line
            blameLines.add(blameLines.get(blameLines.size() - 1));
        }

        output.blameResult(inputFile, blameLines);
    }

}
