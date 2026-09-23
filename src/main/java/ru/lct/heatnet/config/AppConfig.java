package ru.lct.heatnet.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

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
    public Executor tracingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("tracing-");
        executor.initialize();
        return executor;
    }
}
