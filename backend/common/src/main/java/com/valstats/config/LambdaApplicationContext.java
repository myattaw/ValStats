package com.valstats.config;

import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.Micronaut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the Micronaut ApplicationContext singleton for Lambda handlers.
 *
 * Lambda instances are long-lived (not reused across invocations), but within a single
 * Lambda container, we want to reuse the same ApplicationContext to avoid cold starts
 * on each invocation.
 *
 * This class provides lazy initialization on first use and ensures proper cleanup.
 */
public class LambdaApplicationContext {

    private static final Logger LOG = LoggerFactory.getLogger(LambdaApplicationContext.class);
    
    private static ApplicationContext applicationContext;
    private static final Object lock = new Object();

    /**
     * Get or create the shared ApplicationContext.
     * Thread-safe with lazy initialization.
     */
    public static ApplicationContext getContext(Class<?> applicationClass) {
        if (applicationContext != null) {
            return applicationContext;
        }

        synchronized (lock) {
            if (applicationContext != null) {
                return applicationContext;
            }

            LOG.info("Initializing Micronaut ApplicationContext for Lambda");
            applicationContext = Micronaut.run(applicationClass);
            LOG.info("ApplicationContext initialized successfully");

            // Register shutdown hook for graceful cleanup
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                LOG.info("Closing ApplicationContext");
                if (applicationContext != null) {
                    applicationContext.close();
                }
            }));

            return applicationContext;
        }
    }

    /**
     * Close the application context gracefully.
     */
    public static void closeContext() {
        synchronized (lock) {
            if (applicationContext != null) {
                LOG.info("Closing ApplicationContext");
                applicationContext.close();
                applicationContext = null;
            }
        }
    }

}

