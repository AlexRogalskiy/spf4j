/*
 * Copyright 2017 SPF4J.
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
package org.spf4j.failsafe;

import java.util.concurrent.ThreadLocalRandom;

/**
 * A randomizing Backoff strategy.
 * @author Zoltan Farkas
 */
public class RandomizedBackoff implements BackoffDelay {

  private final BackoffDelay wrapped;

  public RandomizedBackoff(final BackoffDelay wrapped) {
    this.wrapped = wrapped;
  }

  @Override
  public long nextDelay() {
    return ThreadLocalRandom.current().nextLong(wrapped.nextDelay());
  }


}