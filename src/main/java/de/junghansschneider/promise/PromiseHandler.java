//
//  Created by Til Schneider <github@murfman.de> on 09.12.14.
//  Copyright © 2014 Junghans und Schneider. License: MIT
//  https://github.com/junghans-schneider/Promise4Java
//

package de.junghansschneider.promise;

public abstract class PromiseHandler<InputType> {

    public Object onValue(InputType value) throws Throwable {
        return value;
    }

    public Object onError(Throwable thr) throws Throwable {
        throw thr;
    }

    public void always() {
    }

}
