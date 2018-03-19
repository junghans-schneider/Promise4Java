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
public abstract class Promise<ValueType> {

    public interface Resolver<ValueType> {
        public void resolve(ValueType value);
        public void resolve(Promise<ValueType> value);
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

    protected static Executor mDefaultExecutor = new DefaultExecutor();
    protected static Subscription<PromiseErrorHandler> mFallbackErrorHandler;


    protected State mState = State.QUEUED;
    protected ValueType mValue;
    protected Throwable mRejectCause;
    protected List<WeakReference<Promise<?>>> mAncestorPromises;
    protected List<Subscription<PromiseValueHandler<ValueType>>> mValueHandlers;
    protected List<Subscription<PromiseErrorHandler>> mErrorHandlers;


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

    /**
     * Sets the executor to use if no executor was specified for the promise's execute method or a promise handler.
     *
     * @param defaultExecutor the default executor
     */
    public static void setDefaultExecutor(Executor defaultExecutor) {
        mDefaultExecutor = defaultExecutor;
    }

    public static Executor getDefaultExecutor() {
        return mDefaultExecutor;
    }

    public static void setFallbackErrorHandler(PromiseErrorHandler fallbackErrorHandler) {
        setFallbackErrorHandler(null, fallbackErrorHandler);
    }

    public static void setFallbackErrorHandler(Executor executor, PromiseErrorHandler fallbackErrorHandler) {
        if (fallbackErrorHandler == null) {
            mFallbackErrorHandler = null;
        } else {
            mFallbackErrorHandler = new Subscription<PromiseErrorHandler>(executor, fallbackErrorHandler);
        }
    }

    protected abstract void execute(Resolver<ValueType> resolver) throws Exception;

    public boolean isFinished() {
        return mState == State.RESOLVED || mState == State.REJECTED;
    }

    public boolean isCancelled() {
        return isCancelled(mRejectCause);
    }

    public static boolean isCancelled(Throwable thr) {
        return thr instanceof CancellationException;
    }

    public Promise<ValueType> onValue(PromiseValueHandler<ValueType> handler) {
        return this.onValue(null, handler);
    }

    public Promise<ValueType> onValue(Executor executor, PromiseValueHandler<ValueType> handler) {
        synchronized(this) {
            if (isFinished()) {
                if (mState == State.RESOLVED) {
                    fireValue(executor, handler);
                }
            } else {
                if (mValueHandlers == null) {
                    mValueHandlers = new ArrayList<Subscription<PromiseValueHandler<ValueType>>>(1);
                }
                mValueHandlers.add(new Subscription<PromiseValueHandler<ValueType>>(executor, handler));
            }
        }
        return this;
    }

    public <ChildValueType> Promise<ChildValueType> then(PromiseThenHandler<ValueType, ChildValueType> handler) {
        return then(null, handler);
    }

    public <ChildValueType> Promise<ChildValueType> then(Executor executor, PromiseThenHandler<ValueType, ChildValueType> handler) {
        ThenWrapperHandler<ValueType, ChildValueType> wrapperHandler = new ThenWrapperHandler<ValueType, ChildValueType>(handler);
        Promise<ChildValueType> chainedPromise = wrapperHandler.getChildPromise();
        chainedPromise.addAncestor(this);

        onValue(executor, wrapperHandler);
        onError(executor, wrapperHandler);

        return chainedPromise;
    }

    public Promise<ValueType> onError(PromiseErrorHandler handler) {
        return this.onError(null, handler);
    }

    public Promise<ValueType> onError(Executor executor, PromiseErrorHandler handler) {
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

    public Promise<ValueType> always(Runnable handler) {
        return this.always(null, handler);
    }

    public Promise<ValueType> always(Executor executor, Runnable handler) {
        AlwaysWrapper wrapperHandler = new AlwaysWrapper(handler);
        onValue(executor, wrapperHandler);
        onError(executor, wrapperHandler);
        return this;
    }

    protected void addAncestor(Promise<?> ancestor) {
        if (ancestor.isFinished()) {
            return;
        }
        synchronized (this) {
            if (mAncestorPromises == null) {
                mAncestorPromises = new ArrayList<WeakReference<Promise<?>>>(1);
            }
            mAncestorPromises.add(new WeakReference<Promise<?>>(ancestor));
        }
    }

    protected void resolve(ValueType value) {
        if (value instanceof Promise) {
            // TODO: Check this in constructor (but how?)
            throw new IllegalArgumentException("Value of a promise must be no promise");
        }

        synchronized(this) {
            if (isFinished()) {
                return;
            }
            mState = State.RESOLVED;
            mValue = value;
            this.notifyAll();
        }

        fireFinished();
    }

    protected void resolve(Promise<ValueType> valuePromise) {
        addAncestor(valuePromise);
        valuePromise
                .onValue(new PromiseValueHandler<ValueType>() {
                    @Override
                    public void onValue(ValueType value) {
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
                for (WeakReference<Promise<?>> promiseRef : mAncestorPromises) {
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
        List<Subscription<PromiseValueHandler<ValueType>>> valueHandlers;
        List<Subscription<PromiseErrorHandler>> errorHandlers;
        List<Subscription<Runnable>> alwaysHandlers;
        synchronized(this) {
            state = mState;
            valueHandlers     = mValueHandlers;
            errorHandlers     = mErrorHandlers;
            mValueHandlers    = null;
            mErrorHandlers    = null;
            mAncestorPromises = null;
        }

        if (state == State.RESOLVED) {
            if (valueHandlers != null) {
                for (Subscription<PromiseValueHandler<ValueType>> subscription : valueHandlers) {
                    fireValue(subscription.executor, subscription.handler);
                }
            }
        } else if (state == State.REJECTED) {
            boolean errorWasHandled = false;
            if (errorHandlers != null) {
                for (Subscription<PromiseErrorHandler> subscription : errorHandlers) {
                    fireError(subscription.executor, subscription.handler);
                    if (!(subscription.handler instanceof AlwaysWrapper)) {
                        errorWasHandled = true;
                    }
                }
            }

            if (!errorWasHandled) {
                if (mFallbackErrorHandler == null) {
                    mFallbackErrorHandler = new Subscription<PromiseErrorHandler>(
                            null,
                            new PromiseErrorHandler() {
                                public void onError(Throwable thr) {
                                    onFallbackError("Error at the end of a promise chain", thr);
                                }
                            });
                }
                fireError(mFallbackErrorHandler.executor, mFallbackErrorHandler.handler);
            }
        } else {
            onFallbackError("Expected finished state, not " + mState);
        }
    }

    @SuppressWarnings("unchecked")
    protected void fireValue(Executor executor, final PromiseValueHandler<ValueType> handler) {
        assertState(State.RESOLVED);

        if (executor == null) {
            executor = getDefaultExecutor();
        }

        executor.execute(new Runnable() {
            public void run() {
                try {
                    handler.onValue(mValue);
                } catch (Throwable thr) {
                    String handlerType = (handler instanceof AlwaysWrapper) ? "always" : "onValue";
                    onFallbackError("Calling " + handlerType + " handler failed");
                }
            }
        });
    }

    protected void fireError(Executor executor, final PromiseErrorHandler handler) {
        assertState(State.REJECTED);

        if (executor == null) {
            executor = getDefaultExecutor();
        }

        executor.execute(new Runnable() {
            public void run() {
                try {
                    handler.onError(mRejectCause);
                } catch (Throwable thr) {
                    String handlerType = (handler instanceof AlwaysWrapper) ? "always" : "onError";
                    onFallbackError("Calling " + handlerType + " handler failed");
                }
            }
        });
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
    public ValueType getValue() {
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

    public ValueType waitForResult() throws Exception {
        return waitForResult(-1);
    }

    public ValueType waitForResult(long timeout) throws Exception {
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

    public static <ValueType> Promise<ValueType> resolvedPromise(ValueType value) {
        return new ResolvedPromise<ValueType>(value);
    }

    public static <ValueType> Promise<ValueType> rejectedPromise(Class<ValueType> type, Throwable rejectCause) {
        return new RejectedPromise<ValueType>(rejectCause);
    }

    public static Promise<Object[]> all(Object... promisesOrValues) {
        if (promisesOrValues == null || promisesOrValues.length == 0) {
            return new ResolvedPromise<Object[]>(new Object[0]);
        } else {
            return new AllPromise(promisesOrValues);
        }
    }

    protected void execute(Executor executor) {
        if (executor == null) {
            executor = getDefaultExecutor();
        }

        executor.execute(new Runnable() {
            public void run() {
                try {
                    synchronized(this) {
                        if (mState != State.QUEUED) {
                            return; // This promise has already started
                        }
                        mState = State.EXECUTING;
                    }

                    Resolver<ValueType> resolver = new Resolver<ValueType>() {
                        public void resolve(ValueType value) {
                            Promise.this.resolve(value);
                        }
                        public void resolve(Promise<ValueType> valuePromise) {
                            Promise.this.resolve(valuePromise);
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
        });
    }


    private static class DefaultExecutor implements Executor {
        private boolean mLoggedWarning = false;

        @Override
        public void execute(Runnable command) {
            if (!mLoggedWarning) {
                System.out.println("You should specify a default executor (using Promise.setDefaultExecutor), "
                        + "so promises can always be resolved asynchronously and fallback error handling can work properly");
                mLoggedWarning = true;
            }
            command.run();
        }
    }

    private static class ResolvedPromise<ValueType> extends Promise<ValueType> {

        ResolvedPromise(ValueType value) {
            super(null, false);
            resolve(value);
        }

        @Override
        protected void execute(Resolver<ValueType> resolver) {
        }

    }

    private static class RejectedPromise<ValueType> extends Promise<ValueType> {

        RejectedPromise(Throwable rejectCause) {
            super(null, false);
            reject(rejectCause);
        }

        @Override
        protected void execute(Resolver<ValueType> resolver) {
        }

    }

    private static class ThenWrapperHandler<ValueType, ChildValueType> implements PromiseValueHandler<ValueType>, PromiseErrorHandler {

        private PromiseThenHandler<ValueType, ChildValueType> mNestedHandler;
        private Promise<ChildValueType> mChildPromise;


        ThenWrapperHandler(PromiseThenHandler<ValueType, ChildValueType> nestedHandler) {
            mNestedHandler = nestedHandler;
            mChildPromise = new Promise<ChildValueType>() {
                @Override
                protected void execute(Resolver<ChildValueType> resolver) {
                }
            };
        }

        public Promise<ChildValueType> getChildPromise() {
            return mChildPromise;
        }

        @Override
        public void onValue(ValueType value) {
            try {
                mNestedHandler.onValue(value)
                        .onValue(new PromiseValueHandler<ChildValueType>() {
                            @Override
                            public void onValue(ChildValueType childValue) {
                                mChildPromise.resolve(childValue);
                            }
                        })
                        .onError(new PromiseErrorHandler() {
                            @Override
                            public void onError(Throwable thr) {
                                mChildPromise.reject(thr);
                            }
                        });
            } catch (Throwable thr) {
                mChildPromise.reject(thr);
            }
        }

        @Override
        public void onError(Throwable thr) {
            mChildPromise.reject(thr);
        }
    }


    private static class AlwaysWrapper<ValueType> implements PromiseValueHandler<ValueType>, PromiseErrorHandler  {

        private Runnable mNestedHandler;

        AlwaysWrapper(Runnable nestedHandler) {
            mNestedHandler = nestedHandler;
        }

        @Override
        public void onValue(ValueType value) {
            mNestedHandler.run();
        }

        @Override
        public void onError(Throwable thr) {
            mNestedHandler.run();
        }
    }


    private static class AllPromise extends Promise<Object[]> {

        private final Object[] mGatheredValues;
        private int mPendingHandlerCount;

        AllPromise(Object... promisesOrValues) {
            mGatheredValues = new Object[promisesOrValues.length];
            mPendingHandlerCount = 0;

            for (int i = 0; i < promisesOrValues.length; i++) {
                Object item = promisesOrValues[i];
                if (item instanceof Promise) {
                    Promise<Object> promise = (Promise<Object>) item;
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
        protected void execute(Resolver<Object[]> resolver) {}

        private void addHandlers(Promise<Object> promise, final int valueIndex) {
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
