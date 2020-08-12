/*
 * Copyright 2019 flow.ci
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.flowci.core.flow.service;

import com.flowci.core.common.manager.SpringEventManager;
import com.flowci.core.common.manager.VarManager;
import com.flowci.core.flow.dao.FlowDao;
import com.flowci.core.flow.dao.YmlDao;
import com.flowci.core.flow.domain.Flow;
import com.flowci.core.flow.domain.Settings;
import com.flowci.core.flow.domain.WebhookStatus;
import com.flowci.core.flow.domain.Yml;
import com.flowci.core.job.domain.Job;
import com.flowci.core.job.event.CreateNewJobEvent;
import com.flowci.core.trigger.domain.GitPingTrigger;
import com.flowci.core.trigger.domain.GitPushTrigger;
import com.flowci.core.trigger.domain.GitTrigger;
import com.flowci.core.trigger.event.GitHookEvent;
import com.flowci.domain.StringVars;
import com.flowci.domain.VarValue;
import com.flowci.exception.ArgumentException;
import com.flowci.tree.FlowNode;
import com.flowci.tree.TriggerFilter;
import com.flowci.tree.YmlParser;
import com.flowci.util.StringHelper;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @author yang
 */

@Log4j2
@Service
public class FlowSettingServiceImpl implements FlowSettingService {

    @Autowired
    private FlowDao flowDao;

    @Autowired
    private YmlDao ymlDao;

    @Autowired
    private SpringEventManager eventManager;

    @Autowired
    private VarManager varManager;

    @Autowired
    private CronService cronService;

    @Override
    public void set(Flow flow, Settings settings) {
        if (settings.hasCron()) {
            cronService.validate(settings.getCron());
        }

        flow.setYamlFromRepo(settings.getIsYamlFromRepo());
        flow.setYamlRepoBranch(settings.getYamlRepoBranch());
        flow.setJobTimeout(settings.getJobTimeout());
        flow.setStepTimeout(settings.getStepTimeout());
        flow.setCron(settings.getCron());
        flowDao.save(flow);

        cronService.set(flow);
    }

    @Override
    public void set(Flow flow, WebhookStatus ws) {
        flow.setWebhookStatus(ws);
        flowDao.save(flow);
    }

    @Override
    public void add(Flow flow, Map<String, VarValue> vars) {
        for (Map.Entry<String, VarValue> entry : vars.entrySet()) {
            String name = entry.getKey();
            VarValue value = entry.getValue();

            if (!StringHelper.hasValue(name)) {
                throw new ArgumentException("Var name cannot be empty");
            }

            if (!StringHelper.hasValue(value.getData())) {
                throw new ArgumentException("Var value of {0} cannot be empty", name);
            }

            boolean isVerified = varManager.verify(value.getType(), value.getData());

            if (isVerified) {
                flow.getLocally().put(name, value);
                continue;
            }

            throw new ArgumentException("Var {0} format is wrong", name);
        }

        flowDao.save(flow);
    }

    @Override
    public void remove(Flow flow, List<String> vars) {
        for (String key : vars) {
            flow.getLocally().remove(key);
        }

        flowDao.save(flow);
    }

    @EventListener
    public void onGitHookEvent(GitHookEvent event) {
        GitTrigger trigger = event.getTrigger();
        Flow flow = flowDao.findByName(event.getFlow());

        if (event.isPingEvent()) {
            GitPingTrigger ping = (GitPingTrigger) trigger;

            WebhookStatus ws = new WebhookStatus();
            ws.setAdded(true);
            ws.setCreatedAt(ping.getCreatedAt());
            ws.setEvents(ping.getEvents());

            flow.setWebhookStatus(ws);
            flowDao.save(flow);
            return;
        }

        if (trigger.isSkip()) {
            log.info("Ignore git trigger {} since skip message", trigger);
            return;
        }

        Optional<Yml> optional = ymlDao.findById(flow.getId());
        if (!optional.isPresent()) {
            log.warn("No available yml for flow {}", flow.getName());
            return;
        }

        Yml yml = optional.get();
        FlowNode root = YmlParser.load(flow.getName(), yml.getRaw());
        if (!canStartJob(root, trigger)) {
            log.debug("Cannot start job since filter not matched on flow {}", flow.getName());
            return;
        }

        StringVars gitInput = trigger.toVariableMap();
        Job.Trigger jobTrigger = trigger.toJobTrigger();

        eventManager.publish(new CreateNewJobEvent(this, flow, yml.getRaw(), jobTrigger, gitInput));
    }

    private boolean canStartJob(FlowNode root, GitTrigger trigger) {
        TriggerFilter condition = root.getTrigger();

        if (trigger.getEvent() == GitTrigger.GitEvent.PUSH) {
            GitPushTrigger pushTrigger = (GitPushTrigger) trigger;
            return condition.isMatchBranch(pushTrigger.getRef());
        }

        if (trigger.getEvent() == GitTrigger.GitEvent.TAG) {
            GitPushTrigger tagTrigger = (GitPushTrigger) trigger;
            return condition.isMatchTag(tagTrigger.getRef());
        }

        return true;
    }
}
