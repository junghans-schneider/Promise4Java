//
//  Created by Til Schneider <github@murfman.de> on 09.12.14.
//  Copyright © 2014 Junghans und Schneider. License: MIT
//  https://github.com/junghans-schneider/Promise4Java
//

package de.junghansschneider.promise;

import java.util.concurrent.Executor;

public class Deferred<ValueType> implements Promise.Resolver<ValueType> {

    private Promise.Resolver<ValueType> mResolver;
    private Promise<ValueType> mPromise;


    public Deferred() {
        this(null);
    }

    public Deferred(Executor executor) {
        mPromise = new Promise<ValueType>(executor) {
            protected void execute(Promise.Resolver<ValueType> resolver) {
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

    public void resolve(ValueType value) {
        mResolver.resolve(value);
    }

    public void resolve(Promise<ValueType> valuePromise) {
        mResolver.resolve(valuePromise);
    }

    public void reject(Throwable thr) {
        mResolver.reject(thr);
    }

    public boolean isCancelled() {
        return mPromise.isCancelled();
    }

}
