/*
 * Copyright 2018 SPF4J.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.spf4j.test.log;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import javax.annotation.concurrent.ThreadSafe;
import org.slf4j.Logger;
import org.slf4j.Marker;

/**
 * @author Zoltan Farkas
 */
@ParametersAreNonnullByDefault
@ThreadSafe
public final class LogRecord {


  private final Thread thread;
  private final Logger logger;
  private final Level level;
  private final long timeStamp;
  private final Marker marker;
  private final String format;
  private final Object[] arguments;
  private Set<Object> attachments;

  public LogRecord(final Logger logger, final Level level,
          final String format, final Object... arguments) {
    this(logger, level, null, format, arguments);
  }

  public LogRecord(final Logger logger, final Level level,
          @Nullable final Marker marker, final String format, final Object... arguments) {
    this.logger = logger;
    this.level = level;
    this.timeStamp = System.currentTimeMillis();
    this.marker = marker;
    this.format = format;
    this.arguments = arguments;
    this.thread = Thread.currentThread();
    this.attachments = Collections.EMPTY_SET;
  }

  public Logger getLogger() {
    return logger;
  }

  public Level getLevel() {
    return level;
  }

  public long getTimeStamp() {
    return timeStamp;
  }

  @Nullable
  public Marker getMarker() {
    return marker;
  }

  public String getFormat() {
    return format;
  }

  public Object[] getArguments() {
    return arguments;
  }

  public Thread getThread() {
    return thread;
  }

  public synchronized void attach(final Object obj) {
    if (attachments.isEmpty()) {
      attachments = new HashSet<>(2);
    }
    attachments.add(obj);
  }

  public synchronized boolean hasAttachment(final Object obj) {
    return attachments.contains(obj);
  }

  @Override
  public String toString() {
    return "LogRecord{" + "thread=" + thread + ", logger=" + logger + ", level="
            + level + ", timeStamp=" + timeStamp + ", marker=" + marker + ", format="
            + format + ", arguments=" + arguments + '}';
  }



}
