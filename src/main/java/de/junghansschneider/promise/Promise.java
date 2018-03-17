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

    protected static enum State { QUEUED, EXECUTING, PENDING, RESOLVED, REJECTED};

    protected static PromiseHandler<?> mFallbackErrorHandler;


    protected State mState = State.QUEUED;
    protected Object mValue;
    protected Throwable mRejectCause;
    protected List<WeakReference<Promise>> mAncestorPromises;
    protected List<PromiseHandler<?>> mHandlers;

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

    public static void setFallbackErrorHandler(PromiseHandler<?> fallbackErrorHandler) {
        mFallbackErrorHandler = fallbackErrorHandler;
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

    public Promise then(PromiseHandler<?> handler) {
        WrapperHandler wrapperHandler = new WrapperHandler(handler);
        Promise chainedPromise = wrapperHandler.getPromise();
        chainedPromise.addAncestor(this);

        handle(wrapperHandler);

        return chainedPromise;
    }

    public Promise handle(PromiseHandler<?> handler) {
        synchronized(this) {
            if (isFinished()) {
                fireFinished(handler);
            } else {
                if (mHandlers == null) {
                    mHandlers = new ArrayList<PromiseHandler<?>>(1);
                }
                mHandlers.add(handler);
            }
        }
        return this;
    }

    public Promise end() {
        if (mFallbackErrorHandler == null) {
            mFallbackErrorHandler = new PromiseHandler<Void>() {
                public Object onError(Throwable thr) throws Throwable {
                    onFallbackError("Error at the end of a promise chain", thr);
                    return null;
                }
            };
        }

        handle(mFallbackErrorHandler);
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
            nestedPromise.handle(new PromiseHandler<Object>() {
                @Override
                public Object onValue(Object value) throws Exception {
                    resolve(value);
                    return null;
                }
                @Override
                public Object onError(Throwable thr) throws Throwable {
                    reject(thr);
                    return null;
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

        if (alreadyFinished && ! (thr instanceof CancellationException)) {
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
        List<PromiseHandler<?>> handlers;
        synchronized(this) {
            handlers = mHandlers;
            mHandlers = null;
            mAncestorPromises = null;
        }

        if (handlers != null) {
            for (PromiseHandler<?> handler : handlers) {
                fireFinished(handler);
            }
        }
    }

    protected void fireFinished(final PromiseHandler<?> handler) {
        Executor executor = handler.getExecutor();

        if (executor == null) {
            doFireFinished(handler);
        } else {
            executor.execute(new Runnable() {
                public void run() {
                    doFireFinished(handler);
                }
            });
        }
    }

    private void doFireFinished(PromiseHandler<?> handler) {
        State state = mState;
        if (state == State.RESOLVED) {
            try {
                @SuppressWarnings("unchecked")
                Object ownValue = ((PromiseHandler<Object>) handler).onValue(mValue);
                resolve(ownValue);
            } catch (Throwable thr) {
                fireError(handler, new Exception("Calling onValue handler failed", thr));
            }
        } else if (state == State.REJECTED) {
            fireError(handler, mRejectCause);
        } else {
            fireError(handler, new IllegalStateException("Expected finished state, not " + mState));
        }

        try {
            handler.always();
        } catch (Throwable thr) {
            fireError(handler, new Exception("Calling always handler failed", thr));
        }
    }

    private void fireError(PromiseHandler<?> handler, Throwable thr) {
        try {
            handler.onError(thr);
        } catch (Throwable thr2) {
            onFallbackError("Calling onError handler failed", thr2);
        }
    }

    protected static void onFallbackError(String msg, Throwable thr) {
        System.err.println(msg);
        thr.printStackTrace();
    }

    protected void assertFinished() {
        if (! isFinished()) {
            throw new IllegalStateException("Promise is not finished yet");
        }
    }

    /**
     * Returns the value if the promise finished successfully. In other cases null is returned.
     *
     * @return the value - or null if there is no value (yet)
     */
    public Object getValue() {
        return mValue;
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
                        throw new TimeoutException();
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

    private static class WrapperHandler extends PromiseHandler<Object> {

        private PromiseHandler<?> mNestedHandler;
        private Promise mPromise;


        WrapperHandler(PromiseHandler<?> nestedHandler) {
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
        public Object onValue(Object value) throws Throwable {
            try {
                @SuppressWarnings("unchecked")
                Object nestedValue = ((PromiseHandler<Object>) mNestedHandler).onValue(value);
                if (mPromise != null) {
                    mPromise.resolve(nestedValue);
                }
            } catch (Throwable thr) {
                if (mPromise != null) {
                    mPromise.reject(thr);
                } else {
                    throw thr;
                }
            }
            return null;
        }

        @Override
        public Object onError(Throwable cause) throws Throwable {
            try {
                Object nestedValue = mNestedHandler.onError(cause);
                if (mPromise != null) {
                    mPromise.resolve(nestedValue);
                }
            } catch (Throwable thr) {
                if (mPromise != null) {
                    mPromise.reject(thr);
                } else {
                    throw thr;
                }
            }
            return null;
        }

        @Override
        public void always() {
            try {
                mNestedHandler.always();
            } catch (Throwable thr) {
                if (mPromise != null) {
                    mPromise.reject(thr);
                } else {
                    onFallbackError("Calling always handler failed", thr);
                }
            }
        }

    }


    private static class AllPromise extends Promise {

        private Object[] mGatheredValues;
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
                        addHandler(promise, i);
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

        private void addHandler(Promise promise, final int valueIndex) {
            promise.handle(new PromiseHandler<Object>() {
                @Override
                public Object onValue(Object value) throws Throwable {
                    boolean finished;
                    synchronized(mGatheredValues) {
                        mPendingHandlerCount--;
                        mGatheredValues[valueIndex] = value;
                        finished = (mPendingHandlerCount == 0);
                    }
                    if (finished) {
                        resolve(mGatheredValues);
                    }

                    return null;
                }
                @Override
                public Object onError(Throwable thr) throws Throwable {
                    reject(thr);
                    return null;
                }
            });
        }

    }

}
