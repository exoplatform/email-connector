/**
 * Copyright (C) 2026 eXo Platform SAS
 *
 *  This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <gnu.org/licenses>.
 */
package org.exoplatform.emailConnector;

import java.util.List;

import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Records what one class logs, at every level down to DEBUG, for as long as it is open;
 * closing it detaches the recorder and restores the logger's level.
 */
public class LogCapture implements AutoCloseable {

  private final Logger                      logger;

  private final Level                       level;

  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

  /**
   * Starts recording what a class logs.
   *
   * @param type the class whose logger is recorded
   */
  public LogCapture(Class<?> type) {
    logger = (Logger) LoggerFactory.getLogger(type);
    level = logger.getLevel();
    logger.setLevel(Level.DEBUG);
    appender.start();
    logger.addAppender(appender);
  }

  /**
   * What was logged so far, oldest first.
   *
   * @return the events
   */
  public List<ILoggingEvent> events() {
    return List.copyOf(appender.list);
  }

  /**
   * What was logged so far at WARN or above.
   *
   * @return the events
   */
  public List<ILoggingEvent> warningsAndAbove() {
    return events().stream().filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN)).toList();
  }

  /**
   * Whether any event so far carries a throwable, which the log prints as a stack.
   *
   * @return true when one does
   */
  public boolean anyStack() {
    return events().stream().anyMatch(event -> event.getThrowableProxy() != null);
  }

  /**
   * Detaches the recorder and restores the logger's level.
   */
  @Override
  public void close() {
    logger.detachAppender(appender);
    logger.setLevel(level);
  }
}
