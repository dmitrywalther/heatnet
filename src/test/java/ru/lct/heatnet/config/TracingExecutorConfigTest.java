package ru.lct.heatnet.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;

class TracingExecutorConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AppConfig.class);

    @Test
    void defaultsToOneFixedWorkerAndBoundedQueue() {
        runner.run(context -> {
            ThreadPoolTaskExecutor executor = context.getBean("tracingExecutor", ThreadPoolTaskExecutor.class);
            assertThat(executor.getCorePoolSize()).isEqualTo(1);
            assertThat(executor.getMaxPoolSize()).isEqualTo(1);
            assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(100);
        });
    }

    @Test
    void honorsExplicitWorkersAndZeroQueue() {
        runner.withPropertyValues("heatnet.tracing.workers=3", "heatnet.tracing.queue-capacity=0")
                .run(context -> {
                    ThreadPoolTaskExecutor executor = context.getBean("tracingExecutor", ThreadPoolTaskExecutor.class);
                    assertThat(executor.getCorePoolSize()).isEqualTo(3);
                    assertThat(executor.getMaxPoolSize()).isEqualTo(3);
                    assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isZero();
                });
    }

    @Test
    void rejectsNonPositiveWorkersAndNegativeQueue() {
        for (String property : new String[]{"heatnet.tracing.workers=0", "heatnet.tracing.workers=-1",
                "heatnet.tracing.queue-capacity=-1"}) {
            runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
        }
    }
}
