package io.github.ldogg123.gregscope.gametest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;

/**
 * Log4j (2.0-beta9, as shipped with Minecraft 1.7.10) test appender that records every event whose formatted message
 * contains a needle. It attaches to the root logger and to the named loggers, and counts an event once even when it
 * passes several of them.
 */
final class LogCapture extends AbstractAppender implements AutoCloseable {

    private final String needle;
    private final List<Logger> loggers = new ArrayList<>();
    private final Map<LogEvent, Boolean> seen = Collections.synchronizedMap(new IdentityHashMap<LogEvent, Boolean>());
    private final List<String> lines = Collections.synchronizedList(new ArrayList<String>());

    private LogCapture(String needle) {
        super("gregscope-gametest-capture-" + System.nanoTime(), null, null, false);
        this.needle = needle;
    }

    static LogCapture attach(String needle, String... loggerNames) {
        LogCapture capture = new LogCapture(needle);
        capture.start();
        capture.add((Logger) LogManager.getRootLogger());
        for (String name : loggerNames) {
            capture.add((Logger) LogManager.getLogger(name));
        }
        return capture;
    }

    private void add(Logger logger) {
        logger.addAppender(this);
        loggers.add(logger);
    }

    @Override
    public void append(LogEvent event) {
        String message = event.getMessage() == null ? null
            : event.getMessage()
                .getFormattedMessage();
        if (message != null && message.contains(needle) && seen.put(event, Boolean.TRUE) == null) {
            lines.add(event.getLevel() + " [" + event.getLoggerName() + "] " + message);
        }
    }

    int count() {
        return lines.size();
    }

    List<String> lines() {
        synchronized (lines) {
            return new ArrayList<>(lines);
        }
    }

    void clear() {
        lines.clear();
        seen.clear();
    }

    @Override
    public void close() {
        for (Logger logger : loggers) {
            logger.removeAppender(this);
        }
        loggers.clear();
        stop();
    }
}
