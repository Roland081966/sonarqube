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

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import org.json.JSONArray;
import org.json.JSONObject;
import org.sonar.api.server.ServerSide;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonar.core.util.ParamChange;
import org.sonar.core.util.RuleActivationListener;
import org.sonar.core.util.RuleChange;
import org.sonar.core.util.RuleSetChangeEvent;
import org.sonar.server.qualityprofile.RuleActivatorEventsDistributor;

import static java.util.Arrays.asList;

@ServerSide
public class SonarLintClientsRegistry implements RuleActivationListener {

  private static final Logger LOG = Loggers.get(SonarLintClientsRegistry.class);

  private final RuleActivatorEventsDistributor ruleActivatorEventsDistributor;

  public SonarLintClientsRegistry(RuleActivatorEventsDistributor ruleActivatorEventsDistributor) {
    this.ruleActivatorEventsDistributor = ruleActivatorEventsDistributor;
  }


  private final List<SonarLintClient> clients = new CopyOnWriteArrayList<>();

  public void registerClient(SonarLintClient sonarLintClient) {
    clients.add(sonarLintClient);
    sonarLintClient.scheduleHeartbeat();
    sonarLintClient.addListener(new SonarLintClientEventsListener(sonarLintClient));
    ruleActivatorEventsDistributor.subscribe(this);

    LOG.debug("Registering new SonarLint client");
  }

  public void unregisterClient(SonarLintClient client) {
    clients.remove(client);
    LOG.debug("Removing SonarLint client");
  }

  public long countConnectedClients() {
    return clients.size();
  }

  @Override
  public void listen(RuleSetChangeEvent ruleChangeEvent) {
    LOG.info("Generating a RuleSetChangeEvent");
    // TODO filter on languages here as well
    broadcastMessage(ruleChangeEvent, f -> f.getClientProjectKeys().isEmpty() || !Collections.disjoint(f.getClientProjectKeys(), asList(ruleChangeEvent.getProjects())));
  }


  public void broadcastMessage(RuleSetChangeEvent message, Predicate<SonarLintClient> filter) {
    String jsonString = getJSONString(message);
    clients.stream().filter(filter).forEach(c -> {
      try {
        c.writeAndFlush(jsonString);
      } catch (IOException e) {
        LOG.error("Unable to send message to a client: " + e.getMessage());
      }
    });
  }


  public String getJSONString(RuleSetChangeEvent ruleSetChangeEvent) {
    JSONObject result = new JSONObject();
    result.put("event", ruleSetChangeEvent.getEvent());

    JSONObject data = new JSONObject();
    data.put("projects", ruleSetChangeEvent.getProjects());

    JSONArray activatedRulesJson = new JSONArray();
    for (RuleChange rule : ruleSetChangeEvent.getActivatedRules()) {
      activatedRulesJson.put(toJson(rule));
    }
    data.put("activatedRules", activatedRulesJson);

    JSONArray deactivatedRulesJson = new JSONArray();
    for (RuleChange rule : ruleSetChangeEvent.getDeactivatedRules()) {
      deactivatedRulesJson.put(toJson(rule));
    }
    data.put("deactivatedRules", deactivatedRulesJson);

    result.put("data", data);
    return result.toString();
  }

  private JSONObject toJson(RuleChange rule) {
    JSONObject ruleJson = new JSONObject();
    ruleJson.put("key", rule.getKey());
    ruleJson.put("language", rule.getLanguage());
    ruleJson.put("severity", rule.getSeverity());
    ruleJson.put("templateKey", rule.getTemplateKey());

    JSONArray params = new JSONArray();
    for (ParamChange paramChange : rule.getParams()) {
      params.put(toJson(paramChange));
    }
    ruleJson.put("params", params);
    return ruleJson;
  }

  private JSONObject toJson(ParamChange paramChange) {
    JSONObject param = new JSONObject();
    param.put("key", paramChange.getKey());
    param.put("value", paramChange.getValue());
    return param;
  }

  class SonarLintClientEventsListener implements AsyncListener {
    private final SonarLintClient client;

    public SonarLintClientEventsListener(SonarLintClient sonarLintClient) {
      this.client = sonarLintClient;
    }

    @Override
    public void onComplete(AsyncEvent event) {
      unregisterClient(client);
    }

    @Override
    public void onError(AsyncEvent event) {
      unregisterClient(client);
    }

    @Override
    public void onStartAsync(AsyncEvent event) {
      //nothing to do on start
    }

    @Override
    public void onTimeout(AsyncEvent event) {
      unregisterClient(client);
    }
  }

}