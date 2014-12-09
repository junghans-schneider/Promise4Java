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
        Promise promise = new Promise() {
            @Override
            protected void execute(Promise.Resolver resolver) {
                resolver.done(1234);
            }
        }.then(new PromiseHandler<Integer>() {
            @Override
            public Object onValue(Integer value) {
                // Nest other promise
                return new Promise() {
                    @Override
                    protected void execute(Promise.Resolver resolver) {
                        resolver.done("Hallo");
                    }
                };
            }
        }).then(new PromiseHandler<String>() {
            @Override
            public Object onValue(String value) {
                // Use result
                return null;
            }
            @Override
            public Object onError(Throwable thr) {
                // Handle error
                return null;
            }
        }).then(new PromiseHandler<Object>() {
            @Override
            public Object onError(Throwable thr) {
                // Handle error handler's error
                return null;
            }
        });

        // .all
        Promise promise1 = null;
        Promise promise2 = null;
        Promise promise3 = null;
        Promise.all("value", promise1, promise2, promise3)
                .then(new PromiseHandler<Object[]>() {
                    @Override
                    public Object onValue(Object[] values) {
                        // Use result
                        return null;
                    }
                });

        // Turn value or promise into promise
        Promise.when("value")
                .then(new PromiseHandler<Object[]>() {
                    @Override
                    public Object onValue(Object[] values) {
                        // Use result
                        return null;
                    }
                });

        // Executors
        Executor backgroundExecutor = null;
        Executor guiExecutor = null;

        new Promise(backgroundExecutor) {
            @Override
            protected void execute(Promise.Resolver resolver) {
                // Load file in background thread
                String content = null;
                resolver.done(content);
            }
        }.handle(new PromiseHandler<String>(guiExecutor) {
            @Override
            public Object onValue(String value) {
                // Show result in GUI
                return null;
            }
            @Override
            public Object onError(Throwable thr) {
                // Show error in GUI
                return null;
            }
        });
    }

    public void testSimpleValue() throws Throwable {
        final boolean[] handlerCalled = new boolean[] { false, false };
        Promise promise = new Promise() {
            @Override
            protected void execute(Promise.Resolver resolver) {
                assertFalse(handlerCalled[1]);
                handlerCalled[0] = true;
                resolver.done(1234);
            }
        }.then(new PromiseHandler<Integer>() {
            @Override
            public Object onValue(Integer value) {
                assertEquals(1234, value.intValue());
                return "my-result";
            }
            @Override
            public void onFinally() {
                assertTrue(handlerCalled[0]);
                handlerCalled[1] = true;
            }
        });

        assertTrue(handlerCalled[0]);
        assertTrue(handlerCalled[1]);
        assertEquals("my-result", promise.waitForResult(5000));
    }

    public void testComplexValue() throws Throwable {
        Promise promise = new Promise() {
            @Override
            protected void execute(Promise.Resolver resolver) {
                resolver.done(new Promise() {
                    @Override
                    protected void execute(Promise.Resolver resolver) {
                        resolver.done(new Promise() {
                            @Override
                            protected void execute(Promise.Resolver resolver) {
                                resolver.done(1234);
                            }
                        });
                    }
                });
            }
        }
                .then(new PromiseHandler<Object>() {})
                .then(new PromiseHandler<Object>() {})
                .then(new PromiseHandler<Integer>() {
                    @Override
                    public Object onValue(Integer value) {
                        assertEquals(1234, value.intValue());
                        return "handler-called";
                    }
                })
                .then(new PromiseHandler<Object>() {})
                .then(new PromiseHandler<Object>() {});

        assertEquals("handler-called", promise.waitForResult(5000));
    }

    public void testSimpleError() throws Throwable {
        final boolean[] handlerCalled = new boolean[] { false, false };
        Promise promise = new Promise() {
            @Override
            protected void execute(Promise.Resolver resolver) {
                throw new IllegalStateException("Test");
            }
        }.then(new PromiseHandler<Object>() {
            @Override
            public Object onError(Throwable thr) {
                assertFalse(handlerCalled[1]);
                handlerCalled[0] = true;
                assertTrue(thr instanceof IllegalStateException);
                assertEquals("Test", thr.getMessage());
                //thr.printStackTrace();
                return "my-result";
            }
            @Override
            public void onFinally() {
                assertTrue(handlerCalled[0]);
                handlerCalled[1] = true;
            }
        });

        assertTrue(handlerCalled[0]);
        assertTrue(handlerCalled[1]);
        assertEquals("my-result", promise.waitForResult(5000));
    }

    public void testComplexError() throws Throwable {
        Promise promise = new Promise() {
            @Override
            protected void execute(Promise.Resolver resolver) {
                resolver.done(new Promise() {
                    @Override
                    protected void execute(Promise.Resolver resolver) {
                        resolver.done(new Promise() {
                            @Override
                            protected void execute(Promise.Resolver resolver) {
                                throw new IllegalStateException("Test");
                            }
                        });
                    }
                });
            }
        }
                .then(new PromiseHandler<Object>() {})
                .then(new PromiseHandler<Object>() {})
                .then(new PromiseHandler<Object>() {
                    @Override
                    public Object onError(Throwable thr) {
                        assertTrue(thr instanceof IllegalStateException);
                        assertEquals("Test", thr.getMessage());
                        //thr.printStackTrace();
                        return "handler-called";
                    }
                })
                .then(new PromiseHandler<Object>() {})
                .then(new PromiseHandler<Object>() {});

        assertEquals("handler-called", promise.waitForResult(5000));
    }

    // TODO: Test handler error (onError should be called if onValue throws an exception)
    // TODO: Test class cast exception

    public void testSimpleCancel() throws Throwable {
        final boolean[] handlerCalled = new boolean[] { false, false };
        Promise firstPromise = waitForever();
        firstPromise.handle(new PromiseHandler<Object>() {
            @Override
            public void onCancel() {
                assertFalse(handlerCalled[1]);
                handlerCalled[0] = true;
            }
            @Override
            public void onFinally() {
                assertTrue(handlerCalled[0]);
                handlerCalled[1] = true;
            }
        });

        firstPromise.cancel();
        assertTrue(handlerCalled[0]);
        assertTrue(handlerCalled[1]);
    }

    public void testComplexCancelForward() throws Throwable {
        final boolean[] handlerCalled = new boolean[] { false };
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
                        resolver.done(promise);
                    }
                };
                promises.add(promise);
                resolver.done(promise);
            }
        };
        promises.add(outerPromise);

        promises.add(outerPromise.then(new PromiseHandler<Object>() {}));
        promises.add(promises.getLast().then(new PromiseHandler<Object>() {}));
        promises.add(promises.getLast().then(new PromiseHandler<Object>() {
            @Override
            public void onCancel() {
                handlerCalled[0] = true;
            }
        }));
        promises.add(promises.getLast().then(new PromiseHandler<Object>() {}));
        promises.add(promises.getLast().then(new PromiseHandler<Object>() {}));

        assertFalse(handlerCalled[0]);
        for (Promise promise : promises) {
            assertFalse(promise.isFinished());
            assertFalse(promise.isCancelled());
        }

        waitPromise[0].cancel();

        assertTrue(handlerCalled[0]);
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
        final boolean[] handlerCalled = new boolean[] { false };
        final LinkedList<Promise> promises = new LinkedList<Promise>();

        Promise outerPromise = new Promise() {
            @Override
            protected void execute(Promise.Resolver resolver) {
                Promise promise = new Promise() {
                    @Override
                    protected void execute(Promise.Resolver resolver) {
                        Promise promise = waitForever();
                        promises.add(promise);
                        resolver.done(promise);
                    }
                };
                promises.add(promise);
                resolver.done(promise);
            }
        };
        promises.add(outerPromise);

        promises.add(outerPromise.then(new PromiseHandler<Object>() {}));
        promises.add(promises.getLast().then(new PromiseHandler<Object>() {}));
        promises.add(promises.getLast().then(new PromiseHandler<Object>() {
            @Override
            public void onCancel() {
                handlerCalled[0] = true;
            }
        }));
        promises.add(promises.getLast().then(new PromiseHandler<Object>() {}));
        promises.add(promises.getLast().then(new PromiseHandler<Object>() {}));

        assertFalse(handlerCalled[0]);
        for (Promise promise : promises) {
            assertFalse(promise.isFinished());
            assertFalse(promise.isCancelled());
        }

        Promise lastPromise = promises.getLast();
        lastPromise.cancel(wholeChain);

        assertEquals(wholeChain, handlerCalled[0]);
        for (Promise promise : promises) {
            boolean expectCancelled = (wholeChain || promise == lastPromise);
            assertEquals(expectCancelled, promise.isFinished());
            assertEquals(expectCancelled, promise.isCancelled());
        }
    }

    public void testAllEmpty() {
        final boolean[] handlerCalled = new boolean[] { false };
        Promise.all()
                .then(new PromiseHandler<Object[]>() {
                    @Override
                    public Object onValue(Object[] values) throws Throwable {
                        handlerCalled[0] = true;
                        assertEquals(0, values.length);
                        return null;
                    }
                });
        assertTrue(handlerCalled[0]);
    }

    public void testAllValuesOnly() {
        final boolean[] handlerCalled = new boolean[] { false };
        Promise.all(123, "Hallo", true, 12.8d)
                .then(new PromiseHandler<Object[]>() {
                    @Override
                    public Object onValue(Object[] values) throws Throwable {
                        handlerCalled[0] = true;
                        assertEquals(4, values.length);
                        assertEquals(123, ((Integer) values[0]).intValue());
                        assertEquals("Hallo", values[1]);
                        assertEquals(true, ((Boolean) values[2]).booleanValue());
                        assertEquals(12.8, ((Double) values[3]).doubleValue());
                        return null;
                    }
                });
        assertTrue(handlerCalled[0]);
    }

    public void testAllDone() throws Exception {
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
                resolver.done("Bla");
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
                resolver.done(false);
            }
        };

        Promise outerPromise = Promise.all("Hallo", promise1, promise2, promise3)
                .then(new PromiseHandler<Object[]>() {
                    @Override
                    public Object onValue(Object[] values) throws Throwable {
                        handlerCalled[0] = true;
                        assertEquals(4, values.length);
                        assertEquals("Hallo", values[0]);
                        assertEquals(1234, ((Integer) values[1]).intValue());
                        assertEquals("Bla", values[2]);
                        assertEquals(false, ((Boolean) values[3]).booleanValue());
                        return null;
                    }
                });

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
                resolver.done(false);
            }
        };

        Promise outerPromise = Promise.all("Hallo", promise1, promise2, promise3)
                .then(new PromiseHandler<Object[]>() {
                    @Override
                    public Object onError(Throwable thr) throws Throwable {
                        handlerCalled[0] = true;
                        assertTrue(thr instanceof RuntimeException);
                        assertEquals("Test", thr.getMessage());
                        return null;
                    }
                });

        outerPromise.waitForResult();
        assertTrue(handlerCalled[0]);
    }

    public void testAllCancelForward() {
        final boolean[] handlerCalled = new boolean[] { false };

        Promise promise1 = waitForever();
        Promise promise2 = waitForever();
        Promise promise3 = waitForever();
        Promise allPromise = Promise.all(promise1, promise2, promise3);
        Promise handlerPromise = allPromise.then(new PromiseHandler<Object[]>() {
            @Override
            public void onCancel() {
                handlerCalled[0] = true;
            }
        });

        assertFalse(handlerCalled[0]);
        assertFalse(promise1.isFinished());
        assertFalse(promise2.isFinished());
        assertFalse(promise3.isFinished());
        assertFalse(allPromise.isFinished());
        assertFalse(handlerPromise.isFinished());

        promise2.cancel();

        assertTrue(handlerCalled[0]);
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
        final boolean[] handlerCalled = new boolean[] { false };

        Promise promise1 = waitForever();
        Promise promise2 = waitForever();
        Promise promise3 = waitForever();
        Promise allPromise = Promise.all(promise1, promise2, promise3);
        Promise handlerPromise = allPromise.then(new PromiseHandler<Object[]>() {
            @Override
            public void onCancel() {
                handlerCalled[0] = true;
            }
        });
        Promise lastPromise = handlerPromise.then(new PromiseHandler<Object>() {
        });

        assertFalse(handlerCalled[0]);
        assertFalse(promise1.isFinished());
        assertFalse(promise2.isFinished());
        assertFalse(promise3.isFinished());
        assertFalse(allPromise.isFinished());
        assertFalse(handlerPromise.isFinished());
        assertFalse(lastPromise.isFinished());

        lastPromise.cancel(wholeChain);

        assertEquals(wholeChain, handlerCalled[0]);
        assertEquals(wholeChain, promise1.isCancelled());
        assertEquals(wholeChain, promise2.isCancelled());
        assertEquals(wholeChain, promise3.isCancelled());
        assertEquals(wholeChain, allPromise.isCancelled());
        assertEquals(wholeChain, handlerPromise.isCancelled());
        assertTrue(lastPromise.isFinished());
    }

    // TODO: Test executors

    public void testDynamicChain() throws Throwable {
        Promise promise = loadParkings(10, 30);
        @SuppressWarnings("unchecked")
        List<Object> parkingList = (List<Object>) promise.waitForResult();
        assertTrue(parkingList.size() >= 30);
    }

    private Promise loadParkings(final int radius, final int minResultSize) {
        return new Promise() {
            @Override
            protected void execute(Resolver resolver) {
                List<Object> parkingList = Arrays.asList(new Object[radius]);
                resolver.done(parkingList);
            }
        }.then(new PromiseHandler<List<Object>>() {
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

}
