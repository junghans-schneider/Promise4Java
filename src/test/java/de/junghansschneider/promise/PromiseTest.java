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

    private ExecutorService mBgExecutor;

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        if (mBgExecutor != null) {
            mBgExecutor.shutdown();
            mBgExecutor = null;
        }
    }

    private ExecutorService getBgExecutor() {
        if (mBgExecutor == null) {
            mBgExecutor = Executors.newSingleThreadExecutor();
        }
        return mBgExecutor;
    }


    private void examples() {
        // Normal chain, different handler types
        Promise examplePromise
                = new Promise() {
                    @Override
                    protected void execute(Promise.Resolver resolver) {
                        resolver.resolve(1234);
                    }
                }.
                then(new PromiseThenHandler<Integer>() {
                    @Override
                    public Promise onValue(Integer value) {
                        // Nest other promise
                        return new Promise() {
                            @Override
                            protected void execute(Promise.Resolver resolver) {
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
        Promise promise1 = null;
        Promise promise2 = null;
        Promise promise3 = null;
        Promise.all("value", promise1, promise2, promise3)
                .onValue(new PromiseValueHandler<Object[]>() {
                    @Override
                    public void onValue(Object[] values) {
                        // Use results
                    }
                });

        // Turn value or promise into promise
        Promise.when("value")
                .onValue(new PromiseValueHandler<String>() {
                    @Override
                    public void onValue(String value) {
                        // Use result
                    }
                });

        // Executors
        Executor backgroundExecutor = null;
        Executor uiExecutor = null;

        Promise multithreadPromise
                = new Promise(backgroundExecutor) {
                    @Override
                    protected void execute(Promise.Resolver resolver) {
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
        Promise promise
                = new Promise() {
                    @Override
                    protected void execute(Promise.Resolver resolver) {
                        assertFalse(handlerCalled[0]);
                        assertFalse(handlerCalled[1]);
                        assertFalse(handlerCalled[2]);
                        handlerCalled[0] = true;
                        resolver.resolve(1234);
                    }
                }
                .then(new PromiseThenHandler<Integer>() {
                    @Override
                    public Object onValue(Integer value) {
                        assertTrue(handlerCalled[0]);
                        assertFalse(handlerCalled[1]);
                        assertFalse(handlerCalled[2]);
                        handlerCalled[1] = true;
                        assertEquals(1234, value.intValue());
                        return "my-result";
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
        Promise promise
                = new Promise() {
                    @Override
                    protected void execute(Promise.Resolver resolver) {
                        resolver.resolve(new Promise() {
                            @Override
                            protected void execute(Promise.Resolver resolver) {
                                resolver.resolve(new Promise() {
                                    @Override
                                    protected void execute(Promise.Resolver resolver) {
                                        resolver.resolve(1234);
                                    }
                                });
                            }
                        });
                    }
                }
                .then(createPipeThenHandler())
                .then(createPipeThenHandler())
                .then(new PromiseThenHandler<Integer>() {
                    @Override
                    public Object onValue(Integer value) throws Exception {
                        assertEquals(1234, value.intValue());
                        return "handler-called";
                    }
                })
                .then(createPipeThenHandler())
                .then(createPipeThenHandler());

        assertEquals("handler-called", promise.waitForResult(5000));
    }

    public void testSimpleError() throws Throwable {
        final boolean[] handlerCalled = new boolean[] { false, false };
        Promise promise
                = new Promise() {
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
        Promise promise
                = new Promise() {
                    @Override
                    protected void execute(Promise.Resolver resolver) {
                        resolver.resolve(new Promise() {
                            @Override
                            protected void execute(Promise.Resolver resolver) {
                                resolver.resolve(new Promise() {
                                    @Override
                                    protected void execute(Promise.Resolver resolver) {
                                        throw new IllegalStateException("Test");
                                    }
                                });
                            }
                        });
                    }
                }
                .then(createPipeThenHandler())
                .then(createPipeThenHandler())
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
                .then(createPipeThenHandler())
                .then(createPipeThenHandler())
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
        Promise promise
                = waitForever()
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
        final LinkedList<Promise> promises = new LinkedList<Promise>();
        final Promise[] waitPromise = new Promise[1];

        Promise outerPromise = new Promise() {
            @Override
            protected void execute(Promise.Resolver resolver) {
                Promise promise = new Promise() {
                    @Override
                    protected void execute(Promise.Resolver resolver) {
                        Promise promise = waitForever();
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

        promises.add(outerPromise.then(createPipeThenHandler()));
        promises.add(promises.getLast()
                .then(createPipeThenHandler())
                .onError(new PromiseErrorHandler() {
                    @Override
                    public void onError(Throwable thr) {
                        if (Promise.isCancelled(thr)) {
                            handledCancel[0] = true;
                        }
                    }
                }));
        promises.add(promises.getLast().then(createPipeThenHandler()));
        promises.add(promises.getLast().then(createPipeThenHandler()));

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
        final LinkedList<Promise> promises = new LinkedList<Promise>();

        Promise outerPromise = new Promise() {
            @Override
            protected void execute(Promise.Resolver resolver) {
                Promise promise = new Promise() {
                    @Override
                    protected void execute(Promise.Resolver resolver) {
                        Promise promise = waitForever();
                        promises.add(promise);
                        resolver.resolve(promise);
                    }
                };
                promises.add(promise);
                resolver.resolve(promise);
            }
        };
        promises.add(outerPromise);

        promises.add(outerPromise.then(createPipeThenHandler()));
        promises.add(promises.getLast().then(createPipeThenHandler()));
        promises.add(promises.getLast()
                .then(createPipeThenHandler())
                .onError(new PromiseErrorHandler() {
                    @Override
                    public void onError(Throwable thr) {
                        if (Promise.isCancelled(thr)) {
                            handledCancel[0] = true;
                        }
                    }
                }));
        promises.add(promises.getLast().then(createPipeThenHandler()));
        promises.add(promises.getLast().then(createPipeThenHandler()));

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
        ExecutorService uiExecutor = Executors.newSingleThreadExecutor();
        uiExecutor.execute(new Runnable() {
            public void run() {
                Thread.currentThread().setName("test-ui");
            }
        });

        ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
        backgroundExecutor.execute(new Runnable() {
            public void run() {
                Thread.currentThread().setName("test-background");
            }
        });

        final boolean[] wasRightExecutor = new boolean[] { false, false, false, false, false };

        Promise promise
                = new Promise(uiExecutor) {
                    @Override
                    protected void execute(Resolver resolver) {
                        wasRightExecutor[0] = Thread.currentThread().getName().equals("test-ui");
                        resolver.resolve(1);
                    }
                }
                .then(backgroundExecutor, new PromiseThenHandler<Object>() {
                    public Object onValue(Object value) throws Throwable {
                        wasRightExecutor[1] = Thread.currentThread().getName().equals("test-background");
                        return null;
                    }
                })
                .then(backgroundExecutor, new PromiseThenHandler<Object>() {
                    public Object onValue(Object value) throws Throwable {
                        wasRightExecutor[2] = Thread.currentThread().getName().equals("test-background");
                        throw new Exception("Test exception");
                    }
                })
                .onError(uiExecutor, new PromiseErrorHandler() {
                    @Override
                    public void onError(Throwable thr) {
                        wasRightExecutor[3] = Thread.currentThread().getName().equals("test-ui");
                    }
                })
                .always(uiExecutor, new Runnable() {
                    @Override
                    public void run() {
                        wasRightExecutor[4] = Thread.currentThread().getName().equals("test-ui");
                    }
                })
                .then(uiExecutor, createPipeThenHandler());

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

        uiExecutor.shutdown();
        backgroundExecutor.shutdown();
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

        Promise promise1 = Promise.when(1234);
        Promise promise2 = new Promise(bgExecutor) {
            @Override
            protected void execute(Resolver resolver) {
                assertFalse(Thread.currentThread() == mainThread);
                try {
                    Thread.sleep(50);
                } catch (InterruptedException exc) {
                }
                resolver.resolve("Bla");
            }
        };
        Promise promise3 = new Promise(bgExecutor) {
            @Override
            protected void execute(Resolver resolver) {
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
                .then(createPipeThenHandler());

        outerPromise.waitForResult();
        assertTrue(handlerCalled[0]);
    }

    public void testAllError() throws Exception {
        final boolean[] handlerCalled = new boolean[] { false };

        final Thread mainThread = Thread.currentThread();
        ExecutorService bgExecutor = getBgExecutor();

        Promise promise1 = Promise.when(1234);
        Promise promise2 = new Promise(bgExecutor) {
            @Override
            protected void execute(Resolver resolver) {
                assertFalse(Thread.currentThread() == mainThread);
                try {
                    Thread.sleep(50);
                } catch (InterruptedException exc) {
                }
                throw new RuntimeException("Test");
            }
        };
        Promise promise3 = new Promise(bgExecutor) {
            @Override
            protected void execute(Resolver resolver) {
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

        Promise promise1 = waitForever();
        Promise promise2 = waitForever();
        Promise promise3 = waitForever();
        Promise allPromise = Promise.all(promise1, promise2, promise3);
        Promise handlerPromise = allPromise
                .onError(new PromiseErrorHandler() {
                    @Override
                    public void onError(Throwable thr) {
                        if (Promise.isCancelled(thr)) {
                            handledCancel[0] = true;
                        }
                    }
                });

        assertFalse(handledCancel[0]);
        assertFalse(promise1.isFinished());
        assertFalse(promise2.isFinished());
        assertFalse(promise3.isFinished());
        assertFalse(allPromise.isFinished());
        assertFalse(handlerPromise.isFinished());

        promise2.cancel();

        assertTrue(handledCancel[0]);
        assertFalse(promise1.isFinished());
        assertTrue(promise2.isCancelled());
        assertFalse(promise3.isFinished());
        assertTrue(allPromise.isCancelled());
        assertTrue(handlerPromise.isCancelled());
    }

    public void testAllCancelBackward() {
        doTestAllCancelBackward(false);
        doTestAllCancelBackward(true);
    }

    private void doTestAllCancelBackward(boolean wholeChain) {
        final boolean[] handledCancel = new boolean[] { false };

        Promise promise1 = waitForever();
        Promise promise2 = waitForever();
        Promise promise3 = waitForever();
        Promise allPromise
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
                .then(createPipeThenHandler());

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

    private Promise loadParkings(final int radius, final int minResultSize) {
        return
                new Promise() {
                    @Override
                    protected void execute(Resolver resolver) {
                        List<Object> parkingList = Arrays.asList(new Object[radius]);
                        resolver.resolve(parkingList);
                    }
                }
                .then(new PromiseThenHandler<List<Object>>() {
                    @Override
                    public Object onValue(List<Object> parkingList) {
                        if (parkingList.size() < minResultSize) {
                            return loadParkings(radius + 10, minResultSize);
                        } else {
                            return parkingList;
                        }
                    }
                });
    }

    private Promise waitForever() {
        return new Promise() {
            @Override
            protected void execute(Resolver resolver) {
            }
        };
    }

    private static PromiseThenHandler<Object> createPipeThenHandler() {
        return new PromiseThenHandler<Object>() {
            @Override
            public Object onValue(Object value) throws Exception {
                return value;
            }
        };
    }

}
