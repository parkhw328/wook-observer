package com.wook.observer.platform;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.*;

class SshAuthenticationTest {
    private String key() {
        byte[] bytes = ByteBuffer.allocate(51).putInt(11).put("ssh-ed25519".getBytes(StandardCharsets.US_ASCII)).putInt(32).put(new byte[32]).array();
        return "ssh-ed25519 " + Base64.getEncoder().encodeToString(bytes);
    }

    @Test
    void acceptsPublicKeyCommentsAndKnownHostsButRejectsInvalidWireTypeAndMultipleKeys() {
        assertThat(HostKeys.normalize(key() + " user@server")).isEqualTo(key());
        assertThat(HostKeys.normalize("[server]:2222 " + key())).isEqualTo(key());
        assertThat(HostKeys.describe(key()).fingerprint()).startsWith("SHA256:").doesNotContain("=");
        assertThatThrownBy(() -> HostKeys.normalize(key().replace("ssh-ed25519 ", "ssh-rsa "))).isInstanceOf(ApiProblem.class);
        assertThatThrownBy(() -> HostKeys.normalize(key() + "\n" + key())).isInstanceOf(ApiProblem.class);
        assertThatThrownBy(() -> HostKeys.normalize("ssh-ed25519 " + "A".repeat(64))).isInstanceOf(ApiProblem.class);
    }

    @Test
    void sendsRootSecretOnlyOnceAfterNoEchoMarkerAndPromptEvenAcrossChunks() throws Exception {
        var handshake = new SshTransport.PrivilegeHandshake("SU_ROOT", "testnonce", "root-$'`! password");
        var input = new ByteArrayOutputStream();
        handshake.receive("banner Password:", input);
        assertThat(input.size()).isZero();
        handshake.receive("\r\nWOOK_READY_testnonce\r\nPass", input);
        assertThat(input.size()).isZero();
        handshake.receive("word:", input);
        assertThat(input.toString(StandardCharsets.UTF_8)).isEqualTo("root-$'`! password\n");
        handshake.receive("Password:", input);
        assertThat(input.toString(StandardCharsets.UTF_8)).isEqualTo("root-$'`! password\n");
        handshake.receive("\r\nWOOK_ROOT_testnonce\r\n", input);
        assertThat(handshake.started()).isTrue();
        assertThat(handshake.command("printf 'DONE\\n'\n")).contains("stty -echo", "su - root", "id -u").doesNotContain("root-$'`! password");
    }

    @Test
    void sudoUsesItsOwnPromptAndDoesNotSendPasswordWhenAlreadyAuthorized() throws Exception {
        var handshake = new SshTransport.PrivilegeHandshake("SUDO", "sudo", "login-password");
        var input = new ByteArrayOutputStream();
        handshake.receive("WOOK_READY_sudo\nPassword:", input);
        assertThat(input.size()).isZero();
        handshake.receive("WOOK_PASSWORD_sudo:", input);
        assertThat(input.toString(StandardCharsets.UTF_8)).isEqualTo("login-password\n");
        var allowed = new SshTransport.PrivilegeHandshake("SUDO_NOPASSWD", "allowed", "ignored");
        input.reset();
        allowed.receive("WOOK_READY_allowed\nWOOK_ROOT_allowed\nPassword:", input);
        assertThat(input.size()).isZero();
        assertThat(allowed.started()).isTrue();
        assertThat(allowed.command("true")).contains("sudo -n -k").doesNotContain("ignored");
    }
}
