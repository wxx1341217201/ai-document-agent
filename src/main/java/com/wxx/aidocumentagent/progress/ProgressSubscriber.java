package com.wxx.aidocumentagent.progress;

import java.security.Principal;

public record ProgressSubscriber(Principal principal, boolean sharedTokenAuthenticated) { }
