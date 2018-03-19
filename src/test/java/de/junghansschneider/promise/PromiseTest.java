//
//  Created by Til Schneider <github@murfman.de> on 09.12.14.
//  Copyright © 2014 Junghans und Schneider. License: MIT
//  https://github.com/junghans-schneider/Promise4Java
//

package de.junghansschneider.promise;

import junit.framework.TestCase;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PromiseTest extends TestCase {

    private Executor mDefaultExecutor;
    private ExecutorService mUiExecutor;
    private ExecutorService mBgExecutor;


    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mDefaultExecutor = Promise.getDefaultExecutor();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        Promise.setDefaultExecutor(mDefaultExecutor);
        if (mUiExecutor != null) {
            mUiExecutor.shutdown();
            mUiExecutor = null;
        }
        if (mBgExecutor != null) {
            mBgExecutor.shutdown();
            mBgExecutor = null;
        }
    }

    private ExecutorService getUiExecutor() {
        if (mUiExecutor == null) {
            mUiExecutor = Executors.newSingleThreadExecutor();
            mUiExecutor.execute(new Runnable() {
                public void run() {
                    Thread.currentThread().setName("test-ui");
                }
            });
        }
        return mUiExecutor;
    }

    private boolean isUiExecutor() {
        return Thread.currentThread().getName().equals("test-ui");
    }

    private ExecutorService getBgExecutor() {
        if (mBgExecutor == null) {
            mBgExecutor = Executors.newSingleThreadExecutor();
            mBgExecutor.execute(new Runnable() {
                public void run() {
                    Thread.currentThread().setName("test-background");
                }
            });
        }
        return mBgExecutor;
    }

    private boolean isBgExecutor() {
        return Thread.currentThread().getName().equals("test-background");
    }


    private void examples() {
        // Normal chain, different handler types
        Promise examplePromise
                = new Promise<Integer>() {
                    @Override
                    protected void execute(Promise.Resolver<Integer> resolver) {
                        resolver.resolve(1234);
                    }
                }.
                then(new PromiseThenHandler<Integer, String>() {
                    @Override
                    public Promise<String> onValue(Integer value) {
                        // Nest other promise
                        return new Promise<String>() {
                            @Override
                            protected void execute(Promise.Resolver<String> resolver) {
                                resolver.resolve("Hallo");
                            }
                        };
                    }
                }).
                onValue(new PromiseValueHandler<String>() {
                    @Override
                    public void onValue(String value) {
                        // Use result
                    }
                })
                .onError(new PromiseErrorHandler() {
                    @Override
                    public void onError(Throwable thr) {
                        // Handle error
                    }
                });

        // .all
        Promise<Integer> promise1 = null;
        Promise<String> promise2 = null;
        Promise<Double> promise3 = null;
        Promise.all("value", promise1, promise2, promise3)
                .onValue(new PromiseValueHandler<Object[]>() {
                    @Override
                    public void onValue(Object[] values) {
                        // Use results
                    }
                });

        // Turn value or promise into promise
        Promise.resolvedPromise("value")
                .onValue(new PromiseValueHandler<String>() {
                    @Override
                    public void onValue(String value) {
                        // Use result
                    }
                });

        // Executors
        Executor backgroundExecutor = null;
        Executor uiExecutor = null;

        Promise<String> multithreadPromise
                = new Promise<String>(backgroundExecutor) {
                    @Override
                    protected void execute(Promise.Resolver<String> resolver) {
                        // Load file in background thread
                        String content = null;
                        resolver.resolve(content);
                    }
                }.
                onValue(uiExecutor, new PromiseValueHandler<String>() {
                    @Override
                    public void onValue(String value) {
                        // Show result in UI
                    }
                })
                .onError(uiExecutor, new PromiseErrorHandler() {
                    @Override
                    public void onError(Throwable thr) {
                        // Show error in UI
                    }
                })
                .always(uiExecutor, new Runnable() {
                    @Override
                    public void run() {
                        // Hide spinner in UI
                    }
                });
    }

    public void testSimpleValue() throws Throwable {
        final boolean[] handlerCalled = new boolean[] { false, false, false };
        Promise<String> promise
                = new Promise<Integer>() {
                    @Override
                    protected void execute(Promise.Resolver<Integer> resolver) {
                        assertFalse(handlerCalled[0]);
                        assertFalse(handlerCalled[1]);
                        assertFalse(handlerCalled[2]);
                        handlerCalled[0] = true;
                        resolver.resolve(1234);
                    }
                }
                .then(new PromiseThenHandler<Integer, String>() {
                    @Override
                    public Promise<String> onValue(Integer value) {
                        assertTrue(handlerCalled[0]);
                        assertFalse(handlerCalled[1]);
                        assertFalse(handlerCalled[2]);
                        handlerCalled[1] = true;
                        assertEquals(1234, value.intValue());
                        return Promise.resolvedPromise("my-result");
                    }
                })
                .always(new Runnable() {
                    @Override
                    public void run() {
                        assertTrue(handlerCalled[0]);
                        assertTrue(handlerCalled[1]);
                        assertFalse(handlerCalled[2]);
                        handlerCalled[2] = true;
                    }
                });

        assertTrue(handlerCalled[0]);
        assertTrue(handlerCalled[1]);
        assertTrue(handlerCalled[2]);
        assertTrue(promise.isFinished());
        assertEquals("my-result", promise.getValue());
        assertEquals("my-result", promise.waitForResult(5000));
    }

    public void testComplexValue() throws Throwable {
        Promise<String> promise
                = new Promise<Integer>() {
                    @Override
                    protected void execute(Promise.Resolver<Integer> resolver) {
                        resolver.resolve(new Promise<Integer>() {
                            @Override
                            protected void execute(Promise.Resolver<Integer> resolver) {
                                resolver.resolve(new Promise<Integer>() {
                                    @Override
                                    protected void execute(Promise.Resolver<Integer> resolver) {
                                        resolver.resolve(1234);
                                    }
                                });
                            }
                        });
                    }
                }
                .then(createPipeThenHandler(Integer.class))
                .then(createPipeThenHandler(Integer.class))
                .then(new PromiseThenHandler<Integer, String>() {
                    @Override
                    public Promise<String> onValue(Integer value) throws Exception {
                        assertEquals(1234, value.intValue());
                        return Promise.resolvedPromise("handler-called");
                    }
                })
                .then(createPipeThenHandler(String.class))
                .then(createPipeThenHandler(String.class));

        assertEquals("handler-called", promise.waitForResult(5000));
    }

    public void testSimpleError() throws Throwable {
        final boolean[] handlerCalled = new boolean[] { false, false };
        Promise<String> promise
                = new Promise<String>() {
                    @Override
                    protected void execute(Promise.Resolver resolver) {
                        throw new IllegalStateException("Test");
                    }
                }
                .onError(new PromiseErrorHandler() {
                    @Override
                    public void onError(Throwable thr) {
                        assertFalse(handlerCalled[0]);
                        assertFalse(handlerCalled[1]);
                        handlerCalled[0] = true;
                        assertTrue(thr instanceof IllegalStateException);
                        assertEquals("Test", thr.getMessage());
                        //thr.printStackTrace();
                    }
                })
                .always(new Runnable() {
                    @Override
                    public void run() {
                        assertTrue(handlerCalled[0]);
                        assertFalse(handlerCalled[1]);
                        handlerCalled[1] = true;
                    }
                });

        assertTrue(handlerCalled[0]);
        assertTrue(handlerCalled[1]);
        assertTrue(promise.isFinished());
        assertNotNull(promise.getRejectCause());
    }

    public void testComplexError() throws Throwable {
        final boolean[] handlerCalled = new boolean[] { false, false };
        Promise<String> promise
                = new Promise<String>() {
                    @Override
                    protected void execute(Promise.Resolver<String> resolver) {
                        resolver.resolve(new Promise<String>() {
                            @Override
                            protected void execute(Promise.Resolver<String> resolver) {
                                resolver.resolve(new Promise<String>() {
                                    @Override
                                    protected void execute(Promise.Resolver<String> resolver) {
                                        throw new IllegalStateException("Test");
                                    }
                                });
                            }
                        });
                    }
                }
                .then(createPipeThenHandler(String.class))
                .then(createPipeThenHandler(String.class))
                .onError(new PromiseErrorHandler() {
                    @Override
                    public void onError(Throwable thr) {
                        assertFalse(handlerCalled[0]);
                        assertFalse(handlerCalled[1]);
                        handlerCalled[0] = true;
                        assertTrue(thr instanceof IllegalStateException);
                        assertEquals("Test", thr.getMessage());
                        //thr.printStackTrace();
                    }
                })
                .then(createPipeThenHandler(String.class))
                .then(createPipeThenHandler(String.class))
                .always(new Runnable() {
                    @Override
                    public void run() {
                        assertTrue(handlerCalled[0]);
                        assertFalse(handlerCalled[1]);
                        handlerCalled[1] = true;
                    }
                });

        assertTrue(handlerCalled[0]);
        assertTrue(handlerCalled[1]);
        assertTrue(promise.isFinished());
        assertNotNull(promise.getRejectCause());
    }

    // TODO: Test handler error (onError should be called if onValue throws an exception)
    // TODO: Test class cast exception

    public void testSimpleCancel() throws Throwable {
        final boolean[] handlerCalled = new boolean[] { false, false };
        Promise<String> promise
                = waitForever(String.class)
                .onError(new PromiseErrorHandler() {
                    @Override
                    public void onError(Throwable thr) {
                        assertFalse(handlerCalled[0]);
                        assertFalse(handlerCalled[1]);
                        if (Promise.isCancelled(thr)) {
                            handlerCalled[0] = true;
                        }
                    }
                })
                .always(new Runnable() {
                    @Override
                    public void run() {
                        assertTrue(handlerCalled[0]);
                        assertFalse(handlerCalled[1]);
                        handlerCalled[1] = true;
                    }
                });

        promise.cancel();
        assertTrue(handlerCalled[0]);
        assertTrue(handlerCalled[1]);
    }

    public void testComplexCancelForward() throws Throwable {
        final boolean[] handledCancel = new boolean[] { false };
        final LinkedList<Promise<String>> promises = new LinkedList<Promise<String>>();
        final Promise<?>[] waitPromise = new Promise[1];

        Promise<String> outerPromise = new Promise<String>() {
            @Override
            protected void execute(Promise.Resolver<String> resolver) {
                Promise<String> promise = new Promise<String>() {
                    @Override
                    protected void execute(Promise.Resolver<String> resolver) {
                        Promise<String> promise = waitForever(String.class);
                        waitPromise[0] = promise;
                        promises.add(promise);
                        resolver.resolve(promise);
                    }
                };
                promises.add(promise);
                resolver.resolve(promise);
            }
        };
        promises.add(outerPromise);

        promises.add(outerPromise.then(createPipeThenHandler(String.class)));
        promises.add(promises.getLast()
                .then(createPipeThenHandler(String.class))
                .onError(new PromiseErrorHandler() {
                    @Override
                    public void onError(Throwable thr) {
                        if (Promise.isCancelled(thr)) {
                            handledCancel[0] = true;
                        }
                    }
                }));
        promises.add(promises.getLast().then(createPipeThenHandler(String.class)));
        promises.add(promises.getLast().then(createPipeThenHandler(String.class)));

        assertFalse(handledCancel[0]);
        for (Promise promise : promises) {
            assertFalse(promise.isFinished());
            assertFalse(promise.isCancelled());
        }

        waitPromise[0].cancel();

        assertTrue(handledCancel[0]);
        for (Promise promise : promises) {
            assertTrue(promise.isFinished());
            assertTrue(promise.isCancelled());
        }
    }

    public void testComplexCancelBackward() throws Throwable {
        doTestComplexCancelBackward(false);
        doTestComplexCancelBackward(true);
    }

    public void doTestComplexCancelBackward(boolean wholeChain) throws Throwable {
        final boolean[] handledCancel = new boolean[] { false };
        final LinkedList<Promise<String>> promises = new LinkedList<Promise<String>>();

        Promise<String> outerPromise = new Promise<String>() {
            @Override
            protected void execute(Promise.Resolver<String> resolver) {
                Promise<String> promise = new Promise<String>() {
                    @Override
                    protected void execute(Promise.Resolver<String> resolver) {
                        Promise<String> promise = waitForever(String.class);
                        promises.add(promise);
                        resolver.resolve(promise);
                    }
                };
                promises.add(promise);
                resolver.resolve(promise);
            }
        };
        promises.add(outerPromise);

        promises.add(outerPromise.then(createPipeThenHandler(String.class)));
        promises.add(promises.getLast().then(createPipeThenHandler(String.class)));
        promises.add(promises.getLast()
                .then(createPipeThenHandler(String.class))
                .onError(new PromiseErrorHandler() {
                    @Override
                    public void onError(Throwable thr) {
                        if (Promise.isCancelled(thr)) {
                            handledCancel[0] = true;
                        }
                    }
                }));
        promises.add(promises.getLast().then(createPipeThenHandler(String.class)));
        promises.add(promises.getLast().then(createPipeThenHandler(String.class)));

        assertFalse(handledCancel[0]);
        for (Promise promise : promises) {
            assertFalse(promise.isFinished());
            assertFalse(promise.isCancelled());
        }

        Promise lastPromise = promises.getLast();
        lastPromise.cancel(wholeChain);

        assertEquals(wholeChain, handledCancel[0]);
        for (Promise promise : promises) {
            boolean expectCancelled = (wholeChain || promise == lastPromise);
            assertEquals(expectCancelled, promise.isFinished());
            assertEquals(expectCancelled, promise.isCancelled());
        }
    }

    public void testExecutors() {
        final boolean[] wasRightExecutor = new boolean[] { false, false, false, false, false };

        Promise<Long> promise
                = new Promise<Integer>(getUiExecutor()) {
                    @Override
                    protected void execute(Resolver<Integer> resolver) {
                        wasRightExecutor[0] = isUiExecutor();
                        resolver.resolve(1);
                    }
                }
                .then(getBgExecutor(), new PromiseThenHandler<Integer, String>() {
                    public Promise<String> onValue(Integer value) throws Throwable {
                        wasRightExecutor[1] = isBgExecutor();
                        return Promise.resolvedPromise("stuff");
                    }
                })
                .then(getBgExecutor(), new PromiseThenHandler<String, Long>() {
                    public Promise<Long> onValue(String value) throws Throwable {
                        wasRightExecutor[2] = isBgExecutor();
                        throw new Exception("Test exception");
                    }
                })
                .onError(getUiExecutor(), new PromiseErrorHandler() {
                    @Override
                    public void onError(Throwable thr) {
                        wasRightExecutor[3] = isUiExecutor();
                    }
                })
                .always(getUiExecutor(), new Runnable() {
                    @Override
                    public void run() {
                        wasRightExecutor[4] = isUiExecutor();
                    }
                })
                .then(getUiExecutor(), createPipeThenHandler(Long.class));

        try {
            promise.waitForResult(2000);
            fail("Exception expected");
        } catch (Exception exc) {
            assertEquals("Test exception", exc.getMessage());
        }

        assertTrue(wasRightExecutor[0]);
        assertTrue(wasRightExecutor[1]);
        assertTrue(wasRightExecutor[2]);
        assertTrue(wasRightExecutor[3]);
        assertTrue(wasRightExecutor[4]);
    }

    public void testDefaultExecutor() {
        final boolean[] wasRightExecutor = new boolean[] { false, false, false, false, false, false, false };

        Promise.setDefaultExecutor(getUiExecutor());

        Promise<Long> promise
                = new Promise<Integer>() {
                    @Override
                    protected void execute(Resolver<Integer> resolver) {
                        wasRightExecutor[0] = isUiExecutor();
                        resolver.resolve(1);
                    }
                }
                .then(getBgExecutor(), new PromiseThenHandler<Integer, String>() {
                    public Promise<String> onValue(Integer value) throws Throwable {
                        wasRightExecutor[1] = isBgExecutor();
                        return Promise.resolvedPromise("stuff");
                    }
                })
                .then(new PromiseThenHandler<String, Long>() {
                    public Promise<Long> onValue(String value) throws Throwable {
                        wasRightExecutor[2] = isUiExecutor();
                        throw new Exception("Test exception");
                    }
                })
                .onError(getBgExecutor(), new PromiseErrorHandler() {
                    @Override
                    public void onError(Throwable thr) {
                        wasRightExecutor[3] = isBgExecutor();
                    }
                })
                .onError(new PromiseErrorHandler() {
                    @Override
                    public void onError(Throwable thr) {
                        wasRightExecutor[4] = isUiExecutor();
                    }
                })
                .always(getBgExecutor(), new Runnable() {
                    @Override
                    public void run() {
                        wasRightExecutor[5] = isBgExecutor();
                    }
                })
                .always(new Runnable() {
                    @Override
                    public void run() {
                        wasRightExecutor[6] = isUiExecutor();
                    }
                })
                .then(createPipeThenHandler(Long.class));

        try {
            promise.waitForResult(2000);
            fail("Exception expected");
        } catch (Exception exc) {
            assertEquals("Test exception", exc.getMessage());
        }

        assertTrue(wasRightExecutor[0]);
        assertTrue(wasRightExecutor[1]);
        assertTrue(wasRightExecutor[2]);
        assertTrue(wasRightExecutor[3]);
        assertTrue(wasRightExecutor[4]);
        assertTrue(wasRightExecutor[5]);
        assertTrue(wasRightExecutor[6]);
    }

    public void testAllEmpty() {
        final boolean[] handlerCalled = new boolean[] { false };
        Promise.all()
                .onValue(new PromiseValueHandler<Object[]>() {
                    @Override
                    public void onValue(Object[] values) {
                        handlerCalled[0] = true;
                        assertEquals(0, values.length);
                    }
                });
        assertTrue(handlerCalled[0]);
    }

    public void testAllValuesOnly() {
        final boolean[] handlerCalled = new boolean[] { false };
        Promise.all(123, "Hallo", true, 12.8d)
                .onValue(new PromiseValueHandler<Object[]>() {
                    @Override
                    public void onValue(Object[] values) {
                        handlerCalled[0] = true;
                        assertEquals(4, values.length);
                        assertEquals(123, ((Integer) values[0]).intValue());
                        assertEquals("Hallo", values[1]);
                        assertEquals(true, ((Boolean) values[2]).booleanValue());
                        assertEquals(12.8, ((Double) values[3]).doubleValue());
                    }
                });
        assertTrue(handlerCalled[0]);
    }

    public void testAllResolved() throws Exception {
        final boolean[] handlerCalled = new boolean[] { false };

        final Thread mainThread = Thread.currentThread();
        ExecutorService bgExecutor = Executors.newSingleThreadExecutor();

        Promise<Integer> promise1 = Promise.resolvedPromise(1234);
        Promise<String> promise2 = new Promise<String>(bgExecutor) {
            @Override
            protected void execute(Resolver<String> resolver) {
                assertFalse(Thread.currentThread() == mainThread);
                try {
                    Thread.sleep(50);
                } catch (InterruptedException exc) {
                }
                resolver.resolve("Bla");
            }
        };
        Promise<Boolean> promise3 = new Promise<Boolean>(bgExecutor) {
            @Override
            protected void execute(Resolver<Boolean> resolver) {
                assertFalse(Thread.currentThread() == mainThread);
                try {
                    Thread.sleep(10);
                } catch (InterruptedException exc) {
                }
                resolver.resolve(false);
            }
        };

        Promise outerPromise = Promise.all("Hallo", promise1, promise2, promise3)
                .onValue(new PromiseValueHandler<Object[]>() {
                    @Override
                    public void onValue(Object[] values) {
                        handlerCalled[0] = true;
                        assertEquals(4, values.length);
                        assertEquals("Hallo", values[0]);
                        assertEquals(1234, ((Integer) values[1]).intValue());
                        assertEquals("Bla", values[2]);
                        assertEquals(false, ((Boolean) values[3]).booleanValue());
                    }
                })
                .then(createPipeThenHandler(Object[].class));

        outerPromise.waitForResult();
        assertTrue(handlerCalled[0]);
    }

    public void testAllError() throws Exception {
        final boolean[] handlerCalled = new boolean[] { false };

        final Thread mainThread = Thread.currentThread();
        ExecutorService bgExecutor = getBgExecutor();

        Promise<Integer> promise1 = Promise.resolvedPromise(1234);
        Promise<String> promise2 = new Promise<String>(bgExecutor) {
            @Override
            protected void execute(Resolver<String> resolver) {
                assertFalse(Thread.currentThread() == mainThread);
                try {
                    Thread.sleep(50);
                } catch (InterruptedException exc) {
                }
                throw new RuntimeException("Test");
            }
        };
        Promise<Boolean> promise3 = new Promise<Boolean>(bgExecutor) {
            @Override
            protected void execute(Resolver<Boolean> resolver) {
                assertFalse(Thread.currentThread() == mainThread);
                try {
                    Thread.sleep(10);
                } catch (InterruptedException exc) {
                }
                resolver.resolve(false);
            }
        };

        Promise outerPromise = Promise.all("Hallo", promise1, promise2, promise3)
                .onError(new PromiseErrorHandler() {
                    @Override
                    public void onError(Throwable thr) {
                        handlerCalled[0] = true;
                        assertTrue(thr instanceof RuntimeException);
                        assertEquals("Test", thr.getMessage());
                    }
                });

        try {
            outerPromise.waitForResult();
            fail("Exception expected");
        } catch (Throwable thr) {
            assertEquals("Test", thr.getMessage());
        }
        assertTrue(handlerCalled[0]);
    }

    public void testAllCancelForward() {
        final boolean[] handledCancel = new boolean[] { false };

        Promise<String> promise1 = waitForever(String.class);
        Promise<Integer> promise2 = waitForever(Integer.class);
        Promise<Boolean> promise3 = waitForever(Boolean.class);
        Promise<Object[]> allPromise
                = Promise.all(promise1, promise2, promise3)
                .onError(new PromiseErrorHandler() {
                    @Override
                    public void onError(Throwable thr) {
                        if (Promise.isCancelled(thr)) {
                            handledCancel[0] = true;
                        }
                    }
                });
        Promise lastPromise = allPromise
                .then(createPipeThenHandler(Object[].class));

        assertFalse(handledCancel[0]);
        assertFalse(promise1.isFinished());
        assertFalse(promise2.isFinished());
        assertFalse(promise3.isFinished());
        assertFalse(allPromise.isFinished());
        assertFalse(lastPromise.isFinished());

        promise2.cancel();

        assertTrue(handledCancel[0]);
        assertFalse(promise1.isFinished());
        assertTrue(promise2.isCancelled());
        assertFalse(promise3.isFinished());
        assertTrue(allPromise.isCancelled());
        assertTrue(lastPromise.isCancelled());
    }

    public void testAllCancelBackward() {
        doTestAllCancelBackward(false);
        doTestAllCancelBackward(true);
    }

    private void doTestAllCancelBackward(boolean wholeChain) {
        final boolean[] handledCancel = new boolean[] { false };

        Promise<String> promise1 = waitForever(String.class);
        Promise<Integer> promise2 = waitForever(Integer.class);
        Promise<Boolean> promise3 = waitForever(Boolean.class);
        Promise<Object[]> allPromise
                = Promise.all(promise1, promise2, promise3)
                .onError(new PromiseErrorHandler() {
                    @Override
                    public void onError(Throwable thr) {
                        if (Promise.isCancelled(thr)) {
                            handledCancel[0] = true;
                        }
                    }
                });
        Promise lastPromise = allPromise
                .then(createPipeThenHandler(Object[].class));

        assertFalse(handledCancel[0]);
        assertFalse(promise1.isFinished());
        assertFalse(promise2.isFinished());
        assertFalse(promise3.isFinished());
        assertFalse(allPromise.isFinished());
        assertFalse(lastPromise.isFinished());

        lastPromise.cancel(wholeChain);

        assertEquals(wholeChain, handledCancel[0]);
        assertEquals(wholeChain, promise1.isCancelled());
        assertEquals(wholeChain, promise2.isCancelled());
        assertEquals(wholeChain, promise3.isCancelled());
        assertEquals(wholeChain, allPromise.isCancelled());
        assertTrue(lastPromise.isCancelled());
    }

    public void testDynamicChain() throws Throwable {
        Promise promise = loadParkings(10, 30);
        @SuppressWarnings("unchecked")
        List<Object> parkingList = (List<Object>) promise.waitForResult();
        assertTrue(parkingList.size() >= 30);
    }

    private Promise<List<Object>> loadParkings(final int radius, final int minResultSize) {
        return
                new Promise<List<Object>>() {
                    @Override
                    protected void execute(Resolver<List<Object>> resolver) {
                        List<Object> parkingList = Arrays.asList(new Object[radius]);
                        resolver.resolve(parkingList);
                    }
                }
                .then(new PromiseThenHandler<List<Object>, List<Object>>() {
                    @Override
                    public Promise<List<Object>> onValue(List<Object> parkingList) {
                        if (parkingList.size() < minResultSize) {
                            return loadParkings(radius + 10, minResultSize);
                        } else {
                            return Promise.resolvedPromise(parkingList);
                        }
                    }
                });
    }

    private static <ValueType> Promise<ValueType> waitForever(Class<ValueType> type) {
        return new Promise<ValueType>() {
            @Override
            protected void execute(Resolver<ValueType> resolver) {
            }
        };
    }

    private static <ValueType> PromiseThenHandler<ValueType, ValueType> createPipeThenHandler(Class<ValueType> type) {
        return new PromiseThenHandler<ValueType, ValueType>() {
            @Override
            public Promise<ValueType> onValue(ValueType value) throws Exception {
                return Promise.resolvedPromise(value);
            }
        };
    }

}
