//
//  Created by Til Schneider <github@murfman.de> on 09.12.14.
//  Copyright © 2014 Junghans und Schneider. License: MIT
//  https://github.com/junghans-schneider/Promise4Java
//

package de.junghansschneider.promise;

import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;

public class Deferred implements Promise.Resolver {

    private Promise.Resolver mResolver;
    private Promise mPromise;


    public Deferred() {
        this(null);
    }

    public Deferred(Executor executor) {
        mPromise = new Promise(executor) {
            protected void execute(Promise.Resolver resolver) {
                mResolver = resolver;
                Deferred.this.execute();
            }
        };
    }

    protected void execute() {
    }

    public Promise getPromise() {
        return mPromise;
    }

    public void done(Object value) {
        mResolver.done(value);
    }

    public void fail(Throwable thr) {
        mResolver.fail(thr);
    }

    public boolean isCancelled() {
        return mPromise.isCancelled();
    }

    public void assertNotCancelled() {
        if (isCancelled()) {
            throw new CancellationException();
        }
    }

}
