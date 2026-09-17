package io.github.ldogg123.gregscope;

/** The last GregScope lifecycle handler that ran, in the order FML calls them. [pure] */
public enum LifecyclePhase {
    CONSTRUCTED,
    PRE_INIT,
    INIT,
    POST_INIT,
    SERVER_STARTING,
    SERVER_STARTED,
    SERVER_STOPPING,
    SERVER_STOPPED
}
