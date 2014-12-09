//
//  Created by Til Schneider <github@murfman.de> on 09.12.14.
//  Copyright © 2014 Junghans und Schneider. License: MIT
//  https://github.com/junghans-schneider/Promise4Java
//

package de.junghansschneider.promise;

import java.util.concurrent.Executor;

public abstract class PromiseHandler<InputType> {

    private Executor mExecutor;


    public PromiseHandler() {
    }

    public PromiseHandler(Executor executor) {
        mExecutor = executor;
    }

    public Executor getExecutor() {
        return mExecutor;
    }

    public Object onValue(InputType value) throws Throwable {
        return value;
    }

    public Object onError(Throwable thr) throws Throwable {
        throw thr;
    }

    public void onCancel() {
    }

    public void onFinally() {
    }

}
