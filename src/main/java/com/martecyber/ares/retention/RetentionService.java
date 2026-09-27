package com.martecyber.ares.retention;

import com.martecyber.ares.agents.tasks.AgentTaskRepository;
import com.martecyber.ares.jobs.JobRepository;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

@Service
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    private final AgentTaskRepository agentTasks;
    private final JobRepository jobs;

    @Value("${ares.retention.task-history-days:30}")
    private int taskHistoryDays;

    public RetentionService(AgentTaskRepository agentTasks, JobRepository jobs) {
        this.agentTasks = agentTasks;
        this.jobs = jobs;
    }

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void purgeOldTaskHistory() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(taskHistoryDays);
        int deletedTasks = agentTasks.deleteTerminalOlderThan(cutoff);
        int deletedJobs  = jobs.deleteTerminalOlderThan(cutoff);
        if (deletedTasks > 0 || deletedJobs > 0) {
            log.info("Retention purge (cutoff={}d): deleted {} agent_task rows, {} job rows",
                taskHistoryDays, deletedTasks, deletedJobs);
        }
    }
}
