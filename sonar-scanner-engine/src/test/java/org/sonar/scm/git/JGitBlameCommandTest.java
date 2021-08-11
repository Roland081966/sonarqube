/*
 * SonarQube
 * Copyright (C) 2009-2021 SonarSource SA
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
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.fs.internal.DefaultFileSystem;
import org.sonar.api.batch.fs.internal.DefaultInputFile;
import org.sonar.api.batch.fs.internal.TestInputFileBuilder;
import org.sonar.api.batch.scm.BlameCommand.BlameInput;
import org.sonar.api.batch.scm.BlameCommand.BlameOutput;
import org.sonar.api.batch.scm.BlameLine;
import org.sonar.api.config.Configuration;
import org.sonar.api.notifications.AnalysisWarnings;
import org.sonar.api.scan.filesystem.PathResolver;
import org.sonar.api.utils.DateUtils;
import org.sonar.api.utils.MessageException;
import org.sonar.api.utils.System2;
import org.sonar.api.utils.log.LogTester;
import org.sonar.api.utils.log.LoggerLevel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;
import static org.mockito.Matchers.startsWith;
import static org.mockito.Mockito.*;
import static org.sonar.scm.git.Utils.javaUnzip;

public class JGitBlameCommandTest {

  private static final String DUMMY_JAVA = "src/main/java/org/dummy/Dummy.java";

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Rule
  public TemporaryFolder temp = new TemporaryFolder();

  @Rule
  public LogTester logTester = new LogTester();

  private final BlameInput input = mock(BlameInput.class);
  private File projectDir;

  @Before
  public void setUp() throws Exception {
    projectDir = temp.newFolder();
  }

  @Test
  public void testBlame() throws IOException {
    File projectDir = temp.newFolder();
    javaUnzip("dummy-git.zip", projectDir);

    JGitBlameCommand jGitBlameCommand = newJGitBlameCommand("true");

    File baseDir = new File(projectDir, "dummy-git");
    DefaultFileSystem fs = new DefaultFileSystem(baseDir);
    when(input.fileSystem()).thenReturn(fs);
    DefaultInputFile inputFile = new TestInputFileBuilder("foo", DUMMY_JAVA)
      .setModuleBaseDir(baseDir.toPath())
      .build();
    fs.add(inputFile);

    BlameOutput blameResult = mock(BlameOutput.class);
    when(input.filesToBlame()).thenReturn(Arrays.asList(inputFile));
    jGitBlameCommand.blame(input, blameResult);

    Date revisionDate1 = DateUtils.parseDateTime("2012-07-17T16:12:48+0200");
    String revision1 = "6b3aab35a3ea32c1636fee56f996e677653c48ea";
    String author1 = "david@gageot.net";

    // second commit, which has a commit date different than the author date
    Date revisionDate2 = DateUtils.parseDateTime("2015-05-19T13:31:09+0200");
    String revision2 = "0d269c1acfb8e6d4d33f3c43041eb87e0df0f5e7";
    String author2 = "duarte.meneses@sonarsource.com";

    List<BlameLine> expectedBlame = new LinkedList<>();
    for (int i = 0; i < 25; i++) {
      expectedBlame.add(new BlameLine().revision(revision1).date(revisionDate1).author(author1));
    }
    for (int i = 0; i < 3; i++) {
      expectedBlame.add(new BlameLine().revision(revision2).date(revisionDate2).author(author2));
    }
    for (int i = 0; i < 1; i++) {
      expectedBlame.add(new BlameLine().revision(revision1).date(revisionDate1).author(author1));
    }

    verify(blameResult).blameResult(inputFile, expectedBlame);
  }

  @Test
  public void properFailureIfNotAGitProject() throws IOException {
    File projectDir = temp.newFolder();
    javaUnzip("dummy-git.zip", projectDir);

    JGitBlameCommand jGitBlameCommand = newJGitBlameCommand("true");

    File baseDir = new File(projectDir, "dummy-git");

    // Delete .git
    FileUtils.forceDelete(new File(baseDir, ".git"));

    DefaultFileSystem fs = new DefaultFileSystem(baseDir);
    when(input.fileSystem()).thenReturn(fs);
    DefaultInputFile inputFile = new TestInputFileBuilder("foo", DUMMY_JAVA).build();
    fs.add(inputFile);

    BlameOutput blameResult = mock(BlameOutput.class);
    when(input.filesToBlame()).thenReturn(Arrays.asList(inputFile));

    thrown.expect(MessageException.class);
    thrown.expectMessage("Not inside a Git work tree: ");

    jGitBlameCommand.blame(input, blameResult);
  }

  @Test
  public void testBlameOnNestedModule() throws IOException {
    File projectDir = temp.newFolder();
    javaUnzip("dummy-git-nested.zip", projectDir);

    JGitBlameCommand jGitBlameCommand = newJGitBlameCommand("true");

    File baseDir = new File(projectDir, "dummy-git-nested/dummy-project");
    DefaultFileSystem fs = new DefaultFileSystem(baseDir);
    when(input.fileSystem()).thenReturn(fs);
    DefaultInputFile inputFile = new TestInputFileBuilder("foo", DUMMY_JAVA)
      .setModuleBaseDir(baseDir.toPath())
      .build();
    fs.add(inputFile);

    BlameOutput blameResult = mock(BlameOutput.class);
    when(input.filesToBlame()).thenReturn(Arrays.asList(inputFile));
    jGitBlameCommand.blame(input, blameResult);

    Date revisionDate = DateUtils.parseDateTime("2012-07-17T16:12:48+0200");
    String revision = "6b3aab35a3ea32c1636fee56f996e677653c48ea";
    String author = "david@gageot.net";
    verify(blameResult).blameResult(inputFile,
      Arrays.asList(
        new BlameLine().revision(revision).date(revisionDate).author(author),
        new BlameLine().revision(revision).date(revisionDate).author(author),
        new BlameLine().revision(revision).date(revisionDate).author(author),
        new BlameLine().revision(revision).date(revisionDate).author(author),
        new BlameLine().revision(revision).date(revisionDate).author(author),
        new BlameLine().revision(revision).date(revisionDate).author(author),
        new BlameLine().revision(revision).date(revisionDate).author(author),
        new BlameLine().revision(revision).date(revisionDate).author(author),
        new BlameLine().revision(revision).date(revisionDate).author(author),
        new BlameLine().revision(revision).date(revisionDate).author(author),
        new BlameLine().revision(revision).date(revisionDate).author(author),
        new BlameLine().revision(revision).date(revisionDate).author(author),
        new BlameLine().revision(revision).date(revisionDate).author(author),
        new BlameLine().revision(revision).date(revisionDate).author(author),
        new BlameLine().revision(revision).date(revisionDate).author(author),
        new BlameLine().revision(revision).date(revisionDate).author(author),
        new BlameLine().revision(revision).date(revisionDate).author(author),
        new BlameLine().revision(revision).date(revisionDate).author(author),
        new BlameLine().revision(revision).date(revisionDate).author(author),
        new BlameLine().revision(revision).date(revisionDate).author(author),
        new BlameLine().revision(revision).date(revisionDate).author(author),
        new BlameLine().revision(revision).date(revisionDate).author(author),
        new BlameLine().revision(revision).date(revisionDate).author(author),
        new BlameLine().revision(revision).date(revisionDate).author(author),
        new BlameLine().revision(revision).date(revisionDate).author(author),
        new BlameLine().revision(revision).date(revisionDate).author(author)));
  }

  @Test
  public void dontFailOnModifiedFile() throws IOException {
    File projectDir = temp.newFolder();
    javaUnzip("dummy-git.zip", projectDir);

    JGitBlameCommand jGitBlameCommand = newJGitBlameCommand("true");

    File baseDir = new File(projectDir, "dummy-git");
    DefaultFileSystem fs = new DefaultFileSystem(baseDir);
    when(input.fileSystem()).thenReturn(fs);
    String relativePath = DUMMY_JAVA;
    DefaultInputFile inputFile = new TestInputFileBuilder("foo", relativePath).build();
    fs.add(inputFile);

    // Emulate a modification
    Files.write(baseDir.toPath().resolve(relativePath), "modification and \n some new line".getBytes());

    BlameOutput blameResult = mock(BlameOutput.class);

    when(input.filesToBlame()).thenReturn(Arrays.asList(inputFile));
    jGitBlameCommand.blame(input, blameResult);
  }

  @Test
  public void dontFailOnNewFile() throws IOException {
    File projectDir = temp.newFolder();
    javaUnzip("dummy-git.zip", projectDir);

    JGitBlameCommand jGitBlameCommand = newJGitBlameCommand("true");

    File baseDir = new File(projectDir, "dummy-git");
    DefaultFileSystem fs = new DefaultFileSystem(baseDir);
    when(input.fileSystem()).thenReturn(fs);
    String relativePath = DUMMY_JAVA;
    String relativePath2 = "src/main/java/org/dummy/Dummy2.java";
    DefaultInputFile inputFile = new TestInputFileBuilder("foo", relativePath).build();
    fs.add(inputFile);
    DefaultInputFile inputFile2 = new TestInputFileBuilder("foo", relativePath2).build();
    fs.add(inputFile2);

    // Emulate a new file
    FileUtils.copyFile(new File(baseDir, relativePath), new File(baseDir, relativePath2));

    BlameOutput blameResult = mock(BlameOutput.class);

    when(input.filesToBlame()).thenReturn(Arrays.asList(inputFile, inputFile2));
    jGitBlameCommand.blame(input, blameResult);
  }

  @Test
  public void dontFailOnSymlink() throws IOException {
    assumeTrue(!System2.INSTANCE.isOsWindows());
    File projectDir = temp.newFolder();
    javaUnzip("dummy-git.zip", projectDir);

    JGitBlameCommand jGitBlameCommand = newJGitBlameCommand("true");

    File baseDir = new File(projectDir, "dummy-git");
    DefaultFileSystem fs = new DefaultFileSystem(baseDir);
    when(input.fileSystem()).thenReturn(fs);
    String relativePath = DUMMY_JAVA;
    String relativePath2 = "src/main/java/org/dummy/Dummy2.java";
    DefaultInputFile inputFile = new TestInputFileBuilder("foo", relativePath)
      .setModuleBaseDir(baseDir.toPath())
      .build();
    fs.add(inputFile);
    DefaultInputFile inputFile2 = new TestInputFileBuilder("foo", relativePath2)
      .setModuleBaseDir(baseDir.toPath())
      .build();
    fs.add(inputFile2);

    // Create symlink
    Files.createSymbolicLink(inputFile2.file().toPath(), inputFile.file().toPath());

    BlameOutput blameResult = mock(BlameOutput.class);

    when(input.filesToBlame()).thenReturn(Arrays.asList(inputFile, inputFile2));
    jGitBlameCommand.blame(input, blameResult);
  }

  @Test
  public void return_early_when_shallow_clone_detected() throws IOException {
    File projectDir = temp.newFolder();
    javaUnzip("shallow-git.zip", projectDir);

    File baseDir = new File(projectDir, "shallow-git");

    DefaultFileSystem fs = new DefaultFileSystem(baseDir);
    when(input.fileSystem()).thenReturn(fs);

    DefaultInputFile inputFile = new TestInputFileBuilder("foo", DUMMY_JAVA).build();
    when(input.filesToBlame()).thenReturn(Collections.singleton(inputFile));

    // register warning with default wrapper
    AnalysisWarnings analysisWarnings = mock(AnalysisWarnings.class);
    JGitBlameCommand jGitBlameCommand = new JGitBlameCommand(new PathResolver(), analysisWarnings, mock(org.sonar.api.config.Configuration.class));
    BlameOutput output = mock(BlameOutput.class);
    jGitBlameCommand.blame(input, output);

    assertThat(logTester.logs()).anyMatch(s -> s.contains("Shallow clone detected, no blame information will be provided."));
    verifyNoInteractions(output);

    verify(analysisWarnings).addUnique(startsWith("Shallow clone detected"));
  }

  @Test
  public void return_early_when_clone_with_reference_detected() throws IOException {
    File projectDir = temp.newFolder();
    javaUnzip("dummy-git-reference-clone.zip", projectDir);

    Path baseDir = projectDir.toPath().resolve("dummy-git2");

    DefaultFileSystem fs = new DefaultFileSystem(baseDir);
    when(input.fileSystem()).thenReturn(fs);

    DefaultInputFile inputFile = new TestInputFileBuilder("foo", DUMMY_JAVA).setModuleBaseDir(baseDir).build();
    when(input.filesToBlame()).thenReturn(Collections.singleton(inputFile));

    // register warning
    AnalysisWarnings analysisWarnings = mock(AnalysisWarnings.class);
    JGitBlameCommand jGitBlameCommand = new JGitBlameCommand(new PathResolver(), analysisWarnings, mock(org.sonar.api.config.Configuration.class));
    TestBlameOutput output = new TestBlameOutput();
    jGitBlameCommand.blame(input, output);

    assertThat(logTester.logs()).anyMatch(s -> s.contains("This git repository references another local repository which is not well supported"));

    // contains commits referenced from the old clone and commits in the new clone
    assertThat(output.blame.keySet()).contains(inputFile);
    assertThat(output.blame.get(inputFile).stream().map(BlameLine::revision))
      .containsOnly("6b3aab35a3ea32c1636fee56f996e677653c48ea", "843c7c30d7ebd9a479e8f1daead91036c75cbc4e", "0d269c1acfb8e6d4d33f3c43041eb87e0df0f5e7");
    verifyNoInteractions(analysisWarnings);
  }
  @Test
  public void shouldIgnoreSubmodulesWhenNotCheckedOut() throws IOException {

    javaUnzip("submodule-git.zip", projectDir);

    JGitBlameCommand jGitBlameCommand = newJGitBlameCommand("true");

    File baseDir = new File(projectDir, "submodule-git");

    FileUtils.deleteDirectory(new File(baseDir, "lib"));

    DefaultFileSystem fs = new DefaultFileSystem(baseDir);
    when(input.fileSystem()).thenReturn(fs);
    DefaultInputFile inputFile = new TestInputFileBuilder("foo", "lib/file")
            .setModuleBaseDir(baseDir.toPath())
            .build();
    fs.add(inputFile);

    GitIgnoreCommand ignoreCommand = new GitIgnoreCommand(mock(Configuration.class));
    ignoreCommand.init(baseDir.toPath());

    BlameOutput blameResult = mock(BlameOutput.class);
    when(input.filesToBlame()).thenReturn(Collections.singletonList(inputFile));

    jGitBlameCommand.blame(input, blameResult);

    List<BlameLine> expectedBlame = new LinkedList<>();
    verify(blameResult, never()).blameResult(inputFile, expectedBlame);
  }

  @Test
  public void shouldNotRetrieveBlameInformationWhenFileIsModifiedButNotCommitted() throws IOException {

    javaUnzip("dummy-git.zip", projectDir);

    JGitBlameCommand jGitBlameCommand = newJGitBlameCommand("true");

    File baseDir = new File(projectDir, "dummy-git");
    DefaultFileSystem fs = new DefaultFileSystem(baseDir);
    when(input.fileSystem()).thenReturn(fs);
    String modifiedFile = "src/main/java/org/dummy/AnotherDummy.java";
    DefaultInputFile inputFile = new TestInputFileBuilder("foo", modifiedFile)
            .setModuleBaseDir(baseDir.toPath())
            .build();
    fs.add(inputFile);

    // Emulate a modification
    Files.write(baseDir.toPath().resolve(modifiedFile), "modification and \n some new line".getBytes());

    BlameOutput blameResult = mock(BlameOutput.class);

    when(input.filesToBlame()).thenReturn(Collections.singletonList(inputFile));
    jGitBlameCommand.blame(input, blameResult);
    verify(blameResult, never()).blameResult(any(),argThat(List::isEmpty));
  }

  @Test
  public void shouldNotRetrieveBlameInformationWhenFileIsAddedButNotCommitted() throws IOException {

    javaUnzip("dummy-git.zip", projectDir);

    JGitBlameCommand jGitBlameCommand = newJGitBlameCommand("true");

    File baseDir = new File(projectDir, "dummy-git");
    DefaultFileSystem fs = new DefaultFileSystem(baseDir);
    when(input.fileSystem()).thenReturn(fs);
    DefaultInputFile inputFile = new TestInputFileBuilder("foo", DUMMY_JAVA)
            .setModuleBaseDir(baseDir.toPath())
            .build();
    fs.add(inputFile);

    // Emulate file creation
    Files.write(baseDir.toPath().resolve(DUMMY_JAVA), "modification and \n some new line".getBytes());

    try (Repository repository = JGitUtils.buildRepository(baseDir.toPath()); Git git = Git.wrap(repository)) {
      git.add().addFilepattern(DUMMY_JAVA).call();
    } catch ( GitAPIException e) {
      fail("Failed when adding file to repository");
    }

    BlameOutput blameResult = mock(BlameOutput.class);
    when(input.filesToBlame()).thenReturn(Collections.singletonList(inputFile));

    jGitBlameCommand.blame(input, blameResult);

    verify(blameResult, never()).blameResult(any(),argThat(List::isEmpty));
  }

  @Test
  public void shouldRetrieveBlameInformationWhenFileIsModifiedAndCommitted() throws IOException {

    javaUnzip("dummy-git.zip", projectDir);

    JGitBlameCommand jGitBlameCommand = newJGitBlameCommand("true");

    File baseDir = new File(projectDir, "dummy-git");
    DefaultFileSystem fs = new DefaultFileSystem(baseDir);
    when(input.fileSystem()).thenReturn(fs);
    String modifiedFile = "src/main/java/org/dummy/AnotherDummy.java";
    DefaultInputFile inputFile = new TestInputFileBuilder("foo", modifiedFile)
            .setModuleBaseDir(baseDir.toPath())
            .build();
    fs.add(inputFile);

    // Emulate a modification
    Files.write(baseDir.toPath().resolve(modifiedFile), "modification and \n some new line".getBytes());

    addAndCommitFile(baseDir, modifiedFile);

    BlameOutput blameResult = mock(BlameOutput.class);
    when(input.filesToBlame()).thenReturn(Collections.singletonList(inputFile));

    jGitBlameCommand.blame(input, blameResult);

    verify(blameResult).blameResult(any(),argThat(list -> list.size() == 2));
  }

  @Test
  public void shouldRetrieveBlameInformationWhenFileIsAddedAndCommitted() throws IOException {

    javaUnzip("dummy-git.zip", projectDir);

    JGitBlameCommand jGitBlameCommand = newJGitBlameCommand("true");

    File baseDir = new File(projectDir, "dummy-git");
    DefaultFileSystem fs = new DefaultFileSystem(baseDir);
    when(input.fileSystem()).thenReturn(fs);
    DefaultInputFile inputFile = new TestInputFileBuilder("foo", DUMMY_JAVA)
            .setModuleBaseDir(baseDir.toPath())
            .build();
    fs.add(inputFile);

    // Emulate a modification
    Files.write(baseDir.toPath().resolve(DUMMY_JAVA), "Added new file\n Line 1\nLine 2".getBytes());

    addAndCommitFile(baseDir, DUMMY_JAVA);

    BlameOutput blameResult = mock(BlameOutput.class);
    when(input.filesToBlame()).thenReturn(Collections.singletonList(inputFile));

    jGitBlameCommand.blame(input, blameResult);

    verify(blameResult).blameResult(any(),argThat(list -> list.size() == 3));
  }

  @Test
  public void shouldRetrieveBlameInformationForAllFilesWhenFileIsAddedAndCommitted() throws IOException {
    javaUnzip("dummy-git.zip", projectDir);

    JGitBlameCommand jGitBlameCommand = newJGitBlameCommand("true");

    File baseDir = new File(projectDir, "dummy-git");
    DefaultFileSystem fs = new DefaultFileSystem(baseDir);
    when(input.fileSystem()).thenReturn(fs);
    DefaultInputFile newFile = new TestInputFileBuilder("foo", DUMMY_JAVA)
            .setModuleBaseDir(baseDir.toPath())
            .build();
    fs.add(newFile);

    DefaultInputFile existingFile = new TestInputFileBuilder("foo", "src/main/java/org/dummy/AnotherDummy.java")
            .setModuleBaseDir(baseDir.toPath())
            .build();
    fs.add(existingFile);

    // Emulate creation of new file
    Files.write(baseDir.toPath().resolve(DUMMY_JAVA), "Added new file\n Line 1\nLine 2".getBytes());

    addAndCommitFile(baseDir, DUMMY_JAVA);

    BlameOutput blameResult = mock(BlameOutput.class);
    when(input.filesToBlame()).thenReturn(Arrays.asList(newFile, existingFile));

    jGitBlameCommand.blame(input, blameResult);

    verify(blameResult).blameResult(eq(newFile),argThat(list -> list.size() == 3));
    verify(blameResult).blameResult(eq(existingFile),argThat(list -> list.size() == 29));
  }

  @Test
  public void shouldNotRetrieveBlameInformationForADeletedFileThatIsNotCommitted() throws IOException {
    javaUnzip("dummy-git.zip", projectDir);

    JGitBlameCommand jGitBlameCommand = newJGitBlameCommand("true");

    File baseDir = new File(projectDir, "dummy-git");
    DefaultFileSystem fs = new DefaultFileSystem(baseDir);
    when(input.fileSystem()).thenReturn(fs);

    DefaultInputFile existingFile = new TestInputFileBuilder("foo", "src/main/java/org/dummy/AnotherDummy.java")
            .setModuleBaseDir(baseDir.toPath())
            .build();
    fs.add(existingFile);

    FileUtils.forceDelete(new File(baseDir, "src/main/java/org/dummy/AnotherDummy.java"));

    BlameOutput blameResult = mock(BlameOutput.class);
    when(input.filesToBlame()).thenReturn(Collections.singletonList(existingFile));

    jGitBlameCommand.blame(input, blameResult);

    verify(blameResult, never()).blameResult(existingFile,new LinkedList<>());
  }

  @Test
  public void shouldLogFilesWhenRelativePathCanNotBeEvaluated() throws IOException {

    javaUnzip("dummy-git.zip", projectDir);

    JGitBlameCommand jGitBlameCommand = newJGitBlameCommand("false");

    File baseDir = new File(projectDir, "dummy-git");
    DefaultFileSystem fs = new DefaultFileSystem(baseDir);
    when(input.fileSystem()).thenReturn(fs);

    DefaultInputFile nonExistingFile = new TestInputFileBuilder("foo", "src/main/java/org/dummy/AnotherDummy.java")
            .build();
    fs.add(nonExistingFile);

    BlameOutput blameResult = mock(BlameOutput.class);
    when(input.filesToBlame()).thenReturn(Collections.singletonList(nonExistingFile));

    logTester.setLevel(LoggerLevel.DEBUG);

    jGitBlameCommand.blame(input, blameResult);

    assertThat(logTester.logs(LoggerLevel.DEBUG).get(2)).contains("Unable to blame file src/main/java/org/dummy/AnotherDummy.java, not found under base directory");
  }

  private void addAndCommitFile(File baseDir, String dummyJava) {
    try (Repository repository = JGitUtils.buildRepository(baseDir.toPath()); Git git = Git.wrap(repository)) {
      git.add().addFilepattern(dummyJava).call();
      git.commit().setMessage("Dummy commit").setAuthor("dummy", "dummy@dummy.com").call();
    } catch (GitAPIException e) {
      fail("Failed when adding file to repository");
    }
  }

  private JGitBlameCommand newJGitBlameCommand(String submoduleEnabled) {
    Configuration configuration = mock(Configuration.class);
    when(configuration.get("sonar.scm.submodules.included")).thenReturn(java.util.Optional.of(submoduleEnabled));
    return new JGitBlameCommand(new PathResolver(), mock(AnalysisWarnings.class), configuration);
  }

  private static class TestBlameOutput implements BlameOutput {
    private Map<InputFile, List<BlameLine>> blame = new LinkedHashMap<>();

    @Override public void blameResult(InputFile inputFile, List<BlameLine> list) {
      blame.put(inputFile, list);
    }
  }

}
