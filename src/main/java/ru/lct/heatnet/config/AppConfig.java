package ru.lct.heatnet.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AppConfig {

    @Bean
    public OpenAPI openApi() {
        return new OpenAPI().info(new Info()
                .title("Heatnet — сервис моделирования трасс подключения к тепловым сетям")
                .description("Конкурсный кейс ЛЦТ 2026. Вход: GeoJSON (WGS 84), "
                        + "выход: варианты новой тепловой сети с расчётом стоимости.")
                .version("1.0.0"));
    }

    /** Встроенный веб-интерфейс: корень сайта ведёт на страницу UI. */
    @Bean
    public org.springframework.web.servlet.config.annotation.WebMvcConfigurer uiRedirect() {
        return new org.springframework.web.servlet.config.annotation.WebMvcConfigurer() {
            @Override
            public void addViewControllers(
                    org.springframework.web.servlet.config.annotation.ViewControllerRegistry registry) {
                registry.addRedirectViewController("/", "/ui/index.html");
                registry.addRedirectViewController("/ui", "/ui/index.html");
                registry.addRedirectViewController("/ui/", "/ui/index.html");
            }
        };
    }

    /** Пул расчётных задач: тяжёлые расчёты не блокируют HTTP-потоки. */
    @Bean(name = "tracingExecutor")
    public ThreadPoolTaskExecutor tracingExecutor(
            @Value("${heatnet.tracing.workers:1}") int workers,
            @Value("${heatnet.tracing.queue-capacity:100}") int queueCapacity) {
        if (workers < 1) {
            throw new IllegalArgumentException("heatnet.tracing.workers должен быть >= 1");
        }
        if (queueCapacity < 0) {
            throw new IllegalArgumentException("heatnet.tracing.queue-capacity должен быть >= 0");
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(workers);
        executor.setMaxPoolSize(workers);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("tracing-");
        executor.initialize();
        return executor;
    }
}
