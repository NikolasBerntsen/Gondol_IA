package com.gondolia.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Ejecutores de la aplicación.
 * <ul>
 *   <li>{@code taskExecutor} (alias {@code applicationTaskExecutor}): métodos {@code @Async}, listeners costosos y
 *       peticiones asincrónicas de Spring MVC.</li>
 *   <li>{@code taskScheduler}: tareas {@code @Scheduled}. Se declara explícitamente para que no terminen corriendo en
 *       el scheduler del broker STOMP cuando se habilita WebSocket.</li>
 * </ul>
 */
@Slf4j
@Configuration
public class AsyncConfig implements AsyncConfigurer {

    @Primary
    @Bean(name = {"taskExecutor", "applicationTaskExecutor"})
    public ThreadPoolTaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(500);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("gondolia-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        return executor;
    }

    @Bean(name = "taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("gondolia-sched-");
        scheduler.setErrorHandler(t -> log.error("Error en tarea programada", t));
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(20);
        return scheduler;
    }

    @Override
    public Executor getAsyncExecutor() {
        return taskExecutor();
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) ->
                log.error("Error no controlado en tarea asincrónica {}.{}", method.getDeclaringClass().getSimpleName(),
                        method.getName(), ex);
    }
}
