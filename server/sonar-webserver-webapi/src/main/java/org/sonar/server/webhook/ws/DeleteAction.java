/*
 * SonarQube
 * Copyright (C) 2009-2025 SonarSource SA
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
package org.sonar.server.webhook.ws;

import java.util.Optional;
import org.sonar.api.server.ws.Request;
import org.sonar.api.server.ws.Response;
import org.sonar.api.server.ws.WebService;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.project.ProjectDto;
import org.sonar.db.webhook.WebhookDto;
import org.sonar.server.user.UserSession;

import static java.lang.String.format;
import static org.sonar.server.exceptions.NotFoundException.checkFoundWithOptional;
import static org.sonar.server.webhook.ws.WebhooksWsParameters.DELETE_ACTION;
import static org.sonar.server.webhook.ws.WebhooksWsParameters.KEY_PARAM;
import static org.sonar.server.webhook.ws.WebhooksWsParameters.KEY_PARAM_MAXIMUM_LENGTH;
import static org.sonar.server.ws.KeyExamples.KEY_PROJECT_EXAMPLE_001;

public class DeleteAction implements WebhooksWsAction {

  private final DbClient dbClient;
  private final UserSession userSession;
  private final WebhookSupport webhookSupport;

  public DeleteAction(DbClient dbClient, UserSession userSession, WebhookSupport webhookSupport) {
    this.dbClient = dbClient;
    this.userSession = userSession;
    this.webhookSupport = webhookSupport;
  }

  @Override
  public void define(WebService.NewController controller) {
    WebService.NewAction action = controller.createAction(DELETE_ACTION)
      .setPost(true)
      .setDescription("Delete a Webhook.<br>" +
        "Requires 'Administer' permission on the specified project, or global 'Administer' permission.")
      .setSince("7.1")
      .setHandler(this);

    action.createParam(KEY_PARAM)
      .setRequired(true)
      .setMaximumLength(KEY_PARAM_MAXIMUM_LENGTH)
      .setDescription("The key of the webhook to be deleted, " +
        "auto-generated value can be obtained through api/webhooks/create or api/webhooks/list")
      .setExampleValue(KEY_PROJECT_EXAMPLE_001);
  }

  @Override
  public void handle(Request request, Response response) throws Exception {
    userSession.checkLoggedIn();

    String webhookKey = request.param(KEY_PARAM);

    try (DbSession dbSession = dbClient.openSession(false)) {
      Optional<WebhookDto> dtoOptional = dbClient.webhookDao().selectByUuid(dbSession, webhookKey);
      WebhookDto webhookDto = checkFoundWithOptional(dtoOptional, "No webhook with key '%s'", webhookKey);

      String projectUuid = webhookDto.getProjectUuid();
      if (projectUuid != null) {
        Optional<ProjectDto> optionalDto = dbClient.projectDao().selectByUuid(dbSession, projectUuid);
        ProjectDto projectDto = optionalDto.orElseThrow(() -> new IllegalStateException(format("the requested project '%s' was not found", projectUuid)));
        webhookSupport.checkPermission(projectDto);
        deleteWebhook(dbSession, webhookDto);
      } else {
        webhookSupport.checkPermission();
        deleteWebhook(dbSession, webhookDto);
      }

      dbSession.commit();
    }

    response.noContent();
  }

  private void deleteWebhook(DbSession dbSession, WebhookDto webhookDto) {
    dbClient.webhookDeliveryDao().deleteByWebhook(dbSession, webhookDto);
    dbClient.webhookDao().delete(dbSession, webhookDto.getUuid(), webhookDto.getName());
  }

}
