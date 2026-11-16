package com.wook.observer.platform;

import org.springframework.core.io.ClassPathResource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Fixed inventory commands over OpenSSH. Only the selected authentication method is attempted. */
final class SshTransport {
    private static final int MAX_OUTPUT = 16 * 1024 * 1024;
    private final Targets targets;
    private final Path askpass;
    SshTransport(Targets targets, Path askpass) { this.targets = targets; this.askpass = askpass.toAbsolutePath(); }

    String collect(Map<String, Object> target) throws Exception {
        long started = System.nanoTime();
        Path directory = Files.createTempDirectory("observer-ssh-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Process process = null;
        var readers = Executors.newVirtualThreadPerTaskExecutor();
        String mode = Store.string(target, "privilege_mode");
        if (mode.isEmpty()) { mode = "NONE"; }
        boolean elevate = !"NONE".equals(mode);
        OperationLog.info("ssh.started", "host", target.get("host"), "port", target.get("port"),
                "authType", target.get("auth_type"), "privilegeMode", mode);
        try {
            String host = Store.string(target, "host"), port = Store.string(target, "port");
            Path known = privateFile(directory, "known_hosts", ("22".equals(port) ? host : "[" + host + "]:" + port) + " " + Store.string(target, "host_key") + "\n");
            var command = new ArrayList<>(List.of("ssh", "-F", "/dev/null", elevate ? "-tt" : "-T", "-e", "none",
                    "-o", "IdentitiesOnly=yes", "-o", "IdentityAgent=none", "-o", "StrictHostKeyChecking=yes", "-o", "UpdateHostKeys=no",
                    "-o", "GlobalKnownHostsFile=/dev/null", "-o", "UserKnownHostsFile=" + known,
                    "-o", "ConnectTimeout=10", "-o", "ConnectionAttempts=1", "-o", "ServerAliveInterval=10", "-o", "ServerAliveCountMax=2",
                    "-o", "ForwardAgent=no", "-o", "ClearAllForwardings=yes", "-o", "LogLevel=ERROR",
                    "-o", "NumberOfPasswordPrompts=1", "-o", "KbdInteractiveAuthentication=no"));
            String sshSecret;
            if ("PASSWORD".equals(Store.string(target, "auth_type"))) {
                sshSecret = targets.decrypt(target, "ssh_password");
                command.addAll(List.of("-o", "BatchMode=no", "-o", "PreferredAuthentications=password", "-o", "PubkeyAuthentication=no"));
            } else {
                Path identity = privateFile(directory, "identity", targets.decryptKey(target));
                sshSecret = targets.decrypt(target, "key_passphrase");
                command.addAll(List.of("-o", "BatchMode=" + (sshSecret.isEmpty() ? "yes" : "no"), "-o", "PreferredAuthentications=publickey",
                        "-o", "PasswordAuthentication=no", "-i", identity.toString()));
            }
            var builder = new ProcessBuilder();
            builder.environment().put("LC_ALL", "C");
            builder.environment().remove("SSH_AUTH_SOCK");
            builder.environment().remove("SSH_ASKPASS");
            builder.environment().remove("SSH_ASKPASS_REQUIRE");
            if (!sshSecret.isEmpty()) {
                if (!Files.isExecutable(askpass)) { throw new ApiProblem(500, "중앙 서버의 SSH 비밀번호 입력 도구 경로를 확인하세요."); }
                Path secret = privateFile(directory, "ssh_secret", sshSecret);
                builder.environment().put("SSH_ASKPASS", askpass.toString());
                builder.environment().put("SSH_ASKPASS_REQUIRE", "force");
                builder.environment().put("DISPLAY", "observer:0");
                builder.environment().put("OBSERVER_SSH_SECRET_FILE", secret.toString());
            }
            String inventory;
            try (var resource = new ClassPathResource("collector/ssh-inventory.sh").getInputStream()) {
                inventory = new String(resource.readAllBytes(), StandardCharsets.UTF_8).replace("# SYSTEM_INVENTORY",
                        com.wook.observer.collector.SystemInventoryCollector.script());
            }
            try (var resource = new ClassPathResource("collector/ssh-libraries.py").getInputStream()) {
                inventory = inventory.replace("# LIBRARY_INVENTORY", new String(resource.readAllBytes(), StandardCharsets.UTF_8));
            }
            var handshake = new PrivilegeHandshake(mode, UUID.randomUUID().toString().replace("-", ""),
                    elevate ? targets.decrypt(target, "privilege_password") : "");
            command.addAll(List.of("-p", port, Store.string(target, "ssh_user") + "@" + host,
                    elevate ? handshake.command(inventory) : "sh -s"));
            process = builder.command(command).start();
            Process child = process;
            // Drain stderr without retaining unbounded remote output or writing it to logs.
            Future<String> diagnostic = readers.submit(() -> diagnostic(child.getErrorStream()));
            var ready = new CompletableFuture<Boolean>();
            var context = org.slf4j.MDC.getCopyOfContextMap();
            var output = readers.submit(() -> {
                try (var ignored = OperationLog.context(context); InputStream input = child.getInputStream(); var bytes = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int length;
                    while ((length = input.read(buffer)) != -1) {
                        if (bytes.size() + length > MAX_OUTPUT) { throw new OutputLimitException(); }
                        bytes.write(buffer, 0, length);
                        if (elevate && !handshake.started()) {
                            handshake.receive(new String(buffer, 0, length, StandardCharsets.UTF_8), child.getOutputStream());
                            if (handshake.started()) { ready.complete(true); }
                        }
                    }
                    return bytes.toString(StandardCharsets.UTF_8);
                } catch (Exception e) {
                    try (var ignored = OperationLog.context(context)) { OperationLog.failure("ssh.output.failed", e, "limitBytes", MAX_OUTPUT); }
                    child.destroyForcibly();
                    throw e;
                } finally { ready.complete(false); }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(180);
            if (elevate) {
                try {
                    if (!ready.get(30, TimeUnit.SECONDS)) {
                        OperationLog.warn("ssh.privilege.failed", "privilegeMode", mode, "reason", diagnostic(diagnostic), "durationMs", OperationLog.elapsed(started));
                        throw new ApiProblem(502, "SSH 연결 또는 root 전환 실패. 접속 인증·su/sudo 권한·전환 비밀번호를 확인하세요. 일반 권한으로 대체 점검하지 않았습니다.");
                    }
                    OperationLog.info("ssh.privilege.confirmed", "privilegeMode", mode, "uid", 0);
                } catch (TimeoutException e) {
                    OperationLog.warn("ssh.timeout", "phase", "PRIVILEGE", "limitSeconds", 30);
                    throw new ApiProblem(502, "root 전환 응답 시간(30초)을 초과했습니다. su/sudo 및 터미널 인증 정책을 확인하세요.");
                }
            } else {
                try (var stdin = process.getOutputStream()) { stdin.write(inventory.getBytes(StandardCharsets.UTF_8)); }
            }
            if (!process.waitFor(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                OperationLog.warn("ssh.timeout", "phase", "COLLECTION", "limitSeconds", 180);
                throw new ApiProblem(502, "SSH 점검 시간 제한(180초)을 초과했습니다.");
            }
            if (process.exitValue() != 0) {
                OperationLog.warn("ssh.exit.failed", "exitCode", process.exitValue(), "reason", diagnostic(diagnostic), "durationMs", OperationLog.elapsed(started));
                throw new ApiProblem(502, "SSH 연결/수집 실패. 호스트 키·선택한 인증 방식·비밀번호·계정 권한·Linux 지원 여부를 확인하세요.");
            }
            String result = output.get(5, TimeUnit.SECONDS);
            OperationLog.info("ssh.completed", "exitCode", 0, "outputBytes", result.getBytes(StandardCharsets.UTF_8).length, "durationMs", OperationLog.elapsed(started));
            return result;
        } finally {
            if (process != null) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                try { process.getOutputStream().close(); } catch (IOException ignored) { /* Still remove temporary credentials. */ }
            }
            readers.shutdownNow();
            for (String file : List.of("identity", "known_hosts", "ssh_secret")) { Files.deleteIfExists(directory.resolve(file)); }
            Files.deleteIfExists(directory);
        }
    }

    private static final class OutputLimitException extends IOException { }

    private static String diagnostic(Future<String> diagnostic) {
        try { return diagnostic.get(2, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return "INTERRUPTED"; }
        catch (Exception e) { return "DIAGNOSTIC_UNAVAILABLE"; }
    }

    static String diagnostic(InputStream input) throws IOException {
        try (input; var bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int length;
            while ((length = input.read(buffer)) != -1) bytes.write(buffer, 0, Math.min(length, Math.max(0, 16_384 - bytes.size())));
            return diagnosticCode(bytes.toString(StandardCharsets.UTF_8));
        }
    }

    static String diagnosticCode(String output) {
        String text = output.toLowerCase(Locale.ROOT);
        if (text.contains("host key verification failed") || text.contains("remote host identification has changed")) return "HOST_KEY_REJECTED";
        if (text.contains("permission denied") || text.contains("authentication failed")) return "AUTHENTICATION_FAILED";
        if (text.contains("could not resolve hostname")) return "DNS_FAILED";
        if (text.contains("connection refused")) return "CONNECTION_REFUSED";
        if (text.contains("connection timed out") || text.contains("operation timed out")) return "CONNECT_TIMEOUT";
        if (text.contains("no route to host") || text.contains("network is unreachable")) return "NETWORK_UNREACHABLE";
        if (text.contains("invalid format") || text.contains("error in libcrypto")) return "KEY_FORMAT";
        return "REMOTE_EXIT_OR_PRIVILEGE_FAILURE";
    }

    private static Path privateFile(Path directory, String name, String content) throws IOException {
        Path file = Files.createFile(directory.resolve(name), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(file, content);
        return file;
    }

    static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }

    /** Send one credential only after our no-echo marker and the selected privilege prompt. */
    static final class PrivilegeHandshake {
        private final String mode, password, readyMark, startMark, prompt;
        private final StringBuilder pending = new StringBuilder();
        private boolean sent, started;
        PrivilegeHandshake(String mode, String nonce, String password) {
            this.mode = mode; this.password = password;
            readyMark = "WOOK_READY_" + nonce;
            startMark = "WOOK_ROOT_" + nonce;
            prompt = "WOOK_PASSWORD_" + nonce + ":";
        }
        String command(String inventory) {
            String script = "[ \"$(id -u)\" = 0 ] || exit 73\nprintf '\\n" + startMark + "\\n'\n" + inventory;
            String command = switch (mode) {
                case "SU_ROOT" -> "su - root -s /bin/sh -c " + quote(script);
                case "SUDO" -> "sudo -k -S -p " + quote(prompt) + " -- /bin/sh -c " + quote(script);
                case "SUDO_NOPASSWD" -> "sudo -n -k -- /bin/sh -c " + quote(script);
                default -> throw new IllegalArgumentException("Unsupported privilege mode");
            };
            return "export LC_ALL=C; stty -echo || exit 72; printf '\\n" + readyMark + "\\n'; exec " + command;
        }
        void receive(String text, OutputStream stdin) throws IOException {
            pending.append(text);
            if (pending.length() > 65_536) { throw new IOException("Privilege handshake output limit"); }
            String value = pending.toString();
            int ready = value.indexOf(readyMark);
            if (ready < 0) { return; }
            value = value.substring(ready + readyMark.length());
            if (value.contains(startMark)) { started = true; pending.setLength(0); return; }
            boolean requested = "SU_ROOT".equals(mode) ? value.toLowerCase(Locale.ROOT).contains("password:") : value.contains(prompt);
            if (!sent && requested && !password.isEmpty() && !"SUDO_NOPASSWD".equals(mode)) {
                stdin.write((password + "\n").getBytes(StandardCharsets.UTF_8));
                stdin.flush();
                sent = true;
            }
        }
        boolean started() { return started; }
    }
}
