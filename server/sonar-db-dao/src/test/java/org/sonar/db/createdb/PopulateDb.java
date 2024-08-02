/*
 * SonarQube
 * Copyright (C) 2009-2024 SonarSource SA
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
package org.sonar.db.createdb;

import com.google.common.collect.Streams;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;
import org.sonar.api.measures.CoreMetrics;
import org.sonar.api.rules.RuleType;
import org.sonar.api.utils.System2;
import org.sonar.db.DbSession;
import org.sonar.db.DbTester;
import org.sonar.db.ce.CeTaskMessageType;
import org.sonar.db.component.BranchDto;
import org.sonar.db.component.ComponentDto;
import org.sonar.db.component.ComponentTesting;
import org.sonar.db.component.SnapshotDto;
import org.sonar.db.component.SnapshotTesting;
import org.sonar.db.issue.IssueDto;
import org.sonar.db.metric.MetricDto;
import org.sonar.db.project.ProjectDto;
import org.sonar.db.rule.RuleDto;
import org.sonar.db.user.TokenType;
import org.sonar.db.user.UserDto;

import static org.sonar.db.component.BranchType.BRANCH;

public class PopulateDb {

  private static final DbTester dbTester = createDbTester();

  private static final Random random = new Random();

  private static final long NUMBER_OF_USERS = 100_000L;

  public static void main(String[] args) {
    // read base data
    final Map<String, MetricDto> metricDtosByKey;
    final List<ProjectDto> allProjects;
    final Set<RuleDto> enabledRules;

    DbSession initSession = dbTester.getSession();
    metricDtosByKey = dbTester.getDbClient().metricDao().selectAll(initSession).stream().collect(
      Collectors.toMap(MetricDto::getKey, Function.identity())
    );
    allProjects = dbTester.getDbClient().projectDao().selectProjects(initSession);
    enabledRules = new HashSet<>(dbTester.getDbClient().ruleDao().selectEnabled(dbTester.getSession()));

    ProjectDto projectDto = generateProject(new SqContext(enabledRules, metricDtosByKey, dbTester),
      new ProjectStructure("project " + (allProjects.size() + 1), 10, 10, 10, 2, 5));
    allProjects.add(projectDto);

    createUsers(allProjects);
    // close database connection
    dbTester.getDbClient().getDatabase().stop();
  }

  private static List<UserDto> createUsers(List<ProjectDto> allProjects) {
    List<UserDto> allUsers = new ArrayList<>();
    for (int i = 0; i < NUMBER_OF_USERS; i++) {
      UserDto userDto = dbTester.users().insertUserRealistic();
      allUsers.add(userDto);
      ProjectDto projectDto = random.nextBoolean() ? null : allProjects.get(random.nextInt(allProjects.size()));
      if (i % 60 == 0 && projectDto != null) {
        createUserTokensDto(userDto, projectDto);
      }
      if (i % 50 == 5 && projectDto != null) {
        createUserDismissedMessages(userDto, projectDto);
      }
    }
    return allUsers;
  }

  private static void createUserDismissedMessages(UserDto userDto, ProjectDto projectDto) {
    CeTaskMessageType type = random.nextBoolean() ? CeTaskMessageType.GENERIC : CeTaskMessageType.SUGGEST_DEVELOPER_EDITION_UPGRADE;
    dbTester.users().insertUserDismissedMessage(userDto, projectDto, type);
  }

  private static void createUserTokensDto(UserDto userDto, ProjectDto randomProject) {
    long now = System.currentTimeMillis();
    Long expirationDate = random.nextBoolean() ? now + 123_123 : null;
    dbTester.users().insertToken(userDto, a -> a.setCreatedAt(now).setExpirationDate(expirationDate).setProjectKey(randomProject.getKey())
      .setLastConnectionDate(now).setType(randomProject.getKey() != null ? TokenType.PROJECT_ANALYSIS_TOKEN.name() :
        TokenType.USER_TOKEN.name()));
  }

  private record SqContext(Set<RuleDto> rules, Map<String, MetricDto> metricDtosByKey, DbTester dbTester) {
    public RuleDto findNotSecurityHotspotRule() {
      return rules.stream().filter(r -> r.getType() != RuleType.SECURITY_HOTSPOT.getDbConstant()).findAny().orElseThrow();
    }
  }

  private static @NotNull DbTester createDbTester() {
    System.setProperty("sonar.jdbc.url", "jdbc:postgresql://localhost:5432/sonarqube");
    System.setProperty("sonar.jdbc.username", "sonarqube");
    System.setProperty("sonar.jdbc.password", "sonarqube");
    System.setProperty("sonar.jdbc.dialect", "postgresql");

    return DbTester.create(System2.INSTANCE);
  }

  private record ProjectStructure(String projectName, int branchPerProject, int filePerBranch, int issuePerFile, int issueChangePerIssue,
                                  int snapshotPerBranch) {
  }

  private record BranchAndComponentDto(BranchDto branch, ComponentDto compo) {
  }

  private static ProjectDto generateProject(SqContext sqContext, ProjectStructure pj) {
    ComponentDto projectCompoDto = sqContext.dbTester.components().insertPublicProject(p -> p.setName(pj.projectName));
    ProjectDto projectDto = sqContext.dbTester.components().getProjectDto(projectCompoDto);


    Streams.concat(
        // main branch
        Stream.of(new BranchAndComponentDto(
          sqContext.dbTester.getDbClient().branchDao().selectByBranchKey(sqContext.dbTester.getSession(), projectDto.getUuid(), "main").orElseThrow(),
          projectCompoDto)),
        // other branches
        Stream.generate(() -> {
          var branchDto = ComponentTesting.newBranchDto(projectDto.getUuid(), BRANCH);
          return new BranchAndComponentDto(
            branchDto,
            sqContext.dbTester.components().insertProjectBranch(projectDto, branchDto)
          );
        }))
      // until there are enough branches generated
      .limit(pj.branchPerProject)
      // for every branche (main included)
      .forEach(branchAndComponentDto -> {
        // for every file in branch
        for (int file = 0; file < pj.filePerBranch; file++) {
          ComponentDto fileComponentDto = sqContext.dbTester.components().insertFile(branchAndComponentDto.compo);
          // for every issue in file
          for (int issue = 0; issue < pj.issuePerFile; issue++) {
            IssueDto issueDto = sqContext.dbTester.issues().insertIssue(sqContext.findNotSecurityHotspotRule(), branchAndComponentDto.compo, fileComponentDto);
            // for every issue change in issue
            for (int issueChange = 0; issueChange < pj.issueChangePerIssue; issueChange++) {
              sqContext.dbTester.issues().insertChange(issueDto);
            }
          }
          // create live measure for this file
          fileLiveMeasureMetrics.stream()
            .map(sqContext.metricDtosByKey::get)
            .forEach(metricDto -> sqContext.dbTester().measures().insertLiveMeasureWithSensibleValues(fileComponentDto, metricDto));
        }

        // create live measure for the branch
        projectLiveMeasureMetrics.stream()
          .map(sqContext.metricDtosByKey::get)
          .forEach(metricDto -> sqContext.dbTester().measures().insertLiveMeasureWithSensibleValues(branchAndComponentDto.compo, metricDto));

        long time = System2.INSTANCE.now();
        List<SnapshotDto> snapshots = new ArrayList<>();
        // for every snapshot on the current branch
        for (int snapshot = 0; snapshot < pj.snapshotPerBranch; snapshot++) {
          SnapshotDto snapshotDto = SnapshotTesting.newAnalysis(branchAndComponentDto.branch);
          snapshotDto.setLast(false);
          snapshotDto.setCreatedAt(time);
          time -= 10_000_000;
          snapshots.add(snapshotDto);
          // insert project measure for the snapshot
          projectProjectMeasureMetrics.stream()
            .map(sqContext.metricDtosByKey::get)
            .forEach(metricDto -> sqContext.dbTester().measures().insertMeasureWithSensibleValues(branchAndComponentDto.compo, snapshotDto, metricDto));
        }
        SnapshotDto lastSnapshotDto = snapshots.get(0);
        lastSnapshotDto.setLast(true);
        sqContext.dbTester.components().insertSnapshots(snapshots.toArray(new SnapshotDto[0]));
      });

    return projectDto;
  }

  private static final List<String> projectLiveMeasureMetrics = List.of(
    CoreMetrics.LINES_KEY,
    CoreMetrics.NCLOC_KEY,
    CoreMetrics.NEW_LINES_KEY,
    CoreMetrics.NCLOC_LANGUAGE_DISTRIBUTION_KEY,
    CoreMetrics.CLASSES_KEY,
    CoreMetrics.FILES_KEY,
    CoreMetrics.FUNCTIONS_KEY,
    CoreMetrics.STATEMENTS_KEY,
    CoreMetrics.COMMENT_LINES_KEY,
    CoreMetrics.COMMENT_LINES_DENSITY_KEY,
    CoreMetrics.COMPLEXITY_KEY,
    CoreMetrics.COGNITIVE_COMPLEXITY_KEY,
    CoreMetrics.COVERAGE_KEY,
    CoreMetrics.LINES_TO_COVER_KEY,
    CoreMetrics.NEW_LINES_TO_COVER_KEY,
    CoreMetrics.NEW_UNCOVERED_LINES_KEY,
    CoreMetrics.LINE_COVERAGE_KEY,
    CoreMetrics.NEW_CONDITIONS_TO_COVER_KEY,
    CoreMetrics.NEW_UNCOVERED_CONDITIONS_KEY,
    CoreMetrics.DUPLICATED_LINES_KEY,
    CoreMetrics.NEW_DUPLICATED_LINES_KEY,
    CoreMetrics.UNCOVERED_LINES_KEY,
    CoreMetrics.DUPLICATED_BLOCKS_KEY,
    CoreMetrics.NEW_BLOCKS_DUPLICATED_KEY,
    CoreMetrics.DUPLICATED_FILES_KEY,
    CoreMetrics.DUPLICATED_LINES_DENSITY_KEY,
    CoreMetrics.NEW_DUPLICATED_LINES_DENSITY_KEY,
    CoreMetrics.VIOLATIONS_KEY,
    CoreMetrics.BLOCKER_VIOLATIONS_KEY,
    CoreMetrics.CRITICAL_VIOLATIONS_KEY,
    CoreMetrics.MAJOR_VIOLATIONS_KEY,
    CoreMetrics.MINOR_VIOLATIONS_KEY,
    CoreMetrics.INFO_VIOLATIONS_KEY,
    CoreMetrics.NEW_VIOLATIONS_KEY,
    CoreMetrics.NEW_BLOCKER_VIOLATIONS_KEY,
    CoreMetrics.NEW_CRITICAL_VIOLATIONS_KEY,
    CoreMetrics.NEW_MAJOR_VIOLATIONS_KEY,
    CoreMetrics.NEW_MINOR_VIOLATIONS_KEY,
    CoreMetrics.NEW_INFO_VIOLATIONS_KEY,
    CoreMetrics.FALSE_POSITIVE_ISSUES_KEY,
    CoreMetrics.WONT_FIX_ISSUES_KEY,
    CoreMetrics.OPEN_ISSUES_KEY,
    CoreMetrics.REOPENED_ISSUES_KEY,
    CoreMetrics.CONFIRMED_ISSUES_KEY,
    CoreMetrics.CODE_SMELLS_KEY,
    CoreMetrics.NEW_CODE_SMELLS_KEY,
    CoreMetrics.BUGS_KEY,
    CoreMetrics.NEW_BUGS_KEY,
    CoreMetrics.VULNERABILITIES_KEY,
    CoreMetrics.NEW_VULNERABILITIES_KEY,
    CoreMetrics.SECURITY_HOTSPOTS_KEY,
    CoreMetrics.NEW_SECURITY_HOTSPOTS_KEY,
    CoreMetrics.TECHNICAL_DEBT_KEY,
    CoreMetrics.NEW_TECHNICAL_DEBT_KEY,
    CoreMetrics.SQALE_RATING_KEY,
    CoreMetrics.NEW_MAINTAINABILITY_RATING_KEY,
    CoreMetrics.DEVELOPMENT_COST_KEY,
    CoreMetrics.NEW_DEVELOPMENT_COST_KEY,
    CoreMetrics.SQALE_DEBT_RATIO_KEY,
    CoreMetrics.NEW_SQALE_DEBT_RATIO_KEY,
    CoreMetrics.EFFORT_TO_REACH_MAINTAINABILITY_RATING_A_KEY,
    CoreMetrics.RELIABILITY_REMEDIATION_EFFORT_KEY,
    CoreMetrics.NEW_RELIABILITY_REMEDIATION_EFFORT_KEY,
    CoreMetrics.RELIABILITY_RATING_KEY,
    CoreMetrics.NEW_RELIABILITY_RATING_KEY,
    CoreMetrics.SECURITY_REMEDIATION_EFFORT_KEY,
    CoreMetrics.NEW_SECURITY_REMEDIATION_EFFORT_KEY,
    CoreMetrics.SECURITY_RATING_KEY,
    CoreMetrics.NEW_SECURITY_RATING_KEY,
    CoreMetrics.SECURITY_REVIEW_RATING_KEY,
    CoreMetrics.NEW_SECURITY_REVIEW_RATING_KEY,
    CoreMetrics.SECURITY_HOTSPOTS_REVIEWED_STATUS_KEY,
    CoreMetrics.SECURITY_HOTSPOTS_TO_REVIEW_STATUS_KEY,
    CoreMetrics.NEW_SECURITY_HOTSPOTS_REVIEWED_STATUS_KEY,
    CoreMetrics.NEW_SECURITY_HOTSPOTS_TO_REVIEW_STATUS_KEY,
    CoreMetrics.ALERT_STATUS_KEY,
    CoreMetrics.QUALITY_GATE_DETAILS_KEY,
    CoreMetrics.LAST_COMMIT_DATE_KEY,
    CoreMetrics.ANALYSIS_FROM_SONARQUBE_9_4_KEY
  );

  private static final List<String> fileLiveMeasureMetrics = List.of(
    CoreMetrics.LINE_COVERAGE_KEY,
    CoreMetrics.COMMENT_LINES_KEY,
    CoreMetrics.VIOLATIONS_KEY,
    CoreMetrics.LAST_COMMIT_DATE_KEY,
    CoreMetrics.MAJOR_VIOLATIONS_KEY,
    CoreMetrics.DEVELOPMENT_COST_KEY,
    CoreMetrics.FUNCTIONS_KEY,
    CoreMetrics.VULNERABILITIES_KEY,
    CoreMetrics.EXECUTABLE_LINES_DATA_KEY,
    CoreMetrics.TECHNICAL_DEBT_KEY,
    CoreMetrics.LINES_KEY,
    CoreMetrics.CODE_SMELLS_KEY,
    CoreMetrics.UNCOVERED_LINES_KEY,
    CoreMetrics.COVERAGE_KEY,
    CoreMetrics.SQALE_RATING_KEY,
    CoreMetrics.COGNITIVE_COMPLEXITY_KEY,
    CoreMetrics.NCLOC_KEY,
    CoreMetrics.STATEMENTS_KEY,
    CoreMetrics.COMMENT_LINES_DENSITY_KEY,
    CoreMetrics.LINES_TO_COVER_KEY,
    CoreMetrics.CLASSES_KEY,
    CoreMetrics.EFFORT_TO_REACH_MAINTAINABILITY_RATING_A_KEY,
    CoreMetrics.NCLOC_LANGUAGE_DISTRIBUTION_KEY,
    CoreMetrics.NCLOC_DATA_KEY,
    CoreMetrics.RELIABILITY_RATING_KEY,
    CoreMetrics.MINOR_VIOLATIONS_KEY,
    CoreMetrics.SECURITY_REVIEW_RATING_KEY,
    CoreMetrics.BLOCKER_VIOLATIONS_KEY,
    CoreMetrics.SQALE_DEBT_RATIO_KEY,
    CoreMetrics.COMPLEXITY_KEY,
    CoreMetrics.SECURITY_RATING_KEY,
    CoreMetrics.OPEN_ISSUES_KEY,
    CoreMetrics.SECURITY_REMEDIATION_EFFORT_KEY,
    CoreMetrics.FILES_KEY,
    CoreMetrics.NEW_DUPLICATED_LINES_KEY,
    CoreMetrics.NEW_UNCOVERED_CONDITIONS_KEY,
    CoreMetrics.NEW_BLOCKS_DUPLICATED_KEY,
    CoreMetrics.NEW_CONDITIONS_TO_COVER_KEY,
    CoreMetrics.NEW_LINES_TO_COVER_KEY,
    CoreMetrics.NEW_UNCOVERED_LINES_KEY,
    CoreMetrics.NEW_LINES_KEY
  );

  private static final List<String> projectProjectMeasureMetrics = List.of(
    CoreMetrics.LINE_COVERAGE_KEY,
    CoreMetrics.COMMENT_LINES_KEY,
    CoreMetrics.VIOLATIONS_KEY,
    CoreMetrics.LAST_COMMIT_DATE_KEY,
    CoreMetrics.MAJOR_VIOLATIONS_KEY,
    CoreMetrics.DUPLICATED_FILES_KEY,
    CoreMetrics.DEVELOPMENT_COST_KEY,
    CoreMetrics.INFO_VIOLATIONS_KEY,
    CoreMetrics.FUNCTIONS_KEY,
    CoreMetrics.VULNERABILITIES_KEY,
    CoreMetrics.ANALYSIS_FROM_SONARQUBE_9_4_KEY,
    CoreMetrics.FALSE_POSITIVE_ISSUES_KEY,
    CoreMetrics.CRITICAL_VIOLATIONS_KEY,
    CoreMetrics.TECHNICAL_DEBT_KEY,
    CoreMetrics.LINES_KEY,
    CoreMetrics.RELIABILITY_REMEDIATION_EFFORT_KEY,
    CoreMetrics.CODE_SMELLS_KEY,
    CoreMetrics.UNCOVERED_LINES_KEY,
    CoreMetrics.COVERAGE_KEY,
    CoreMetrics.SQALE_RATING_KEY,
    CoreMetrics.COGNITIVE_COMPLEXITY_KEY,
    CoreMetrics.DUPLICATED_BLOCKS_KEY,
    CoreMetrics.NCLOC_KEY,
    CoreMetrics.STATEMENTS_KEY,
    CoreMetrics.SECURITY_HOTSPOTS_KEY,
    CoreMetrics.COMMENT_LINES_DENSITY_KEY,
    CoreMetrics.BUGS_KEY,
    CoreMetrics.LINES_TO_COVER_KEY,
    CoreMetrics.CLASSES_KEY,
    CoreMetrics.EFFORT_TO_REACH_MAINTAINABILITY_RATING_A_KEY,
    CoreMetrics.NCLOC_LANGUAGE_DISTRIBUTION_KEY,
    CoreMetrics.RELIABILITY_RATING_KEY,
    CoreMetrics.MINOR_VIOLATIONS_KEY,
    CoreMetrics.DUPLICATED_LINES_DENSITY_KEY,
    CoreMetrics.WONT_FIX_ISSUES_KEY,
    CoreMetrics.SECURITY_REVIEW_RATING_KEY,
    CoreMetrics.BLOCKER_VIOLATIONS_KEY,
    CoreMetrics.CONFIRMED_ISSUES_KEY,
    CoreMetrics.DUPLICATED_LINES_KEY,
    CoreMetrics.ALERT_STATUS_KEY,
    CoreMetrics.SQALE_DEBT_RATIO_KEY,
    CoreMetrics.COMPLEXITY_KEY,
    CoreMetrics.SECURITY_RATING_KEY,
    CoreMetrics.OPEN_ISSUES_KEY,
    CoreMetrics.SECURITY_REMEDIATION_EFFORT_KEY,
    CoreMetrics.QUALITY_GATE_DETAILS_KEY,
    CoreMetrics.REOPENED_ISSUES_KEY,
    CoreMetrics.FILES_KEY
  );
}
