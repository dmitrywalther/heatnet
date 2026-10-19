package ru.lct.heatnet.job;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;

public interface JobRepository extends JpaRepository<JobEntity, String> {
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("update JobEntity j set j.status = :errorStatus, j.finishedAt = :finishedAt, "
            + "j.errorMessage = :message, j.resultPath = null, j.depthResultPath = null, "
            + "j.reportPath = null, j.summaryJson = null where j.status in :unfinished")
    int failInterruptedJobs(@Param("unfinished") Collection<JobEntity.Status> unfinished,
                            @Param("errorStatus") JobEntity.Status errorStatus,
                            @Param("finishedAt") Instant finishedAt,
                            @Param("message") String message);
}
