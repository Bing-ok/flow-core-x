/*
 * Copyright 2018 flow.ci
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

package com.flowci.core.job.manager;

import com.flowci.core.agent.domain.ShellIn;
import com.flowci.core.agent.domain.ShellKill;
import com.flowci.core.common.domain.Variables;
import com.flowci.core.common.manager.SpringEventManager;
import com.flowci.core.job.domain.Job;
import com.flowci.core.job.domain.Step;
import com.flowci.core.plugin.domain.Plugin;
import com.flowci.core.plugin.event.GetPluginAndVerifySetContext;
import com.flowci.core.plugin.event.GetPluginEvent;
import com.flowci.domain.DockerOption;
import com.flowci.domain.Vars;
import com.flowci.exception.StatusException;
import com.flowci.tree.*;
import com.flowci.util.ObjectsHelper;
import com.flowci.util.StringHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * @author yang
 */
@Component
public class CmdManagerImpl implements CmdManager {

    @Autowired
    private SpringEventManager eventManager;

    @Override
    public ShellIn createShellCmd(Job job, Step step, NodeTree tree) {
        Node node = tree.get(NodePath.create(step.getNodePath()));

        if (node instanceof ParallelStepNode) {
            throw new StatusException("Illegal step node type, must be regular step");
        }

        RegularStepNode rNode = (RegularStepNode) node;
        ShellIn in = new ShellIn()
                .setId(step.getId())
                .setFlowId(job.getFlowId())
                .setJobId(job.getId())
                .setAllowFailure(rNode.isAllowFailure())
                .setDockers(ObjectsHelper.copy(findDockerOptions(rNode)))
                .setBash(linkScript(rNode, ShellIn.ShellType.Bash))
                .setPwsh(linkScript(rNode, ShellIn.ShellType.PowerShell))
                .setEnvFilters(linkFilters(rNode))
                .setInputs(rNode.allEnvs().merge(job.getContext(), false))
                .setTimeout(linkTimeout(rNode, job.getTimeout()))
                .setRetry(linkRetry(rNode, 0))
                .setCache(rNode.getCache());

        if (rNode.hasPlugin()) {
            setPlugin(rNode.getPlugin(), in);
        }

        // set node allow failure as top priority
        if (rNode.isAllowFailure() != in.isAllowFailure()) {
            in.setAllowFailure(rNode.isAllowFailure());
        }

        // auto create default container name
        for (DockerOption option : in.getDockers()) {
            if (!option.hasName()) {
                option.setName(getDefaultContainerName(rNode));
            }
        }

        if (!isDockerEnabled(job.getContext())) {
            in.getDockers().clear();
        }

        return in;
    }

    @Override
    public ShellKill createKillCmd() {
        return new ShellKill();
    }

    private String getDefaultContainerName(RegularStepNode node) {
        NodePath path = node.getPath();
        String stepStr = path.getNodePathWithoutSpace().replace(NodePath.PathSeparator, "-");
        return StringHelper.escapeNumber(String.format("%s-%s", stepStr, StringHelper.randomString(5)));
    }

    private Integer linkRetry(RegularStepNode current, Integer defaultRetry) {
        if (current.hasRetry()) {
            return current.getRetry();
        }

        if (current.hasParent()) {
            Node parent = current.getParent();
            if (parent instanceof RegularStepNode) {
                return linkRetry((RegularStepNode) parent, defaultRetry);
            }
        }

        return defaultRetry;
    }

    private Integer linkTimeout(RegularStepNode current, Integer defaultTimeout) {
        if (current.hasTimeout()) {
            return current.getTimeout();
        }

        if (current.hasParent()) {
            Node parent = current.getParent();
            if (parent instanceof RegularStepNode) {
                return linkTimeout((RegularStepNode) parent, defaultTimeout);
            }
        }

        return defaultTimeout;
    }

    private Set<String> linkFilters(RegularStepNode current) {
        Set<String> output = new LinkedHashSet<>();

        if (current.hasParent()) {
            Node parent = current.getParent();
            if (parent instanceof RegularStepNode) {
                output.addAll(linkFilters((RegularStepNode) parent));
            }
        }

        output.addAll(current.getExports());
        return output;
    }

    private List<String> linkScript(RegularStepNode current, ShellIn.ShellType shellType) {
        List<String> output = new LinkedList<>();

        if (current.hasParent()) {
            Node parent = current.getParent();
            if (parent instanceof RegularStepNode) {
                output.addAll(linkScript((RegularStepNode) parent, shellType));
            }
        }

        if (shellType == ShellIn.ShellType.Bash) {
            output.add(current.getBash());
        }

        if (shellType == ShellIn.ShellType.PowerShell) {
            output.add(current.getPwsh());
        }

        return output;
    }

    private List<DockerOption> findDockerOptions(Node current) {
        if (current.hasDocker()) {
            return current.getDockers();
        }

        Node parent = current.getParent();
        return findDockerOptions(parent);
    }

    private void setPlugin(String name, ShellIn cmd) {
        GetPluginEvent event = eventManager.publish(new GetPluginAndVerifySetContext(this, name, cmd.getInputs()));
        if (event.hasError()) {
            throw event.getError();
        }

        Plugin plugin = event.getFetched();
        cmd.setPlugin(name);
        cmd.setAllowFailure(plugin.isAllowFailure());
        cmd.addEnvFilters(plugin.getExports());
        cmd.addScript(plugin.getBash(), ShellIn.ShellType.Bash);
        cmd.addScript(plugin.getPwsh(), ShellIn.ShellType.PowerShell);

        // apply docker from plugin as run time if it's specified
        ObjectsHelper.ifNotNull(plugin.getDocker(), (docker) -> {
            Iterator<DockerOption> iterator = cmd.getDockers().iterator();
            while (iterator.hasNext()) {
                DockerOption option = iterator.next();
                if (option.isRuntime()) {
                    iterator.remove();
                    break;
                }
            }
            cmd.getDockers().add(plugin.getDocker());
        });
    }

    private static boolean isDockerEnabled(Vars<String> input) {
        String val = input.get(Variables.Step.DockerEnabled, "true");
        return Boolean.parseBoolean(val);
    }
}
