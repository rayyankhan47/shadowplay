package com.shadowplay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared logger. Every line is prefixed with [Shadowplay] so it is easy to grep in launcher logs. */
public final class Log {
	private static final Logger LOGGER = LoggerFactory.getLogger("Shadowplay");
	private static final String PREFIX = "[Shadowplay] ";

	private Log() {
	}

	public static void debug(String format, Object... args) {
		LOGGER.debug(PREFIX + format, args);
	}

	public static void info(String format, Object... args) {
		LOGGER.info(PREFIX + format, args);
	}

	public static void warn(String format, Object... args) {
		LOGGER.warn(PREFIX + format, args);
	}

	public static void error(String format, Object... args) {
		LOGGER.error(PREFIX + format, args);
	}
}
