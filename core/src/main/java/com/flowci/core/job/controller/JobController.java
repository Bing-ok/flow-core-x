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

package com.flowci.core.job.controller;

import com.flowci.core.auth.annotation.Action;
import com.flowci.core.common.manager.SessionManager;
import com.flowci.core.flow.domain.Flow;
import com.flowci.core.flow.service.FlowService;
import com.flowci.core.flow.service.YmlService;
import com.flowci.core.job.domain.*;
import com.flowci.core.job.domain.Job.Trigger;
import com.flowci.core.job.service.*;
import com.flowci.core.user.domain.User;
import com.flowci.exception.ArgumentException;
import com.flowci.tree.NodePath;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * @author yang
 */
@RestController
@RequestMapping("/jobs")
public class JobController {

    private static final String DefaultPage = "0";

    private static final String DefaultSize = "20";

    private static final String ParameterLatest = "latest";

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private FlowService flowService;

    @Autowired
    private YmlService ymlService;

    @Autowired
    private JobService jobService;

    @Autowired
    private StepService stepService;

    @Autowired
    private LocalTaskService localTaskService;

    @Autowired
    private LoggingService loggingService;

    @Autowired
    private ReportService reportService;

    @Autowired
    private ArtifactService artifactService;

    @Autowired
    private TaskExecutor appTaskExecutor;

    @GetMapping("/{flow}")
    @Action(JobAction.LIST)
    public Page<JobItem> list(@PathVariable("flow") String name,
                              @RequestParam(required = false, defaultValue = DefaultPage) int page,
                              @RequestParam(required = false, defaultValue = DefaultSize) int size) {

        Flow flow = flowService.get(name);
        return jobService.list(flow, page, size);
    }

    @GetMapping("/{flow}/{buildNumberOrLatest}")
    @Action(JobAction.GET)
    public Job get(@PathVariable("flow") String name, @PathVariable String buildNumberOrLatest) {
        Flow flow = flowService.get(name);

        if (ParameterLatest.equals(buildNumberOrLatest)) {
            return jobService.getLatest(flow.getId());
        }

        try {
            long buildNumber = Long.parseLong(buildNumberOrLatest);
            return jobService.get(flow.getId(), buildNumber);
        } catch (NumberFormatException e) {
            throw new ArgumentException("Build number must be a integer");
        }
    }

    @GetMapping("/{jobId}/desc")
    @Action(JobAction.GET)
    public JobDesc getDesc(@PathVariable String jobId) {
        return jobService.getDesc(jobId);
    }

    @GetMapping(value = "/{flow}/{buildNumber}/yml", produces = MediaType.APPLICATION_JSON_VALUE)
    @Action(JobAction.GET_YML)
    public String getYml(@PathVariable String flow, @PathVariable String buildNumber) {
        Job job = get(flow, buildNumber);
        JobYml yml = jobService.getYml(job);
        return Base64.getEncoder().encodeToString(yml.getRaw().getBytes());
    }

    @GetMapping("/{flow}/{buildNumberOrLatest}/steps")
    @Action(JobAction.LIST_STEPS)
    public List<Step> listSteps(@PathVariable String flow,
                                @PathVariable String buildNumberOrLatest) {
        Job job = get(flow, buildNumberOrLatest);
        return stepService.list(job);
    }

    @GetMapping("/{flow}/{buildNumberOrLatest}/tasks")
    @Action(JobAction.LIST_STEPS)
    public List<ExecutedLocalTask> listTasks(@PathVariable String flow,
                                             @PathVariable String buildNumberOrLatest) {
        Job job = get(flow, buildNumberOrLatest);
        return localTaskService.list(job);
    }

    @GetMapping("/logs/{stepId}/read")
    @Action(JobAction.DOWNLOAD_STEP_LOG)
    public Collection<byte[]> readStepLog(@PathVariable String stepId) {
        return loggingService.read(stepId);
    }

    @GetMapping("/logs/{stepId}/download")
    @Action(JobAction.DOWNLOAD_STEP_LOG)
    public ResponseEntity<Resource> downloadStepLog(@PathVariable String stepId) {
        Step step = stepService.get(stepId);
        Resource resource = loggingService.get(stepId);
        Flow flow = flowService.getById(step.getFlowId());

        NodePath path = NodePath.create(step.getNodePath());
        String fileName = String.format("%s-#%s-%s.log", flow.getName(), step.getBuildNumber(), path.name());

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(MediaType.APPLICATION_OCTET_STREAM_VALUE))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .body(resource);
    }

    @PostMapping
    @Action(JobAction.CREATE)
    public Job create(@Validated @RequestBody CreateJob data) {
        Flow flow = flowService.get(data.getFlow());
        String ymlStr = ymlService.getYmlString(flow);
        return jobService.create(flow, ymlStr, Trigger.API, data.getInputs());
    }

    @PostMapping("/run")
    @Action(JobAction.RUN)
    public void createAndStart(@Validated @RequestBody CreateJob body) {
        final User current = sessionManager.get();
        final Flow flow = flowService.get(body.getFlow());
        final String ymlStr = ymlService.getYmlString(flow);

        if (!flow.isYamlFromRepo() && Objects.isNull(ymlStr)) {
            throw new ArgumentException("YAML config is required to start a job");
        }

        // start from thread since could be loading yaml from git repo
        appTaskExecutor.execute(() -> {
            sessionManager.set(current);
            Job job = jobService.create(flow, ymlStr, Trigger.API, body.getInputs());
            jobService.start(job);
        });
    }

    @PostMapping("/rerun")
    @Action(JobAction.RUN)
    public void rerun(@Validated @RequestBody RerunJob body) {
        Job job = jobService.get(body.getJobId());
        Flow flow = flowService.getById(job.getFlowId());
        jobService.rerun(flow, job);
    }

    @PostMapping("/{flow}/{buildNumber}/cancel")
    @Action(JobAction.CANCEL)
    public Job cancel(@PathVariable String flow, @PathVariable String buildNumber) {
        Job job = get(flow, buildNumber);
        jobService.cancel(job);
        return job;
    }

    @GetMapping("/{flow}/{buildNumber}/reports")
    @Action(JobAction.LIST_REPORTS)
    public List<JobReport> listReports(@PathVariable String flow, @PathVariable String buildNumber) {
        Job job = get(flow, buildNumber);
        return reportService.list(job);
    }

    @GetMapping(value = "/{flow}/{buildNumber}/reports/{reportId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Action(JobAction.FETCH_REPORT)
    public String fetchReport(@PathVariable String flow,
                              @PathVariable String buildNumber,
                              @PathVariable String reportId) {
        Job job = get(flow, buildNumber);
        return reportService.fetch(job, reportId);
    }

    @GetMapping("/{flow}/{buildNumber}/artifacts")
    @Action(JobAction.LIST_ARTIFACTS)
    public List<JobArtifact> listArtifact(@PathVariable String flow, @PathVariable String buildNumber) {
        Job job = get(flow, buildNumber);
        return artifactService.list(job);
    }

    @GetMapping(value = "/{flow}/{buildNumber}/artifacts/{artifactId}")
    @Action(JobAction.DOWNLOAD_ARTIFACT)
    public ResponseEntity<Resource> downloadArtifact(@PathVariable String flow,
                                                     @PathVariable String buildNumber,
                                                     @PathVariable String artifactId) {
        Job job = get(flow, buildNumber);
        JobArtifact artifact = artifactService.fetch(job, artifactId);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(MediaType.APPLICATION_OCTET_STREAM_VALUE))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + artifact.getFileName() + "\"")
                .body(new InputStreamResource(artifact.getSrc()));
    }
}
