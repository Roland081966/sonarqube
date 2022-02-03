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
package org.sonar.server.pushapi.sonarlint;

import java.util.Set;
import javax.servlet.AsyncContext;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

public class SonarLintClientTest {

  private final AsyncContext firstContext = mock(AsyncContext.class);
  private final AsyncContext secondContext = mock(AsyncContext.class);

  @Test
  public void equals_twoClientsWithSameArgumentsAreEqual() {
    SonarLintClient first = new SonarLintClient(firstContext, Set.of(), Set.of());
    SonarLintClient second = new SonarLintClient(firstContext, Set.of(), Set.of());

    assertThat(first).isEqualTo(second);
  }

  @Test
  public void equals_twoClientsWithDifferentAsyncObjects() {
    SonarLintClient first = new SonarLintClient(firstContext, Set.of(), Set.of());
    SonarLintClient second = new SonarLintClient(secondContext, Set.of(), Set.of());

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  public void equals_twoClientsWithDifferentLanguages() {
    SonarLintClient first = new SonarLintClient(firstContext, Set.of(), Set.of("java"));
    SonarLintClient second = new SonarLintClient(firstContext, Set.of(), Set.of("cobol"));

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  public void equals_twoClientsWithDifferentProjectKeys() {
    SonarLintClient first = new SonarLintClient(firstContext, Set.of("project1", "project2"), Set.of());
    SonarLintClient second = new SonarLintClient(firstContext, Set.of("project1"), Set.of());

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  public void equals_secondClientIsNull() {
    SonarLintClient first = new SonarLintClient(firstContext, Set.of("project1", "project2"), Set.of());

    assertThat(first).isNotEqualTo(null);
  }

  @Test
  public void hashCode_producesSameHashesForEqualObjects() {
    SonarLintClient first = new SonarLintClient(firstContext, Set.of(), Set.of());
    SonarLintClient second = new SonarLintClient(firstContext, Set.of(), Set.of());

    assertThat(first).hasSameHashCodeAs(second);
  }
}