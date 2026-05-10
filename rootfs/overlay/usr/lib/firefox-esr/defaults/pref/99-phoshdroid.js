// Phoshdroid-specific Firefox overrides.
//
// Firefox's default multi-process architecture spawns content processes that
// rely on syscalls proot can't emulate (seccomp-bpf, PR_SET_NO_NEW_PRIVS,
// some shm primitives). Even with MOZ_DISABLE_CONTENT_SANDBOX the content
// children crash silently mid-navigation — searching, clicking a link, or
// opening a tab can take the whole browser with it.
//
// Forcing single-process / in-parent rendering sidesteps the problem: one
// process, no IPC fork, no sandbox. Less security isolation per-tab, which
// is acceptable on a single-user Linux-under-Android setup.
pref("fission.autostart", false);
pref("dom.ipc.processCount", 1);
pref("dom.ipc.processCount.webIsolated", 1);
pref("dom.ipc.forkserver.enable", false);
pref("extensions.webextensions.remote", false);
