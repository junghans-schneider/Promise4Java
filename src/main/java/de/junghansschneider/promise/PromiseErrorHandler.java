//
//  Created by Til Schneider <github@murfman.de> on 18.03.18.
//  Copyright © 2014 Junghans und Schneider. License: MIT
//  https://github.com/junghans-schneider/Promise4Java
//

package de.junghansschneider.promise;

public interface PromiseErrorHandler {

    void onError(Throwable thr);

}
