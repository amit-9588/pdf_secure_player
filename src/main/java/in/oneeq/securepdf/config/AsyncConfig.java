package in.oneeq.securepdf.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Dedicated pool for the PDF processing worker so that a slow render job
 * never starves the web request threads.
 */
@Configuration
public class AsyncConfig implements AsyncConfigurer {

    @Bean(name = "pdfProcessingExecutor")
    public Executor pdfProcessingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("pdf-proc-");
        executor.initialize();
        return executor;
    }

    @Override
    public Executor getAsyncExecutor() {
        return pdfProcessingExecutor();
    }
}
