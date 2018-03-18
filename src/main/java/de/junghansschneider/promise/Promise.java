//
//  Created by Til Schneider <github@murfman.de> on 09.12.14.
//  Copyright © 2014 Junghans und Schneider. License: MIT
//  https://github.com/junghans-schneider/Promise4Java
//

package de.junghansschneider.promise;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;

/**
 *
 * Missing compared to Q promises:
 * - fin
 * - allSettled
 * - progress
 */
public abstract class Promise {

    public interface Resolver {
        public void resolve(Object value);
        public void reject(Throwable thr);
        public boolean isCancelled();
    }

    private static class Subscription<HandlerType> {
        Subscription(Executor executor, HandlerType handler) {
            if (handler == null) {
                throw new NullPointerException("handler is null");
            }

            this.executor = executor;
            this.handler = handler;
        }

        Executor executor;
        HandlerType handler;
    }


    protected static enum State { QUEUED, EXECUTING, PENDING, RESOLVED, REJECTED};

    protected static Subscription<PromiseErrorHandler> mFallbackErrorHandler;


    protected State mState = State.QUEUED;
    protected Object mValue;
    protected Throwable mRejectCause;
    protected List<WeakReference<Promise>> mAncestorPromises;
    protected List<Subscription<PromiseValueHandler<?>>> mValueHandlers;
    protected List<Subscription<PromiseErrorHandler>> mErrorHandlers;
    protected List<Subscription<Runnable>> mAlwaysHandlers;


    public Promise() {
        this(null, true);
    }

    public Promise(Executor executor) {
        this(executor, true);
    }

    protected Promise(Executor executor, boolean executeNow) {
        if (executeNow) {
            execute(executor);
        }
    }

    public static void setFallbackErrorHandler(PromiseErrorHandler fallbackErrorHandler) {
        setFallbackErrorHandler(null, fallbackErrorHandler);
    }

    public static void setFallbackErrorHandler(Executor executor, PromiseErrorHandler fallbackErrorHandler) {
        mFallbackErrorHandler = new Subscription<PromiseErrorHandler>(executor, fallbackErrorHandler);
    }

    protected abstract void execute(Resolver resolver);

    public boolean isFinished() {
        return mState == State.RESOLVED || mState == State.REJECTED;
    }

    public boolean isCancelled() {
        return isCancelled(mRejectCause);
    }

    public static boolean isCancelled(Throwable thr) {
        return thr instanceof CancellationException;
    }

    public Promise onValue(PromiseValueHandler<?> handler) {
        return this.onValue(null, handler);
    }

    public Promise onValue(Executor executor, PromiseValueHandler<?> handler) {
        synchronized(this) {
            if (isFinished()) {
                if (mState == State.RESOLVED) {
                    fireValue(executor, handler);
                }
            } else {
                if (mValueHandlers == null) {
                    mValueHandlers = new ArrayList<Subscription<PromiseValueHandler<?>>>(1);
                }
                mValueHandlers.add(new Subscription<PromiseValueHandler<?>>(executor, handler));
            }
        }
        return this;
    }

    public Promise then(PromiseThenHandler<?> handler) {
        return then(null, handler);
    }

    public Promise then(Executor executor, PromiseThenHandler<?> handler) {
        ThenWrapperHandler wrapperHandler = new ThenWrapperHandler(handler);
        Promise chainedPromise = wrapperHandler.getPromise();
        chainedPromise.addAncestor(this);

        onValue(executor, wrapperHandler);
        onError(executor, wrapperHandler);

        return chainedPromise;
    }

    public Promise onError(PromiseErrorHandler handler) {
        return this.onError(null, handler);
    }

    public Promise onError(Executor executor, PromiseErrorHandler handler) {
        synchronized(this) {
            if (isFinished()) {
                if (mState == State.REJECTED) {
                    fireError(executor, handler);
                }
            } else {
                if (mErrorHandlers == null) {
                    mErrorHandlers = new ArrayList<Subscription<PromiseErrorHandler>>(1);
                }
                mErrorHandlers.add(new Subscription<PromiseErrorHandler>(executor, handler));
            }
        }
        return this;
    }

    public Promise always(Runnable handler) {
        return this.always(null, handler);
    }

    public Promise always(Executor executor, Runnable handler) {
        synchronized(this) {
            if (isFinished()) {
                fireAlways(executor, handler);
            } else {
                if (mAlwaysHandlers == null) {
                    mAlwaysHandlers = new ArrayList<Subscription<Runnable>>(1);
                }
                mAlwaysHandlers.add(new Subscription<Runnable>(executor, handler));
            }
        }
        return this;
    }

    public Promise end() {
        if (mFallbackErrorHandler == null) {
            mFallbackErrorHandler = new Subscription<PromiseErrorHandler>(
                    null,
                    new PromiseErrorHandler() {
                        public void onError(Throwable thr) {
                            onFallbackError("Error at the end of a promise chain", thr);
                        }
                    });
        }

        onError(mFallbackErrorHandler.executor, mFallbackErrorHandler.handler);
        return this;
    }

    protected void addAncestor(Promise ancestor) {
        if (ancestor.isFinished()) {
            return;
        }
        synchronized (this) {
            if (mAncestorPromises == null) {
                mAncestorPromises = new ArrayList<WeakReference<Promise>>(1);
            }
            mAncestorPromises.add(new WeakReference<Promise>(ancestor));
        }
    }

    protected void resolve(Object value) {
        Promise nestedPromise = null;
        synchronized(this) {
            if (isFinished()) {
                return;
            }
            if (value instanceof Promise) {
                nestedPromise = (Promise) value;
                addAncestor(nestedPromise);
            } else {
                mState = State.RESOLVED;
                mValue = value;
                this.notifyAll();
            }
        }

        if (nestedPromise == null) {
            fireFinished();
        } else {
            nestedPromise
                    .onValue(new PromiseValueHandler<Object>() {
                        @Override
                        public void onValue(Object value) {
                            resolve(value);
                        }
                    })
                    .onError(new PromiseErrorHandler() {
                        @Override
                        public void onError(Throwable thr) {
                            reject(thr);
                        }
                    });
        }
    }

    protected void reject(Throwable thr) {
        boolean alreadyFinished;
        synchronized(this) {
            alreadyFinished = isFinished();
            if (! alreadyFinished) {
                mState = State.REJECTED;
                mRejectCause = thr;
                this.notifyAll();
            }
        }

        if (alreadyFinished && !isCancelled (thr)) {
            onFallbackError("Catched error after promise was finished", thr);
        } else {
            // TODO: Fallback-handle unhandled errors (Problem: error handlers may be called asynchronously in Executor)
            fireFinished();
        }
    }

    public boolean cancel() {
        return cancel(false);
    }

    /**
     * Tries to cancel the promise.
     *
     * @param wholeChain if true all parent promises having will be cancelled as well unless they have other uncancelled
     *        child promises.
     * @return whether the promise could be cancelled (= whether it hasn't settled before)
     */
    public boolean cancel(boolean wholeChain) {
        synchronized(this) {
            if (isFinished()) {
                return false;
            }
            mState = State.REJECTED;
            mRejectCause = new CancellationException("Promise was cancelled");

            if (wholeChain && (mAncestorPromises != null)) {
                for (WeakReference<Promise> promiseRef : mAncestorPromises) {
                    Promise promise = promiseRef.get();
                    if (promise != null) {
                        promise.cancel(true);
                    }
                }
            }

            this.notifyAll();
        }

        fireFinished();

        return true;
    }

    protected void fireFinished() {
        assertFinished();

        State state;
        List<Subscription<PromiseValueHandler<?>>> valueHandlers;
        List<Subscription<PromiseErrorHandler>> errorHandlers;
        List<Subscription<Runnable>> alwaysHandlers;
        synchronized(this) {
            state = mState;
            valueHandlers     = mValueHandlers;
            errorHandlers     = mErrorHandlers;
            alwaysHandlers    = mAlwaysHandlers;
            mValueHandlers    = null;
            mErrorHandlers    = null;
            mAlwaysHandlers   = null;
            mAncestorPromises = null;
        }

        if (state == State.RESOLVED) {
            if (valueHandlers != null) {
                for (Subscription<PromiseValueHandler<?>> subscription : valueHandlers) {
                    fireValue(subscription.executor, subscription.handler);
                }
            }
        } else if (state == State.REJECTED) {
            if (errorHandlers != null) {
                for (Subscription<PromiseErrorHandler> subscription : errorHandlers) {
                    fireError(subscription.executor, subscription.handler);
                }
            }
        } else {
            onFallbackError("Expected finished state, not " + mState);
        }
        if (alwaysHandlers != null) {
            for (Subscription<Runnable> subscription : alwaysHandlers) {
                fireAlways(subscription.executor, subscription.handler);
            }
        }
    }

    @SuppressWarnings("unchecked")
    protected void fireValue(Executor executor, final PromiseValueHandler<?> handler) {
        assertState(State.RESOLVED);

        if (executor == null) {
            try {
                ((PromiseValueHandler<Object>) handler).onValue(mValue);
            } catch (Throwable thr) {
                onFallbackError("Calling onValue handler failed");
            }
        } else {
            executor.execute(new Runnable() {
                public void run() {
                    try {
                        ((PromiseValueHandler<Object>) handler).onValue(mValue);
                    } catch (Throwable thr) {
                        onFallbackError("Calling onValue handler failed");
                    }
                }
            });
        }
    }

    protected void fireError(Executor executor, final PromiseErrorHandler handler) {
        assertState(State.REJECTED);

        if (executor == null) {
            try {
                handler.onError(mRejectCause);
            } catch (Throwable thr) {
                onFallbackError("Calling onError handler failed");
            }
        } else {
            executor.execute(new Runnable() {
                public void run() {
                    try {
                        handler.onError(mRejectCause);
                    } catch (Throwable thr) {
                        onFallbackError("Calling onError handler failed");
                    }
                }
            });
        }
    }

    protected void fireAlways(Executor executor, final Runnable handler) {
        assertFinished();

        if (executor == null) {
            try {
                handler.run();
            } catch (Throwable thr) {
                onFallbackError("Calling always handler failed");
            }
        } else {
            executor.execute(new Runnable() {
                public void run() {
                    try {
                        handler.run();
                    } catch (Throwable thr) {
                        onFallbackError("Calling always handler failed");
                    }
                }
            });
        }
    }

    protected static void onFallbackError(String msg) {
        onFallbackError(msg, new Exception(msg));
    }

    protected static void onFallbackError(String msg, Throwable thr) {
        System.err.println(msg);
        thr.printStackTrace();
    }

    protected void assertState(State state) {
        if (mState != state) {
            throw new IllegalStateException("Expected promise state " + state + ", not " + mState);
        }
    }

    protected void assertFinished() {
        if (! isFinished()) {
            throw new IllegalStateException("Promise is not finished yet");
        }
    }

    /**
     * Returns the value if the promise was resolved. In other cases null is returned.
     *
     * @return the value - or null if there is no value (yet)
     */
    public Object getValue() {
        return mValue;
    }

    /**
     * Returns the exception if the promise was rejected. In other cases null is returned.
     *
     * @return the exception - or null if there is no exception (yet)
     */
    public Throwable getRejectCause() {
        return mRejectCause;
    }

    public Object waitForResult() throws Exception {
        return waitForResult(-1);
    }

    public Object waitForResult(long timeout) throws Exception {
        long timeoutTime = (timeout > 0) ? (System.currentTimeMillis() + timeout) : -1;
        synchronized(this) {
            while (! isFinished()) {
                if (timeout > 0) {
                    long timeLeft = timeoutTime - System.currentTimeMillis();
                    if (timeLeft < 0) {
                        throw new TimeoutException("Waiting for promise result timed out");
                    }
                    this.wait(timeLeft);
                } else {
                    this.wait();
                }
            }
        }

        if (mState == State.RESOLVED) {
            return mValue;
        } else if (mState == State.REJECTED) {
            if (mRejectCause instanceof Error) {
                throw (Error) mRejectCause;
            } else {
                throw (Exception) mRejectCause;
            }
        } else {
            throw new IllegalStateException("Expected settled state, but state is " + mState);
        }
    }

    public static Promise when(final Object promiseOrValue) {
        if (promiseOrValue instanceof Promise) {
            return (Promise) promiseOrValue;
        } else {
            return new FinishedPromise(promiseOrValue);
        }
    }

    public static Promise all(Object... promisesOrValues) {
        if (promisesOrValues == null || promisesOrValues.length == 0) {
            return new FinishedPromise(new Object[0]);
        } else {
            return new AllPromise(promisesOrValues);
        }
    }

    protected void execute(Executor executor) {
        if (executor == null) {
            doExecute();
        } else {
            executor.execute(new Runnable() {
                public void run() {
                    doExecute();
                }
            });
        }
    }

    private void doExecute() {
        try {
            synchronized(this) {
                if (mState != State.QUEUED) {
                    return; // This promise has already started
                }
                mState = State.EXECUTING;
            }

            Resolver resolver = new Resolver() {
                public void resolve(Object value) {
                    Promise.this.resolve(value);
                }
                public void reject(Throwable thr) {
                    Promise.this.reject(thr);
                }
                public boolean isCancelled() {
                    return Promise.this.isCancelled();
                }
            };

            execute(resolver);

            synchronized(this) {
                if (! isFinished()) {
                    mState = State.PENDING;
                }
            }
        } catch (Throwable thr) {
            reject(thr);
        }
    }


    private static class FinishedPromise extends Promise {

        FinishedPromise(Object value) {
            super(null, false);
            resolve(value);
        }

        @Override
        protected void execute(Resolver resolver) {
        }

    }

    private static class ThenWrapperHandler<ValueType> implements PromiseValueHandler<ValueType>, PromiseErrorHandler {

        private PromiseThenHandler<ValueType> mNestedHandler;
        private Promise mPromise;


        ThenWrapperHandler(PromiseThenHandler<ValueType> nestedHandler) {
            mNestedHandler = nestedHandler;
            mPromise = new Promise() {
                @Override
                protected void execute(Resolver resolver) {
                }
            };
        }

        public Promise getPromise() {
            return mPromise;
        }

        @Override
        public void onValue(ValueType value) {
            try {
                @SuppressWarnings("unchecked")
                Object nestedValue = mNestedHandler.onValue(value);
                mPromise.resolve(nestedValue);
            } catch (Throwable thr) {
                mPromise.reject(thr);
            }
        }

        @Override
        public void onError(Throwable thr) {
            mPromise.reject(thr);
        }
    }


    private static class AllPromise extends Promise {

        private final Object[] mGatheredValues;
        private int mPendingHandlerCount;

        AllPromise(Object... promisesOrValues) {
            mGatheredValues = new Object[promisesOrValues.length];
            mPendingHandlerCount = 0;

            for (int i = 0; i < promisesOrValues.length; i++) {
                Object item = promisesOrValues[i];
                if (item instanceof Promise) {
                    Promise promise = (Promise) item;
                    if (promise.isFinished()) {
                        mGatheredValues[i] = promise.getValue();
                    } else {
                        addAncestor(promise);
                        mPendingHandlerCount++;
                        addHandlers(promise, i);
                    }
                } else {
                    mGatheredValues[i] = item;
                }
            }

            if (mPendingHandlerCount == 0) {
                resolve(mGatheredValues);
            }
        }

        @Override
        protected void execute(Resolver resolver) {}

        private void addHandlers(Promise promise, final int valueIndex) {
            promise
                    .onValue(new PromiseValueHandler<Object>() {
                        @Override
                        public void onValue(Object value) {
                            boolean finished;
                            synchronized(mGatheredValues) {
                                mPendingHandlerCount--;
                                mGatheredValues[valueIndex] = value;
                                finished = (mPendingHandlerCount == 0);
                            }
                            if (finished) {
                                resolve(mGatheredValues);
                            }
                        }
                    })
                    .onError(new PromiseErrorHandler() {
                        @Override
                        public void onError(Throwable thr) {
                            reject(thr);
                        }
                    });
        }

    }

}
