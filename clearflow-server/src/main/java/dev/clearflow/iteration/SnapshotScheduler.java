package dev.clearflow.iteration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 迭代日快照：每天 01:05 记录进行中迭代的剩余点数（燃尽图数据源）
 * 采集逻辑与需求变更时的实时快照共用 IterationService#snapshot，避免同一张表混入两套点数口径
 */
@Component
public class SnapshotScheduler {

    private static final Logger log = LoggerFactory.getLogger(SnapshotScheduler.class);

    private final IterationService iterationService;

    public SnapshotScheduler(IterationService iterationService) {
        this.iterationService = iterationService;
    }

    @Scheduled(cron = "0 5 1 * * *")
    public void capture() {
        int n = iterationService.snapshotActiveIterations();
        if (n > 0) {
            log.info("迭代快照完成：{} 个进行中迭代 @ {}", n, LocalDate.now());
        }
    }
}
