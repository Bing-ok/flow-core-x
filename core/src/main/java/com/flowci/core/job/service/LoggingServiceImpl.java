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

package com.flowci.core.job.service;

import com.flowci.core.agent.domain.CmdStdLog;
import com.flowci.core.common.config.AppProperties;
import com.flowci.core.common.helper.CacheHelper;
import com.flowci.core.common.manager.SocketPushManager;
import com.flowci.core.common.rabbit.RabbitOperations;
import com.flowci.core.flow.domain.Flow;
import com.flowci.core.job.domain.Job;
import com.flowci.core.job.domain.Step;
import com.flowci.core.job.event.JobCreatedEvent;
import com.flowci.core.job.event.JobStatusChangeEvent;
import com.flowci.exception.NotFoundException;
import com.flowci.store.FileManager;
import com.flowci.store.Pathable;
import com.flowci.util.FileHelper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.RemovalListener;
import com.google.common.collect.ImmutableList;
import com.rabbitmq.client.Envelope;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author yang
 */
@Log4j2
@Service
public class LoggingServiceImpl implements LoggingService {

    private static final Page<String> LogNotFound = new PageImpl<>(
            ImmutableList.of("Log not available"),
            PageRequest.of(0, 1),
            1L
    );

    private static final int FileBufferSize = 8000; // ~8k

    private static final Pathable LogPath = () -> "logs";

    // cache current job log
    private final Cache<String, Map<String, Queue<byte[]>>> logCache = CacheHelper.createLocalCache(50, 3600);

    @Autowired
    private AppProperties.RabbitMQ rabbitProperties;

    @Autowired
    private String topicForTtyLogs;

    @Autowired
    private String topicForLogs;

    @Autowired
    private RabbitOperations receiverQueueManager;

    @Autowired
    private SocketPushManager socketPushManager;

    @Autowired
    private FileManager fileManager;

    @Autowired
    private StepService stepService;

    @EventListener(ContextRefreshedEvent.class)
    public void onStart() throws IOException {
        // shell log content will be json {cmdId: xx, content: b64 log}
        String shellLogQueue = rabbitProperties.getShellLogQueue();
        receiverQueueManager.startConsumer(shellLogQueue, true, new CmdStdLogHandler());

        // tty log conent will be b64 std out/err
        String ttyLogQueue = rabbitProperties.getTtyLogQueue();
        receiverQueueManager.startConsumer(ttyLogQueue, true, new TtyLogHandler());
    }

    /**
     * Create/Remove logging buffer for job
     */
    @EventListener(JobStatusChangeEvent.class)
    public void onJobFinished(JobStatusChangeEvent event) {
        Job job = event.getJob();

        if (job.getStatus() == Job.Status.CREATED) {
            List<Step> steps = stepService.list(job);
            Map<String, Queue<byte[]>> cache = new HashMap<>(steps.size());
            for (Step step : steps) {
                cache.put(step.getId(), new ConcurrentLinkedQueue<>());
            }
            logCache.put(job.getId(), cache);
            return;
        }

        if (job.isDone()) {
            logCache.invalidate(job.getId());
        }
    }

    @Override
    public String save(String fileName, InputStream stream) throws IOException {
        String cmdId = FileHelper.getName(fileName);
        Pathable[] logDir = getLogDir(cmdId);
        return fileManager.save(fileName, stream, logDir);
    }

    @Override
    public Resource get(String stepId) {
        try {
            String fileName = getLogFile(stepId);
            InputStream stream = fileManager.read(fileName, getLogDir(stepId));
            return new InputStreamResource(stream);
        } catch (IOException e) {
            throw new NotFoundException("Log not available");
        }
    }

    @Override
    public Collection<byte[]> read(String stepId) {
        Step step = stepService.get(stepId);
        Map<String, Queue<byte[]>> cached = logCache.getIfPresent(step.getJobId());

        if (Objects.isNull(cached)) {
            return Collections.emptyList();
        }

        return cached.get(stepId);
    }

    private Pathable[] getLogDir(String cmdId) {
        Step step = stepService.get(cmdId);

        return new Pathable[]{
                Flow.path(step.getFlowId()),
                Job.path(step.getBuildNumber()),
                LogPath
        };
    }

    private String getLogFile(String cmdId) {
        return cmdId + ".log";
    }

    private class CmdStdLogHandler implements RabbitOperations.OnMessage {

        @Override
        public boolean on(Map<String, Object> headers, byte[] body, Envelope envelope) {
            Optional<String> jobId = CmdStdLog.getFromHeader(headers, CmdStdLog.ID_HEADER);
            if (!jobId.isPresent()) {
                return true;
            }

            // write to log cache
            Optional<String> stepId = CmdStdLog.getFromHeader(headers, CmdStdLog.STEP_ID_HEADER);
            if (stepId.isPresent()) {
                Map<String, Queue<byte[]>> cache = logCache.getIfPresent(jobId.get());
                if (cache != null) {
                    cache.get(stepId.get()).add(body);
                }
            }

            // push to ws
            socketPushManager.push(topicForLogs + "/" + jobId.get(), body);
            return false;
        }
    }

    private class TtyLogHandler implements RabbitOperations.OnMessage {

        @Override
        public boolean on(Map<String, Object> headers, byte[] body, Envelope envelope) {
            Optional<String> optional = CmdStdLog.getFromHeader(headers, CmdStdLog.ID_HEADER);
            if (optional.isPresent()) {
                String ttyId = optional.get();
                socketPushManager.push(topicForTtyLogs + "/" + ttyId, body);
            }
            return false;
        }
    }
}
