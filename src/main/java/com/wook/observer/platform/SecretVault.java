package com.wook.observer.platform;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Component
@Profile("!collector")
public class SecretVault {
    private final SecretKeySpec key;
    public SecretVault(@Value("${observer.platform.encryption-key}") String encoded) {
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException e) { throw new IllegalStateException("OBSERVER_ENCRYPTION_KEY must be a base64 32-byte key"); }
        if (bytes.length != 32) { throw new IllegalStateException("Set OBSERVER_ENCRYPTION_KEY to a base64 32-byte key"); }
        key = new SecretKeySpec(bytes, "AES");
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] data = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + data.length).put(iv).put(data).array());
        } catch (Exception e) { throw new IllegalStateException("Credential encryption failed"); }
    }

    public String decrypt(String encrypted) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(Base64.getDecoder().decode(encrypted));
            byte[] iv = new byte[12]; buffer.get(iv);
            byte[] data = new byte[buffer.remaining()]; buffer.get(data);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(data), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new ApiProblem(500, "SSH 자격 증명 복호화 실패. 암호화 키를 확인하세요."); }
    }
}
