package com.wxx.aidocumentagent.progress;

/** Replace with a user-aware policy when authentication is introduced. */
@FunctionalInterface
public interface ProgressSubscriptionAuthorizer {
    boolean maySubscribe(ProgressSubscriber subscriber, ProgressSubscriptionRequest request);
}
