/*
 * Copyright (c) 2001, Zoltan Farkas All Rights Reserved.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.spf4j.base;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.net.SocketException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for executing stuff with retry logic.
 *
 * @author zoly
 */
@ParametersAreNonnullByDefault
//CHECKSTYLE IGNORE RedundantThrows FOR NEXT 2000 LINES
public final class Callables {

    private Callables() {
    }

    private static final Logger LOG = LoggerFactory.getLogger(Callables.class);



    public static final RetryPredicate<?> RETRY_FOR_NULL_RESULT = new RetryPredicate<Object>() {
        @Override
        public Action apply(final Object input) {
            return (input != null) ? Action.ABORT : Action.RETRY;
        }
    };

    /**
     * A decent default retry predicate.
     * It might retry exceptions that might not be retriable..
     * (like IO exceptions thrown by parser libraries for parsing issues...)
     */
    public static final AdvancedRetryPredicate<Exception> DEFAULT_EXCEPTION_RETRY =
            new AdvancedRetryPredicate<Exception>() {
        @Override
        public AdvancedAction apply(@Nonnull final Exception input) {
            Throwable rootCause = com.google.common.base.Throwables.getRootCause(input);
            if (rootCause instanceof RuntimeException) {
                return AdvancedAction.ABORT;
            }
            Throwable e = Throwables.firstCause(input,
                    (ex) -> (ex instanceof SQLTransientException
                    || ex instanceof SQLRecoverableException
                    || ex instanceof SocketException
                    || ex instanceof TimeoutException));
            if (e != null) {
                LOG.debug("Exception encountered, retrying...", input);
                return AdvancedAction.RETRY;
            }
            return AdvancedAction.ABORT;
        }
    };

    public static final Predicate<Exception> DEFAULT_EXCEPTION_RETRY_PREDICATE =
            new Predicate<Exception>() {

        @Override
        @SuppressFBWarnings("NP_PARAMETER_MUST_BE_NONNULL_BUT_MARKED_AS_NULLABLE")
        public boolean test(final Exception t) {
            return DEFAULT_EXCEPTION_RETRY.apply(t) != AdvancedAction.ABORT;
        }

    };



    public static <T, EX extends Exception> T executeWithRetry(final TimeoutCallable<T, EX> what,
            final int nrImmediateRetries,
            final int maxRetryWaitMillis, final Class<EX> exceptionClass)
            throws InterruptedException, EX {
        return executeWithRetry(what, nrImmediateRetries, maxRetryWaitMillis,
                TimeoutRetryPredicate.NORETRY_FOR_RESULT, DEFAULT_EXCEPTION_RETRY, exceptionClass);
    }

    public static <T, EX extends Exception> T executeWithRetry(final TimeoutCallable<T, EX> what,
            final int nrImmediateRetries,
            final int maxRetryWaitMillis,
            final AdvancedRetryPredicate<Exception> retryOnException,
            final Class<EX> exceptionClass)
            throws InterruptedException, EX {
        return executeWithRetry(what, nrImmediateRetries, maxRetryWaitMillis,
                TimeoutRetryPredicate.NORETRY_FOR_RESULT, retryOnException, exceptionClass);
    }

    /**
     * After the immediate retries are done,
     * delayed retry with randomized Fibonacci values up to the specified max is executed.
     * @param <T> - the type returned by the Callable that is retried.
     * @param <EX> - the Exception thrown by the retried callable.
     * @param what - the callable to retry.
     * @param nrImmediateRetries - the number of immediate retries.
     * @param maxWaitMillis - maximum wait time in between retries.
     * @param retryOnReturnVal - predicate to control retry on return value;
     * @param retryOnException - predicate to retry on thrown exception.
     * @return the result of the callable.
     * @throws java.lang.InterruptedException - thrown if interrupted.
     * @throws EX - the exception declared to be thrown by the callable.
     */
    public static <T, EX extends Exception> T executeWithRetry(final TimeoutCallable<T, EX> what,
            final int nrImmediateRetries, final int maxWaitMillis,
            final TimeoutRetryPredicate<? super T> retryOnReturnVal,
            final AdvancedRetryPredicate<Exception> retryOnException,
            final Class<EX> exceptionClass)
            throws InterruptedException, EX {
        return executeWithRetry(what, retryOnReturnVal,
                new FibonacciBackoffRetryPredicate<>(retryOnException, nrImmediateRetries,
                        maxWaitMillis / 100, maxWaitMillis, EX_TYPE_CLASS_MAPPER), exceptionClass);
    }


    private static final class RetryData {

        private int immediateLeft;

        private int p1;

        private int p2;

        private final int maxDelay;

        RetryData(final int immediateLeft, final int p1, final int maxDelay) {
            this.immediateLeft = immediateLeft;
            if (p1 < 1) {
                this.p1 = 0;
                this.p2 = 1;
            } else {
                this.p1 = p1;
                this.p2 = p1;
            }
            this.maxDelay = maxDelay;
        }

        int nextDelay() {
            if (immediateLeft > 0) {
                immediateLeft--;
                return 0;
            } else if (p2 > maxDelay) {
                return maxDelay;
            } else {
                int result = p2;
                p2 = p1 + p2;
                p1 = result;
                return result;
            }
        }

    }

    static final Function<Exception, Class> EX_TYPE_CLASS_MAPPER = new Function<Exception, Class>() {

        @Override
        @SuppressFBWarnings("NP_PARAMETER_MUST_BE_NONNULL_BUT_MARKED_AS_NULLABLE")
        public Class apply(final Exception f) {
            return com.google.common.base.Throwables.getRootCause(f).getClass();
        }

    };


    public static final class FibonacciBackoffRetryPredicate<T> implements TimeoutRetryPredicate<T> {

        private final IntMath.XorShift32 random;

        private final AdvancedRetryPredicate<T> arp;

        private final int nrImmediateRetries;

        private final int maxWaitMillis;

        private final int minWaitMillis;

        private Map<Object, RetryData> retryRegistry;

        private final Function<T, ?> mapper;

        public FibonacciBackoffRetryPredicate(final AdvancedRetryPredicate<T> arp,
                final int nrImmediateRetries, final int minWaitMillis, final int maxWaitMillis,
                final Function<T, ?> mapper) {
            this.arp = arp;
            this.nrImmediateRetries = nrImmediateRetries;
            this.maxWaitMillis = maxWaitMillis;
            this.minWaitMillis = minWaitMillis;
            retryRegistry = null;
            this.mapper = mapper;
            this.random = new IntMath.XorShift32();
        }


        @Override
        @SuppressFBWarnings("MDM_THREAD_YIELD")
        public Action apply(final T value, final long deadline) throws InterruptedException, TimeoutException {
            long currentTime = System.currentTimeMillis();
            if (currentTime > deadline) {
                return Action.ABORT;
            }
            if (retryRegistry == null) {
                retryRegistry = new HashMap<>();
            }
            AdvancedAction action = arp.apply(value, deadline);
            switch (action) {
                case ABORT:
                    return Action.ABORT;
                case RETRY_IMMEDIATE:
                    return Action.RETRY;
                case RETRY_DELAYED:
                case RETRY:
                    RetryData retryData = getRetryData(value, action);
                    final int nextDelay = retryData.nextDelay();
                    long delay = Math.min(nextDelay, deadline - currentTime);
                    if (delay > 0) {
                        delay = Math.abs(random.nextInt()) % delay;
                        Thread.sleep(delay);
                    }
                    return Action.RETRY;
                default:
                    throw new RuntimeException("Unsupperted Retry Action " + action);

            }
        }

        RetryData getRetryData(final T value, final AdvancedAction action) {
            Object rootCauseClass = mapper.apply(value);
            RetryData data = retryRegistry.get(rootCauseClass);
            if (data == null) {
                data  = createRetryData(action);
                retryRegistry.put(rootCauseClass, data);
            }
            return data;
        }

        private RetryData createRetryData(final AdvancedAction action) {
            if (action == AdvancedAction.RETRY_DELAYED) {
                return new RetryData(0, minWaitMillis, maxWaitMillis);
            } else {
                return new RetryData(nrImmediateRetries, minWaitMillis, maxWaitMillis);
            }
        }


    }



    public static <T, EX extends Exception> T executeWithRetry(final TimeoutCallable<T, EX> what,
            final TimeoutRetryPredicate<? super T> retryOnReturnVal,
            final TimeoutRetryPredicate<Exception> retryOnException, final Class<EX> exceptionClass)
            throws InterruptedException, EX {
        final long deadline = what.getDeadline();
        return executeWithRetry(what,
                new TimeoutRetryPredicate2RetryPredicate<>(deadline, retryOnReturnVal),
                new TimeoutRetryPredicate2RetryPredicate<>(deadline, retryOnException), exceptionClass);
    }


    public abstract static class TimeoutCallable<T, EX extends Exception> extends CheckedCallable<T, EX> {

        private final long mdeadline;

        public TimeoutCallable(final int timeoutMillis) {
            mdeadline = System.currentTimeMillis() + timeoutMillis;
        }

        @Override
        public final T call() throws EX, InterruptedException, TimeoutException {
            return call(mdeadline);
        }

        public abstract T call(long deadline) throws EX, InterruptedException, TimeoutException;

        public final long getDeadline() {
            return mdeadline;
        }

    }

    public enum AdvancedAction {
        RETRY, // Retry based on default policy. (can be immediate or delayed)
        RETRY_IMMEDIATE, // Do immediate retry
        RETRY_DELAYED, // Do delayed retry
        ABORT // Abort, no retry, return last value/exception
    }


    public abstract static class AdvancedRetryPredicate<T> {

        //CHECKSTYLE:OFF designed for extension does not like this, but I need this for backwards compat.
        public AdvancedAction apply(final T value, final long deadline) {
            //CHECKSTYLE:ON
            return apply(value);
        }

        public abstract AdvancedAction apply(T value);

        public static final AdvancedRetryPredicate<?> NO_RETRY = new AdvancedRetryPredicate<Object>() {
            @Override
            public AdvancedAction apply(final Object value) {
                return AdvancedAction.ABORT;
            }
        };

    }




    public interface DelayPredicate<T> {
        /**
         * the number or millis of delay until the next retry, or -1 for abort.
         * @param value
         * @return
         */
        int apply(T value);

        DelayPredicate<Object> NORETRY_DELAY_PREDICATE = new DelayPredicate<Object>() {

            @Override
            public int apply(final Object value) {
                return -1;
            }

        };
    }


    public interface TimeoutDelayPredicate<T>  {

        /**
         *
         * @param value - the value to apply the predicate for.
         * @param deadline - the deadline in millis since EPOCH.
         * @return the number or millis of delay until the next retry, or -1 for abort.
         */
         int apply(T value, long deadline);


         TimeoutDelayPredicate<Object> NORETRY_FOR_RESULT = new TimeoutDelayPredicate<Object>() {

            @Override
            public int apply(final Object value, final long deadline) {
                return -1;
            }

        };


    }

    public static final class SmartRetryPredicate2TimeoutRetryPredicate<T>
    implements TimeoutRetryPredicate<T> {

        private final TimeoutDelayPredicate predicate;

        public SmartRetryPredicate2TimeoutRetryPredicate(final TimeoutDelayPredicate<T> predicate) {
            this.predicate = predicate;
        }



        @Override
        @SuppressFBWarnings("MDM_THREAD_YIELD")
        public Action apply(final T value, final long deadline) throws InterruptedException {
            int apply = predicate.apply(value, deadline);
            if (apply < 0) {
                return Action.ABORT;
            } else if (apply == 0) {
                return Action.RETRY;
            } else {
                Thread.sleep(apply);
                return Action.RETRY;
            }
        }

    }


    public static <T, EX extends Exception> T executeWithRetry(final TimeoutCallable<T, EX> what,
            final TimeoutDelayPredicate<T> retryOnReturnVal,
            final TimeoutDelayPredicate<Exception> retryOnException, final Class<EX> exceptionClass)
            throws InterruptedException, EX {
        return executeWithRetry(what, new SmartRetryPredicate2TimeoutRetryPredicate<>(retryOnReturnVal),
                new SmartRetryPredicate2TimeoutRetryPredicate<>(retryOnException), exceptionClass);
    }

   public static <T, EX extends Exception> T executeWithRetry(final TimeoutCallable<T, EX> what,
            final TimeoutDelayPredicate<Exception> retryOnException, final Class<EX> exceptionClass)
            throws InterruptedException, EX {
        return (T) executeWithRetry(what, (TimeoutDelayPredicate<T>) TimeoutDelayPredicate.NORETRY_FOR_RESULT,
                retryOnException, exceptionClass);
    }

    public interface TimeoutRetryPredicate<T> {

        Action apply(T value, long deadline)
                throws InterruptedException, TimeoutException;

        TimeoutRetryPredicate<Object> NORETRY_FOR_RESULT = new TimeoutRetryPredicate<Object>() {

            @Override
            public Action apply(final Object value, final long deadline) {
                return Action.ABORT;
            }

        };

    }

    public static final class TimeoutRetryPredicate2RetryPredicate<T> implements RetryPredicate<T> {

        private final long deadline;

        private final TimeoutRetryPredicate<T> predicate;

        public TimeoutRetryPredicate2RetryPredicate(final long deadline, final TimeoutRetryPredicate<T> predicate) {
            this.deadline = deadline;
            this.predicate = predicate;
        }



        @Override
        public Action apply(final T value) throws InterruptedException {
            try {
                return predicate.apply(value, deadline);
            } catch (TimeoutException ex) {
                throw new RuntimeException(ex);
            }
        }


    }


    /**
     * A callable that will be retried.
     * @param <T> - The type returned by  Callable.
     * @param <EX> - The type of exception thrown by call.
     */
    public abstract static class CheckedCallable<T, EX extends Exception> implements RetryCallable<T, EX> {

        @Override
        public abstract T call() throws EX, InterruptedException, TimeoutException;

    }



    /**
     * A callable that will be retried.
     * @param <T> - the type of the object returned by this callable.
     * @param <EX> - the exception type returned by this callable.
     */
    public interface RetryCallable<T, EX extends Exception> extends Callable<T> {

        /**
         * the method that is retried.
         * @return
         * @throws EX
         * @throws InterruptedException
         * @throws java.util.concurrent.TimeoutException
         */
        @Override
        T call() throws EX, InterruptedException, TimeoutException;

        /**
         * method to process result (after all retries exhausted).
         * @param lastRet
         * @return - the result to be returned.
         */
        default T lastReturn(T lastRet) {
          return lastRet;
        }

        /**
         * method to press the exception after all retries exhausted.
         * @param <EXX>
         * @param ex
         * @return - the exception to be thrown or null if swallowing is desired.
         */
        @Nullable
        default Exception lastException(Exception ex) {
          return ex;
        }

    }



    public enum Action { RETRY, ABORT }

    public interface RetryPredicate<T> {

        Action apply(T value)
                throws InterruptedException;
    }

    /**
     * Naive implementation of execution with retry logic. a callable will be executed and retry attempted in current
     * thread if the result and exception predicates. before retry, a callable can be executed that can abort the retry
     * and finish the function with the previous result.
     *
     * @param <T> - The type of callable to retry result;
     * @param <EX> - the exception thrown by the callable to retry.
     * @param what - the callable to retry.
     * @param retryOnReturnVal - the predicate to control retry on return value.
     * @param retryOnException - the predicate to return on retry value.
     * @return the result of the retried callable if successful.
     * @throws java.lang.InterruptedException - thrown if retry interrupted.
     * @throws EX - the exception thrown by callable.
     */
    @SuppressFBWarnings("RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE")
    public static <T, EX extends Exception> T executeWithRetry(
            final RetryCallable<T, EX> what,
            final RetryPredicate<? super T> retryOnReturnVal,
            final RetryPredicate<Exception> retryOnException,
            final Class<EX> exceptionClass)
            throws InterruptedException, EX {
        T result = null;
        Exception lastEx = null; // last exception
        try {
            result = what.call();
        } catch (InterruptedException ex1) {
            throw ex1;
        } catch (Exception e) { // only EX and RuntimeException
            lastEx = e;
        }
        Exception lastExChain = lastEx; // last exception chained with all previous exceptions
        while ((lastEx != null && retryOnException.apply(lastEx) == Action.RETRY)
                || retryOnReturnVal.apply(result) == Action.RETRY) {
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                throw new InterruptedException();
            }
            result = null;
            lastEx = null;
            try {
                result = what.call();
            } catch (InterruptedException ex1) {
                throw ex1;
            } catch (Exception e) { // only EX and RuntimeException
                lastEx = e;
                if (lastExChain != null) {
                    lastExChain = Throwables.suppress(e, lastExChain);
                } else {
                    lastExChain = e;
                }
            }
        }
        if (lastEx != null) {
            Exception ett = what.lastException(lastExChain);
            if (ett instanceof RuntimeException) {
                throw (RuntimeException) ett;
            } else if (ett == null) {
              return null;
            } else if (exceptionClass.isAssignableFrom(ett.getClass())) {
                throw (EX) ett;
            } else {
                throw new RuntimeException(ett);
            }
        }
        return what.lastReturn(result);
    }



    public static <T> Callable<T> synchronize(final Callable<T> callable) {
        return new Callable<T>() {

            @Override
            public synchronized T call() throws Exception {
                return callable.call();
            }
        };
    }

    /**
     * This is a duplicate of guava Callables.threadRenaming ...
     * will have to review for deprecation/removal.
     */
    public static <T> Callable<T> withName(final Callable<T> callable, final String name) {
        return new Callable<T>() {

            @Override
            public T call() throws Exception {
                Thread currentThread = Thread.currentThread();
                String origName = currentThread.getName();
                try {
                    currentThread.setName(origName + '[' + name + ']');
                    return callable.call();
                } finally {
                    currentThread.setName(origName);
                }
            }

            @Override
            public String toString() {
                return name;
            }

        };
    }


}
