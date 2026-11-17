package com.alienbooster.claimer;

interface IRemoteShell {
    String exec(String command, int timeoutMs);
}
