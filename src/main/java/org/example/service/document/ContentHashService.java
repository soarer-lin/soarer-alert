package org.example.service.document;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
public class ContentHashService {

    private static final String ALGORITHM = "SHA-256";
    private static final int BUFFER_SIZE = 8192;

    public String sha256(byte[] data) {
        if (data == null) {
            throw new DocumentProcessingException("文件内容为空，无法计算 Hash");
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(ALGORITHM).digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }

    public String sha256(InputStream inputStream) {
        if (inputStream == null) {
            throw new DocumentProcessingException("文件输入流为空，无法计算 Hash");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        } catch (IOException e) {
            throw new DocumentProcessingException("读取文件失败，无法计算 Hash: " + e.getMessage(), e);
        }
    }
}
